# Meridian — Architecture Decisions

The non-obvious choices behind the code, kept as a living reference.

## Monorepo layout

```
meridian/
├── apps/
│   ├── api/       Node · Express · Postgres. REST + Socket.IO. JWT auth.
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
4. Socket.IO broadcasts the updated PR to dashboard room `org:{orgId}`.
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

- Stored per-repo in `repo_rules` (JSON). Predicates validated with Zod at
  write time as a discriminated union (`path_glob`, `touches_paths`,
  `author_in`, `size_gt`, `regex_match`) so unexpected shapes never reach
  the evaluator.
- Rules can escalate a tier and attach a labelled reason. They never
  de-escalate.

## Realtime

- Socket.IO namespace `/live`. Rooms are per-org. Events: `pr.updated`,
  `pr.scored`, `pr.merged`, `incident.reported`.
- The handshake reuses the access-token cookie; connections without a
  valid token are rejected.

## Production hardening

Everything in this section is on by default in production.

**Removed dev shortcuts.** No silent OAuth demo login, no `demo1234`
password, no login-form defaults, no webhook signature bypass. Each of
these previously returned a working shortcut; each now returns an explicit
error unless explicitly re-enabled for local dev via an env flag.

**Config validation** (`apps/api/src/utils/env.js`). Fails fast in
production if `JWT_SECRET`, `DATABASE_URL`, or `GITHUB_WEBHOOK_SECRET` is
missing; rejects a JWT secret shorter than 32 chars.

**HTTP hardening.** Helmet with a tight CSP (this service serves JSON, no
HTML sources needed); CORS locked to `ALLOWED_ORIGINS` (wildcards
refused); `express-rate-limit` at 10/15 min on `/auth/*` and 300/min on
the rest.

**Data-layer hygiene.** Zod validation on every mutating endpoint; every
DB call uses positional parameters (audited — no string concatenation
into SQL). Bcrypt cost 12 (tunable); password minimum 12 chars enforced
at seed time.

**Auth.** JWTs signed with issuer/audience claims; access + refresh
cookies are httpOnly, `SameSite=Lax`, `Secure` in production. Refresh
rotation deletes the old session row and issues a new one on every use.
CSRF double-submit on all mutating routes.

**Logging & errors.** Structured pino logs; access tokens, refresh
tokens, passwords, hashes, and webhook signatures are redacted from log
output. The error handler never leaks stack traces in production.
`installProcessGuards()` turns unhandled rejections and uncaught
exceptions into structured logs before the process exits.

**Lifecycle.** Graceful shutdown on SIGTERM/SIGINT (close HTTP → drain
PG pool). `/health/live` and `/health/ready` on both API and ML.
Migrations are `CREATE ... IF NOT EXISTS` throughout; `db:reset` refuses
to run in production without `CONFIRM_RESET=yes`.

**ML service.** `/docs`, `/redoc`, and `/openapi.json` are disabled.
Public URL is gated by a shared-secret `X-Internal-Secret` header
(constant-time compare, generic 401); refuses to boot in production if
the secret is unset. Error paths never leak stack traces to callers.

**Frontend.** Production bundle strips `console.log` and `console.debug`
via esbuild; source maps are dev-only.

**Fixture replay.** Refuses to run when `NODE_ENV=production` or when
`API_URL` isn't localhost.

## Render deploy config

The Blueprint (`render.yaml`) provisions four resources in one apply:

- **`meridian-db`** — managed Postgres 16.
- **`meridian-ml`** — public web service, gated by a shared-secret
  `X-Internal-Secret` header. Render's free tier doesn't offer private
  services (`type: pserv` needs a paid plan), so the scorer runs as a
  normal public web service and authenticates at the app layer instead.
  The header value (`ML_INTERNAL_SECRET`) is auto-generated on the ml
  service; the api reads the same value via `fromService` binding so
  they can't drift.
- **`meridian-api`** — Node web service. Runs `node src/db/migrate.js &&
  node src/index.js` on boot. Health check: `/health/ready`.
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
