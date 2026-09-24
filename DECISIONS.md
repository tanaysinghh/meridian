# Meridian — Architecture Decisions

The non-obvious choices behind the code, kept as a living reference.

## Monorepo layout

```
meridian/
├── apps/
│   ├── api/       Java 21 · Spring Boot · Postgres. REST + STOMP. JWT auth.
│   ├── web/       React · Vite · Tailwind. Landing + dashboard.
│   └── ml/        FastAPI · LightGBM. Risk-scoring microservice.
├── packages/
│   └── shared/    Shared TypeScript types + risk-tier enums.
├── fixtures/      Mock GitHub webhook payloads (dev-only replay).
├── scripts/       Fixture replay (refuses to run in prod / non-localhost).
└── docker-compose.yml    Postgres for local dev.
```

Three deployable units; a monorepo keeps shared contracts (webhook payload
shapes, risk-tier enums, PR schema) in one place without publishing packages.

## Data flow

1. GitHub webhook → `POST /webhooks/github` → HMAC-verifies signature
   (fail-closed in prod) → normalizes into `pull_request_events`.
2. On PR open / synchronize → API enqueues scoring → calls `ml/score` →
   stores score + explanation in `pr_risk_scores`.
3. Rules engine runs **after** the ML score → applies per-org overrides →
   tier decided by `max(rule_tier, model_tier)` (rules can escalate,
   never de-escalate).
4. The updated PR is pushed to dashboards subscribed to the org's STOMP
   destination, `/topic/org.{orgId}`.
5. A post-merge job (nightly) checks for reverts / hotfixes and writes
   outcomes to `pr_outcomes` — the training signal for future model runs.

## Auth

- Primary: GitHub OAuth (real flow, no dev shortcut).
- Fallback: email + password (bcrypt cost 12) + JWT.
- Access token in an httpOnly, `SameSite=Lax`, `Secure`-in-prod cookie
  (15 min TTL).
- Refresh token in a separate httpOnly cookie (30 day default). Rotated on
  every refresh; the old session row is deleted from `sessions`.
- Roles: `developer` / `team_lead` / `admin`, enforced in middleware.
- CSRF: double-submit token. API sets a readable `mrd_csrf` cookie on every
  safe request; browsers echo it back in `X-CSRF-Token` on mutations.

## Risk scoring — deliberately explainable

- **Features**: PR size, files touched, hot-file overlap, author revert
  rate, time-of-day, day-of-week, touches-auth flag, commit-message
  quality, review-comment density.
- **Model**: LightGBM classifier trained on synthetic labels at first;
  swap in real outcome data as `pr_outcomes` fills.
- **Explainability**: per-prediction SHAP-lite contributions in the same
  response. UI renders top 5 positive + top 3 negative contributors.
- **Confidence**: low if `author_pr_count < 5` or `repo_pr_count < 20`.
- **Fallback**: if the ML service is unreachable, `mlClient.scorePR`
  degrades to a rule-only heuristic score so webhook ingestion doesn't
  stall on ML being down.

## Rules engine

- Stored per-repo in `repo_rules` (JSONB). Predicates validated at write
  time as a Jackson polymorphic union keyed on `type` (`path_glob`,
  `touches_paths`, `author_in`, `size_gt`, `regex_match`), with Bean
  Validation constraints per arm, so unexpected shapes never reach the
  evaluator.
- The evaluator reads the stored JSON rather than the request DTOs, so a
  rule written before a validation change still evaluates, and one
  malformed rule is skipped instead of failing the whole scoring run.
- Rules can escalate a tier and attach a labelled reason. They never
  de-escalate.

## Realtime

- STOMP over WebSocket at `/live`, backed by Spring's simple in-memory
  broker. One destination per org: `/topic/org.{orgId}`.
- STOMP frames carry a destination but no event name, so the event moves
  into the message body as `{event, data}`. The frontend's socket module
  unwraps it and re-emits, which is why the rest of the app still calls
  `.on('pr.scored', ...)` unchanged. Events: `pr.updated`, `pr.scored`.
