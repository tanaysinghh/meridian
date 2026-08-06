# Meridian — Architecture Decisions

Living log of non-obvious choices made while building. Update as we refine.

## Monorepo layout
```
meridian/
├── apps/
│   ├── api/       Node + Express + Postgres. REST + Socket.IO. JWT auth.
│   ├── web/       React + Vite + Tailwind. Landing + dashboard.
│   └── ml/        FastAPI Python. Risk scoring microservice.
├── packages/
│   └── shared/    Shared TS types + risk-tier enums.
├── fixtures/      Mock GitHub webhook payloads — dev-only replay.
├── scripts/       Fixture replay (localhost-only, refuses in prod).
└── docker-compose.yml   Postgres for local dev.
```

Rationale: three deployable units (web, api, ml) — a monorepo keeps shared
contracts (webhook payload shapes, risk tier enums, PR schema) in one place
without publishing packages.

## Data flow
1. GitHub webhook → `api/webhooks/github` → HMAC-verifies signature (fail-closed
   in prod) → normalizes into `pull_request_events`.
2. On PR open/synchronize → api enqueues scoring → calls `ml/score` → stores
   score + explanation in `pr_risk_scores`.
3. Rules engine runs AFTER ML score → applies org overrides → tier decided by
   `max(rule_tier, model_tier)`.
4. Socket.IO broadcasts updated PR to dashboard room `org:{orgId}`.
5. Post-merge job (nightly) checks for reverts/hotfixes → writes to
   `pr_outcomes` for model validation.

## Auth
- Primary: GitHub OAuth (real flow — no dev shortcut).
- Fallback: email/password with bcrypt (cost 12) + JWT.
- Access token in httpOnly, SameSite=Lax, Secure-in-prod cookie (15m TTL).
- Refresh token in a separate httpOnly cookie (30d default), rotated on each
  refresh; old refresh tokens are deleted from `sessions` on rotation.
- Roles: `developer` / `team_lead` / `admin`. Enforced in middleware.
- CSRF: double-submit token. API sets a readable `mrd_csrf` cookie on every
  safe request; browser echoes it in `X-CSRF-Token` on mutating requests.

## Risk scoring — deliberately explainable
- Features: PR size, files touched, hot-file overlap, author revert rate,
  time-of-day, day-of-week, touches-auth flag, commit message quality,
  review comment density.
- Model: LightGBM classifier on synthetic labels initially; swap in real
  outcome data as `pr_outcomes` fills.
- Explainability: per-prediction SHAP-lite contributions in the same response.
  UI renders top 5 positive + top 3 negative contributors.
- Confidence: low if `author_pr_count < 5` or `repo_pr_count < 20`.

## Rules engine
- Stored per-repo in `repo_rules` (JSON). Predicates validated via Zod at
  write time (`path_glob`, `touches_paths`, `author_in`, `size_gt`,
  `regex_match`).
- Rules can escalate a tier and add a labelled reason. Never de-escalate.

## Realtime
- Socket.IO namespace `/live`. Rooms are per-org. Events: `pr.updated`,
  `pr.scored`, `pr.merged`, `incident.reported`.
- Handshake reuses the access-token cookie; connections without a valid
  token are rejected.

## Production hardening (post 0.1)

### What was removed
- **GitHub OAuth dev shortcut.** The `/auth/github` route no longer secretly
  logs in as `demo@meridian.dev` when `GITHUB_CLIENT_ID` is unset — it
  returns HTTP 503 with an explicit `github_oauth_not_configured` error.
- **Well-known demo password.** `demo1234` is gone. `db:seed` bootstraps only
  the admin defined in `ADMIN_EMAIL`/`ADMIN_PASSWORD`. The Acme demo dataset
  moved behind `npm run db:seed:demo` (which refuses in prod without
  `FORCE_DEMO_SEED=yes`) and generates a random password per seed run,
  printing it once to stdout.
- **Login form defaults.** The web login no longer prefills
  `demo@meridian.dev` / `demo1234`.
- **Silent webhook bypass.** `verifySignature` used to return `true` when
  `GITHUB_WEBHOOK_SECRET` was unset. Now it returns `false` unless the
  operator has explicitly set `ALLOW_UNSIGNED_WEBHOOKS=true` (dev only).