- Two-stage auth. The HTTP upgrade reads the `mrd_at` cookie (a browser
  cannot set headers on a WebSocket handshake); the CONNECT frame can
  alternatively carry a token for non-browser clients. Critically, every
  SUBSCRIBE is checked against the session's own org — destinations are
  client-chosen strings, so without that check any authenticated user
  could subscribe to another org's feed by guessing a UUID.

## Production hardening

Everything in this section is on by default in production.

**Removed dev shortcuts.** No silent OAuth demo login, no `demo1234`
password, no login-form defaults, no webhook signature bypass. Each of
these previously returned a working shortcut; each now returns an explicit
error unless explicitly re-enabled for local dev via an env flag.

**Config validation** (`config/StartupChecks.java`). Fails fast in
production if `JWT_SECRET`, `DATABASE_URL`, or `GITHUB_WEBHOOK_SECRET` is
missing; rejects a JWT secret shorter than 32 chars, the dev fallback
secret, wildcard CORS origins, and `ALLOW_UNSIGNED_WEBHOOKS=true`. Runs
during context refresh, so a misconfigured instance never starts
accepting traffic.

**HTTP hardening.** Spring Security headers with a tight CSP
(`default-src 'none'` — this service serves JSON, no HTML sources
needed), `X-Frame-Options: DENY`, HSTS, `Referrer-Policy`,
`Cross-Origin-Resource-Policy: same-site`, `Permissions-Policy`. CORS
locked to `ALLOWED_ORIGINS` (wildcards refused at startup). Rate limiting
via bucket4j at 10/15 min on `/auth/*` and 300/min on the rest, keyed by
client IP, with health and webhook paths exempt.

**Data-layer hygiene.** Jakarta Bean Validation on every mutating
endpoint. All persistence goes through Spring Data JPA; the few native
queries are Postgres-specific reads (`LATERAL`, `date_trunc`,
`PERCENTILE_CONT`, `unnest`) and every one uses named parameters — no
string concatenation into SQL, including the optional filters on
`GET /prs`, which use `(cast(:p as text) is null or ...)` so the
statement shape never depends on input. Bcrypt cost 12 (tunable);
password minimum 12 chars enforced at bootstrap.

**Auth.** JWTs signed with issuer/audience claims; access + refresh
cookies are httpOnly, `SameSite=Lax`, `Secure` in production. Refresh
rotation deletes the old session row and issues a new one on every use.
CSRF double-submit on all mutating routes.

**Logging & errors.** One structured line per request carrying only
method, path, status, duration and user id — headers and bodies are never
logged, so tokens, cookies and passwords cannot reach the log in the
first place. `common/SensitiveDataRedactor` covers the remaining cases
(webhook payload fragments, upstream error bodies) with the same key list
pino redacted. `GlobalExceptionHandler` returns
`{"error": "internal_error"}` in production and only attaches
message/stack when the dev profile asks for it.

**Lifecycle.** Graceful shutdown (`server.shutdown: graceful`) drains
in-flight requests, and the scoring executor waits up to 20s for queued
work. `/health/live` and `/health/ready` on both API and ML, with the
same response bodies as before. Flyway applies migrations at startup
before the service begins serving.

**ML service.** `/docs`, `/redoc`, and `/openapi.json` are disabled.
Public URL is gated by a shared-secret `X-Internal-Secret` header
(constant-time compare, generic 401); refuses to boot in production if
the secret is unset. Error paths never leak stack traces to callers.

**Frontend.** Production bundle strips `console.log` and `console.debug`
via esbuild; source maps are dev-only.

**Fixture replay.** Refuses to run when `NODE_ENV=production` or when
`API_URL` isn't localhost.

## Migration to Spring Boot

`apps/api` moved from Node/Express to Java 21 + Spring Boot 4. The goal was a
like-for-like replacement: same routes, same request and response shapes, so
`apps/web` keeps working. This section records what that cost and where the
translation is not exactly one-to-one.

### Verification method

Both services were run side by side against the same database and every
endpoint compared. All 21 GET endpoints returned structurally identical JSON,
and spot-checked values matched byte for byte. 26 further checks covered the
mutating endpoints, auth failure modes, CSRF enforcement, refresh-token
rotation and webhook signature rejection. That comparison is what surfaced the
two serialization differences below.

Endpoint-level diffing was not sufficient on its own. A second pass drove the
frontend's own `api.js` and `socket.js` modules — the real files, with browser
globals shimmed — through the Vite dev proxy against the running service. That
is what caught the CSRF rotation bug, which only appears across a *sequence* of
requests and so is invisible when each endpoint is checked in isolation.

### Package by feature, not by layer

`com.meridian.api.<feature>` — `auth`, `pullrequests`, `rules`, `analytics`,
`webhooks`, and so on, each holding its own controller, service, entity,
repository and DTOs. Layer-first packaging (`controllers/`, `services/`,
`repositories/`) would have spread a single change across four directories;
here the PR feature is one directory. `common/` and `config/` hold the
genuinely cross-cutting pieces.

### DTOs, and why the JSON shape is pinned explicitly

Entities are never serialized. Every response goes through a record with an
explicit `@JsonProperty` on each field, because the previous API returned raw
`pg` rows and the frontend reads those exact snake_case keys — inferring them
from a naming strategy would have been one silent rename away from a broken
dashboard.

Two serialization behaviours had to be restored deliberately
(`config/JacksonConfig.java`):

- **`NUMERIC` serializes as a JSON string.** node-postgres does not parse
  `NUMERIC` into a JS number (it would lose precision outside the double
  range), so the old API emitted `"score": "0.524"` and
  `"risk_threshold": "0.550"`. Jackson would naturally emit numbers. Strings
  are preserved, scale included.
- **Timestamps are millisecond-precision ISO-8601.** Postgres stores
  `TIMESTAMPTZ` at microsecond precision, so Java reads `…054327Z` where
  node-postgres — parsing into a millisecond JS `Date` — produced `…054Z`.
  Truncated to match.

Both are pure contract fidelity; the frontend tolerates either form. If the
contract is ever revised deliberately, `JacksonConfig` is the one place to
undo them.

### No schema changes

The existing tables map cleanly to JPA entities and nothing was altered.
Postgres `TEXT[]` columns map to `List<String>` via `@JdbcTypeCode(ARRAY)`;
the composite primary keys on `file_hotness` and `file_ownership` use
`@IdClass`. `jsonb` columns are held as raw `String` and passed through with
`common/RawJson`, which writes them back verbatim — the rule predicates and
the ML service's `contributions` are open-ended shapes owned elsewhere, and
round-tripping them through Java types would risk changing them for no gain.

`role` and `tier` became Java enums with an `AttributeConverter` so the stored
values stay the lowercase strings the `CHECK` constraints already enforce.

### Native SQL is retained where JPQL cannot express the query