- **"Continue with GitHub (dev)" landing copy** removed; the button now
  probes the API and shows a clear "not configured" hint if OAuth isn't
  wired.

### What was hardened
- **Config validation** (`src/utils/env.js`): fails fast in production if
  `JWT_SECRET`, `DATABASE_URL`, or `GITHUB_WEBHOOK_SECRET` is missing, and
  rejects a JWT secret shorter than 32 chars.
- **Helmet** for security headers with a tight CSP (this service serves JSON;
  no HTML sources needed).
- **CORS** locked to `ALLOWED_ORIGINS` (comma-separated); wildcards refused.
- **Rate limiting** (`express-rate-limit`): 10/15min on `/auth/*`, 300/min on
  the rest. Tunable via env.
- **Zod validation** on every mutating endpoint. Rule predicates are validated
  as a discriminated union so unexpected shapes never reach the evaluator.
- **Parameterized queries everywhere** — audited; no string concatenation
  into SQL. The one dynamic filter builder in `prs.js` uses positional
  parameters.
- **Bcrypt cost** raised from 10 → 12 (tunable via `BCRYPT_COST`). Password
  minimum 12 chars enforced at seed time.
- **JWT** signed and verified with issuer/audience claims; access + refresh
  cookies are httpOnly, SameSite=Lax, and Secure in production.
- **Refresh rotation**: the refresh route deletes the old session row and
  issues a new one on every use.
- **CSRF** double-submit protection on all mutating routes.
- **Structured logging** with pino; access tokens, refresh tokens, passwords,
  hashes, and webhook signatures are redacted from log output. Prod uses
  JSON; dev uses pino-pretty.
- **Error handler** never leaks stack traces to clients in production.
  `installProcessGuards()` turns stray rejections and uncaught exceptions
  into structured logs before the process exits.
- **Graceful shutdown** on SIGTERM/SIGINT: close HTTP server then drain the
  PG pool.
- **DB pool** sized from env (`DB_POOL_MAX`, `DB_POOL_IDLE_MS`), with SSL for
  managed hosts and an on-pool-error logger. Migrations are `IF NOT EXISTS`
  everywhere (already idempotent). `--reset` refuses in prod without
  `CONFIRM_RESET=yes`.
- **Health checks**: `/health/live` and `/health/ready` on both API and ML.
  Ready checks include a DB `SELECT 1` (API) and a model load (ML).
- **Fixture replay** refuses when `NODE_ENV=production` or when `API_URL`
  isn't localhost.
- **ML service** ships with `/docs`, `/redoc`, `/openapi.json` disabled;
  CORS is default-closed (opt-in via `ALLOWED_ORIGINS`); errors never leak
  stack traces to callers.
- **Frontend** production bundle strips `console.log` / `console.debug` via
  esbuild; source maps only in dev.

### Things needing manual action (see README's "Go-live checklist")
- GitHub App + OAuth credentials
- Slack incoming webhook
- Email provider (Resend supported)
- Managed Postgres URL + SSL
- Rotate `JWT_SECRET` and set `ADMIN_PASSWORD` per environment
- Choose a host and set `WEB_ORIGIN`, `ALLOWED_ORIGINS`, `TRUST_PROXY`

### Dependency audit
- `apps/api`: `npm audit` — 0 vulnerabilities.
- `apps/web`: 2 outstanding advisories, both blocked on a **major-version
  bump** the operator should own:
  - `react-router-dom` 6.x → 7.x (open-redirect via backslash / SSR
    hydration). We're SSR-free and don't accept user input into `<Link to>`
    so exposure is low, but the fix is a v7 migration.
  - `vite` 5.x → 8.x (esbuild dev-server request forwarding — dev-only, not
    a runtime concern; production `vite build` is unaffected).
- `apps/ml`: `pip audit` not run in this env (system Python conflicts unrelated
  to Meridian). Pinned versions in `requirements.txt`; run `pip-audit` in
  your CI to confirm.

## Not doing
- Deep learning. LightGBM is the ceiling.
- Multi-tenant SaaS billing. Single-org-per-deploy for now, but schema is
  org-scoped so it's a small lift later.
- Kubernetes / prod deploy config beyond what env vars support.