The analytics aggregates, the PR list and the reviewer-load query stay as
native SQL, carried over statement-for-statement. Each depends on something
JPQL has no equivalent for: the `LEFT JOIN LATERAL … ORDER BY scored_at DESC
LIMIT 1` that picks each PR's latest score, `date_trunc`, `PERCENTILE_CONT …
WITHIN GROUP`, `EXTRACT(ISODOW …)`, `unnest` over a `TEXT[]`, and interval
arithmetic against a column. Keeping the SQL unchanged also means the numbers
on the dashboard could not drift during the migration. They run through
`EntityManager` returning `Tuple`, mapped to records by `common/Tuples`.

### Flyway adopts the existing schema

`V1__initial_schema.sql` is a direct translation of the old hand-rolled
`schema.sql`, `CREATE … IF NOT EXISTS` included, paired with
`baseline-on-migrate: true`. That lets it apply cleanly to a database the old
`migrate.js` already provisioned as well as to an empty one. Later versions
should use plain DDL — Flyway tracks what has run from V1 onward.

### Behaviour changes worth knowing about

**`size_gt` rules now work — a deliberate behaviour change.** The Zod schema
validated the field as `lines` while the evaluator read `pred.value`, so a
`size_gt` rule created through the API could never match: it validated, saved,
showed as enabled in the rules list, and silently never fired. The evaluator now
reads `lines`, falling back to `value` for any rule written directly against the
old field name.

> **What changes:** every enabled `size_gt` rule starts escalating PRs whose
> additions + deletions exceed its `lines` threshold. Those PRs were previously
> unaffected by the rule, so some will now report a higher tier — and, if the new
> tier is in `notify_on_tiers`, trigger Slack alerts they did not before.

This was chosen over preserving the bug because a rule that is enabled, saved and
incapable of matching is a defect, not behaviour worth carrying forward. Contrast
`regex_match` below, where the opposite call was made for the opposite reason.

**`regex_match` still matches file paths, not `target`.** The schema accepts a
`target` of `title`/`body`/`diff`, but the evaluator has always tested the
pattern against file paths and ignored it. That behaviour is preserved rather
than "fixed", because unlike `size_gt` these rules do fire today and changing
what they match would alter live scoring. The field remains accepted and
stored but unused.

**Webhook org resolution is deterministic.** The old query was
`SELECT org_id FROM repos WHERE full_name=$1 UNION SELECT id FROM orgs LIMIT 1`,
where the `LIMIT 1` applied to the union — so for an unknown repo the org
depended on Postgres' row order. It is now: the repo's own org if connected,
otherwise the oldest org. Same intent, no longer arbitrary.

**Login trims before validating.** The old Zod chain was
`.trim().toLowerCase().email()`, so `"  Dev@Example.test  "` was accepted.
`LoginRequest` normalises in its canonical constructor so validation sees the
trimmed value, preserving that.

**Validation messages differ in wording.** Status codes and the
`invalid_request: <field>: <message>` envelope match, but the text inside comes
from Bean Validation rather than Zod. The frontend switches on the `error`
prefix, not the detail.

**CSRF token rotation had to be suppressed.** Spring attaches
`CsrfAuthenticationStrategy` to the filter chain, which rotates the CSRF token
whenever a request authenticates. That suits form login, where authentication
happens once per session. This API re-authenticates from the JWT on *every*
request, so it fired every time: the token was deleted and its replacement
deferred, leaving the `mrd_csrf` cookie alternating between a value and empty on
successive calls. Roughly half of all dashboard mutations came back 403,
including logout — intermittently, which is the worst way for it to fail.
`config/StableCsrfTokenRepository` wraps the cookie repository and ignores the
delete, so the token lives as long as its cookie and is cleared at exactly one
point: `AuthCookies.clearAll()` on logout. The CSRF defence is unchanged
otherwise — still random per browser, still `SameSite=Lax`, still required on
every mutating request — and with a stateless JWT there is no session to fixate.
Caught by the browser-path verification, not by the endpoint diff, because it
only shows up across a *sequence* of requests.

**Scoring concurrency.** The old handler fired scoring with a floating promise.
It now runs on a bounded executor (`config/AsyncConfig`) with a
`CallerRunsPolicy`: under a burst the webhook thread does the work itself,
which throttles at the source rather than growing an unbounded queue.

### Not ported

**The demo dataset seeder.** `npm run db:seed` did two jobs. The admin
bootstrap — the production-critical half — is ported as
`admin/BootstrapSeeder`, run with `--seed` or `MERIDIAN_SEED=true`, with the
same guards (required in prod, 12-character minimum, idempotent). The demo
fixture dataset is not; `scripts/replay-fixtures.js` populates PR data locally
instead.

**The weekly digest is still unscheduled.** `digest/DigestService` is a
faithful port including the HTML template, and like its predecessor it exposes
`sendWeeklyDigest` for an external trigger. Nothing schedules it.

### Dependency notes

`DATABASE_URL` is accepted in the libpq URI form Render and docker-compose
emit, converted to JDBC at startup (`config/DatabaseUrl`) so the environment
variable list did not have to change. Spring Boot 4 splits auto-configuration
into per-technology modules, which is why the build depends on
`spring-boot-starter-flyway` and `spring-boot-webmvc-test` explicitly rather
than getting them transitively.

## Render deploy config

The Blueprint (`render.yaml`) provisions four resources in one apply:

- **`meridian-db`** — managed Postgres 15. Authoritative in production;
  see "Database provenance" below.
- **`meridian-ml`** — public web service, gated by a shared-secret
  `X-Internal-Secret` header. Render's free tier doesn't offer private
  services (`type: pserv` needs a paid plan), so the scorer runs as a
  normal public web service and authenticates at the app layer instead.
  The header value (`ML_INTERNAL_SECRET`) is auto-generated on the ml
  service; the api reads the same value via `fromService` binding so
  they can't drift.
- **`meridian-api`** — Docker web service built from `apps/api/Dockerfile`
  (Maven build stage, JRE-only runtime, non-root user). Render has no
  first-class Java runtime, and the image pins both the build JDK and the
  runtime JRE. Flyway migrates the schema during startup, so there is no
  separate migration command to orchestrate. Health check: `/health/ready`.
- **`meridian-web`** — Vite static site. Reads `VITE_API_BASE` at
  **build** time; the same-origin `/api` proxy pattern only works in dev.

**Tradeoff of the public-with-header design.** App-layer auth blocks
unauthorized *use* of `/score`, not floods of unauthorized *attempts* —
Render's platform-level rate limiting is the only defense against the
latter on the free tier. Revisit if we upgrade to a plan with `pserv`.

Secrets generated by Render (`JWT_SECRET`, `GITHUB_WEBHOOK_SECRET`,
`ML_INTERNAL_SECRET`) use `generateValue: true`, so they're never in the
repo. Every "you supply" value (`GITHUB_CLIENT_*`, CORS origins, admin
bootstrap creds, the ml service URL) uses `sync: false` — Render's
dashboard prompts for each on first Blueprint apply.

The admin bootstrap is a one-off: `ADMIN_EMAIL` / `ADMIN_PASSWORD` are read
only when the service is started with `--seed`, so ordinary deploys never
touch user records.

## Deployment pitfalls

Recorded because the first production rollout looked completely healthy while
the product did not work at all.

**`sync: false` is a prompt, not a default.** Render creates the service
whether or not you answer it. On the first apply, `WEB_ORIGIN`,
`ALLOWED_ORIGINS`, `VITE_API_BASE`, `ML_SERVICE_URL` and every `GITHUB_*`
credential were left unset. Each one then fell through to the value meant for
local development, and the result was four independent breaks behind four green
"Deployed" badges:

| Unset variable | Fallback | Effect in production |
|----------------|----------|----------------------|
| `VITE_API_BASE` | `/api` | Vite bakes this in at **build** time. The deployed bundle called a path that does not exist on a static site — every API call 404'd. Fixing it needs a rebuild, not a restart. |
| `WEB_ORIGIN` / `ALLOWED_ORIGINS` | `http://localhost:5173` | CORS rejected the real web origin with 403, so the browser could not reach the API even once the bundle was corrected. |
| `ML_SERVICE_URL` | `http://localhost:8000` | Unreachable from inside the container, so `MlClient` silently degraded to the heuristic on every score. The ML service was running and healthy the whole time. |
| `GITHUB_*` | empty | `/auth/github` returned its "not configured" 503. |

**Health checks did not catch any of it.** `/health/ready` verifies the process
and the database, which is the right scope for a readiness probe — it is not an
end-to-end test. Nothing in the platform's view of the system distinguishes
"running" from "working."

The lesson worth keeping: after any rollout, verify the product, not the
dashboard. Fetch the deployed bundle and confirm it contains the API origin;
send a CORS preflight from the real web origin and confirm it is allowed; and
check that a freshly scored PR reports a real `model_version` rather than
`fallback-heuristic-0.1`. All three are cheap and each one catches a failure
that "Deployed" hides.

**The ML fallback is deliberately quiet, which cuts both ways.** Degrading to
the heuristic when the model is unreachable is correct — ingestion must not
stall on ML being down. But it means a permanently misconfigured
`ML_SERVICE_URL` looks exactly like a healthy system, forever. The
`model_version` column on every score row is what makes it detectable after the
fact.

## Database provenance

**Render's managed Postgres (`meridian-db`, PostgreSQL 15) is the authoritative
production database.** Nothing else holds production data.

The connection code is deliberately provider-agnostic. `config/DatabaseUrl`
accepts `DATABASE_URL` in either the libpq URI form
(`postgres://user:pass@host:port/db`) that Render and docker-compose emit, or a
`jdbc:postgresql://…` URL, and normalises the former before the DataSource is
built. `DATABASE_SSL=true` appends `sslmode=require`. There is no
provider-specific code anywhere — pointing this service at Neon, Supabase, RDS
or a local container is a matter of changing one environment variable, and
Flyway will migrate whatever it finds.

Two version notes, both intentional:

- Production runs **Postgres 15**; `docker-compose.yml` runs **16** for local
  development. The schema uses nothing version-specific, so the split is
  harmless. It is left alone rather than aligned because changing the local
  image's major version forces Postgres to reject the existing data directory,
  which would destroy local demo data for no benefit.
- `render.yaml` records `postgresMajorVersion: 15` to match the running
  instance. Render only reads that field when *creating* a database, so editing
  it never upgrades one that already exists.

## Visual design — palette anchored on #035BD6

The v1 palette (obsidian + violet base, warm ivory panels, amber/coral
accents) was too decorative — orbs and gradient meshes clashed with the
"dense, quiet, keyboard-first" positioning we already sell in copy. The
current palette lives in the Linear / Vercel / Raycast family so the
project reads as part of a coherent design system.

Fixed constraint: primary accent **#035BD6**. Everything else derived to
harmonize with it.

| Token          | Hex                          | Purpose                            |
|----------------|------------------------------|------------------------------------|
| `bg`           | `#08090b`                    | page background                    |
| `bg2`          | `#0f1013`                    | elevated (nav active)              |
| `bg3`          | `#16181d`                    | higher elevated (modals)           |
| `panel`        | `#0d0e11`                    | default card surface               |
| `panel2`       | `#14161b`                    | hover / secondary card             |
| `line`         | `#1e2027`                    | subtle border                      |
| `line2`        | `#2a2d36`                    | stronger border                    |
| `ink` / `onbg` | `#f5f6f8`                    | primary text                       |
| `ink2`         | `#9ba1ad`                    | secondary text                     |
| `ink3`         | `#5c626e`                    | muted / meta                       |
| `accent`       | `#035BD6`                    | brand blue (the anchor)            |
| `accent2`      | `#1d6ee8`                    | hover / brighter                   |
| `accent3`      | `#0a4bab`                    | pressed / deeper                   |
| `tier.low`     | `#10b981` (text `#6ee7b7`)   | risk tier — low                    |
| `tier.medium`  | `#eab308` (text `#fcd34d`)   | risk tier — medium                 |
| `tier.high`    | `#f97316` (text `#fdba74`)   | risk tier — high                   |
| `tier.critical`| `#ef4444` (text `#fca5a5`)   | risk tier — critical               |

Rules:

- Near-black `#08090b` (not pure black, which reads OLED-y).
- Blue is used **once per view** for the primary affordance (CTA, active
  nav stripe, focus ring, key-metric hover bar).
- Tier colors form a single green → yellow → orange → red temperature
  scale so they read as a system.
- Sharp corners everywhere (`borderRadius: 0` on every token).

Motion primitives kept: `card-lift`, `row-hover`, `pulseGlow`, page
transitions, count-up numbers, animated risk dial, staggered entrances,
chart animations, skeleton shimmer.

## Dependency audit

- **`apps/api`**: `npm audit` — 0 vulnerabilities.
- **`apps/web`**: 4 known advisories (3 moderate, 1 high) across
  `react-router-dom` 6.x and `vite` 5.x. Both fixes are **major-version
  breaking bumps** the operator should own:
  - `react-router-dom` 6 → 7. Advisories are for open-redirect via
    backslash in `<Link>` and SSR-hydration constructor injection.
    Meridian is SSR-free and doesn't accept user input into `<Link to>`,
    so runtime exposure is low.
  - `vite` 5 → 6/7/8. `esbuild` dev-server request forwarding — dev-only,
    doesn't affect production builds.
- **`apps/ml`**: pip-audit not run in this environment; versions pinned in
  `requirements.txt`. Run `pip-audit -r requirements.txt` in CI.

## Not doing

- Deep learning. LightGBM is the ceiling.
- Multi-tenant SaaS billing. Single-org-per-deploy for now, but the
  schema is org-scoped so it's a small lift later.
- Kubernetes or bespoke prod-deploy tooling beyond what env vars support.
