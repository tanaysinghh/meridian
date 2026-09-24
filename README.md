# Meridian

**PR risk scoring and review intelligence for engineering teams.** Ingests
GitHub webhooks, scores every pull request with an explainable model, and
surfaces the ones most likely to cause a revert, hotfix, or incident.

## What's in the box

- Ranked dashboard of open PRs with a per-PR risk tier
  (`low` / `medium` / `high` / `critical`) and the top contributing signals.
- Rules engine (per-repo, JSON-defined, validated on write) that can escalate
  tiers on top of the model — never de-escalate.
- Realtime updates pushed to the dashboard over STOMP/WebSocket.
- Reviewer suggestions based on file ownership + hot-file overlap.
- Incident log with links back to the PRs that likely caused each one, to
  close the feedback loop on model training.
- Weekly digest (email via Resend, and/or Slack webhook).

## Tech stack

| service      | stack                                        | port  |
|--------------|----------------------------------------------|-------|
| `apps/api`   | Java 21 · Spring Boot 4 · Postgres            | 4000  |
| `apps/web`   | React 18 · Vite · Tailwind                    | 5173  |
| `apps/ml`    | FastAPI · LightGBM · scikit                   | 8000  |

`apps/api` in detail: Spring MVC for the REST layer, Spring Data JPA +
Hibernate over Postgres, Flyway for schema migrations, and Spring Security
for auth and the security headers. Built and deployed as a Docker image.

The database connection is provider-agnostic — production runs on Render's
managed Postgres 15, local development on the Postgres 16 container in
`docker-compose.yml`. See `DECISIONS.md § Database provenance`.

- Auth: email + password (bcrypt cost 12) with JWT in httpOnly cookies,
  plus GitHub OAuth. CSRF via double-submit tokens.
- Realtime: STOMP over WebSocket at `/live`, one topic per org
  (`/topic/org.{orgId}`).
- Explainability: SHAP-lite contributions returned inline with every score.
- Fallback: if the ML service is unreachable, the API degrades to a
  rule-only score so ingestion doesn't stall.

Full architectural notes live in [`DECISIONS.md`](DECISIONS.md).

## Local development

```bash
# 1. Postgres via docker-compose
docker compose up -d db

# 2. API  (needs JDK 21 — `java -version` should report 21.x)
cd apps/api
cp .env.example .env
#   → set at minimum:
#       JWT_SECRET     (32+ random chars)
#       ADMIN_EMAIL, ADMIN_PASSWORD  (bootstrap admin, 12+ chars)
#       ALLOW_UNSIGNED_WEBHOOKS=true (only if you want to run the fixture replay)

# Spring reads the process environment, not .env, so export it first.
# bash/zsh:
set -a && . ./.env && set +a
# PowerShell:
#   Get-Content .env | Where-Object {$_ -match '^\s*[^#]\w*='} |
#     ForEach-Object { $k,$v = $_ -split '=',2; [Environment]::SetEnvironmentVariable($k,$v) }

# Flyway applies the schema automatically on startup — no separate migrate step.
mvn spring-boot:run                       # http://localhost:4000

# One-off, to create the first org + admin user:
mvn spring-boot:run -Dspring-boot.run.arguments=--seed

# 3. ML service
cd apps/ml
python -m venv .venv && source .venv/Scripts/activate    # Windows: .venv\Scripts\activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000

# 4. Web
cd apps/web
npm install
npm run dev                  # http://localhost:5173
```

Sign in at http://localhost:5173/login with the admin credentials you set
in `.env` and bootstrapped with `--seed`.

### Running the API's tests

```bash
cd apps/api && mvn test
```

No database or Docker required — the suite is unit tests plus MockMvc
slices covering auth, the PR risk-scoring endpoints, webhook signature
verification, the rules engine, and the ML fallback scorer.

### Building the API image

```bash
cd apps/api && docker build -t meridian-api .
```

This is the same Dockerfile Render builds from.

### Replaying webhook fixtures (dev only)

```bash
# In apps/api/.env: ALLOW_UNSIGNED_WEBHOOKS=true, then restart the API.
node scripts/replay-fixtures.js
```

The script refuses to run when `NODE_ENV=production` or when `API_URL`
points at a non-localhost host (unless `FORCE=yes`).

## Production build

```bash
cd apps/web && npm run build          # emits dist/, ready to serve behind a CDN

cd apps/api && mvn clean package      # emits target/meridian-api-<version>.jar
SPRING_PROFILES_ACTIVE=prod java -jar target/meridian-api-0.1.0.jar
```

## Deployment

Meridian ships with a **Render Blueprint** at [`render.yaml`](render.yaml)
that provisions all four resources (Postgres + api + ml + web) in a single
apply. Step-by-step walkthrough in [`RENDER_DEPLOY.md`](RENDER_DEPLOY.md).

**Current deployment status: live.**

| service | URL |
|---------|-----|
| `meridian-web` | https://meridian-web-1pi4.onrender.com |
| `meridian-api` | https://meridian-api-il0f.onrender.com |
| `meridian-ml`  | https://meridian-ml.onrender.com |
| `meridian-db`  | Render managed Postgres 15 (private) |

All four run on Render's free tier, which **spins services down after
inactivity** — the first request after an idle period can take 50–110
seconds while the container wakes. That is the plan, not a fault. Upgrade
`meridian-api` to Starter to remove it.

> **If you re-apply the Blueprint, fill in every `sync: false` variable.**
> Those are prompts, not defaults: Render creates the service without them
> and the app silently falls back to its local-development values. On the
> first rollout `WEB_ORIGIN`, `ALLOWED_ORIGINS`, `VITE_API_BASE`,
> `ML_SERVICE_URL` and the `GITHUB_*` credentials were all left unset, which
> left every service reporting "Deployed" while the product did not work at
> all: the frontend called a `/api` path that does not exist, CORS rejected
> the real web origin, and every PR was scored by the fallback heuristic
> instead of the model. See `DECISIONS.md § Deployment pitfalls`.

External hosts other than Render work fine — the API and ML services are
a plain Docker image / Python app and don't depend on Render primitives.

## Known limitations / next steps

The pieces that are stubbed out or deferred, in rough priority order:

- **GitHub App is registered and OAuth login works.** The app
  (`meridian-tanaysinghh`, App ID 5059654) is live against the production
  URLs, with three permissions — Repository Metadata and Pull requests
  read-only, Account Email addresses read-only — and two webhook events,
  Pull request and Pull request review. Runbook:
  `MANUAL_SETUP.md § GitHub App / webhooks`.
- **The app is not yet installed on a repository**, so no live PRs are
  being ingested. Registering the app and installing it are separate
  steps: until it is installed somewhere, GitHub has nothing to send
  webhooks about. Installing requires generating a private key, which the
  codebase itself never uses — it authenticates webhooks by HMAC and OAuth
  by user-to-server token.
- **Slack and email digest are wired but not configured.** Set
  `SLACK_WEBHOOK_URL` and/or `EMAIL_PROVIDER=resend` +
  `RESEND_API_KEY` + `EMAIL_FROM` on the api service to turn them on.
  Without those, digests are computed but silently dropped in prod (dev
  writes email HTML to `apps/api/outbox/`).
- **Dependency majors deferred.** `react-router-dom` v6 → v7 and `vite`
  v5 → v6/7/8 are documented in `DECISIONS.md § Dependency audit` —
  both are breaking bumps with negligible runtime exposure in this
  codebase.
- **ML model trained on synthetic labels.** Once real `pr_outcomes` data
  accumulates (the nightly post-merge job populates it), retrain on
  actual reverts/hotfixes rather than the seed distribution.
- **Real webhooks won't populate the scoring inputs.** Ingestion reads
  `file_paths`, `commits_details` and `diff_text` off the pull request
  payload, and none of those are real GitHub webhook fields — they were
  invented for the fixture replay. Against a live GitHub App you get the
  addition/deletion/file counts but an empty file list, so the path-based
  rules, the `touches_*` features, the built-in sensitive-path escalation
  and the secret scanner all sit inert. Pre-existing (the Node service read
  the same non-existent fields); closing it means an extra
  `GET /repos/{owner}/{repo}/pulls/{n}/files` during ingestion, which needs
  no permission beyond the Pull requests read already granted.
- **Demo dataset seeding not ported.** The old `npm run db:seed:demo`
  loaded a sample org with PRs, rules and hotness data. The admin
  bootstrap it also did *was* ported (`--seed`); the demo fixtures were
  not. Use `scripts/replay-fixtures.js` to populate PR data locally.
  See `DECISIONS.md § Migration to Spring Boot`.

## More docs

- [`DECISIONS.md`](DECISIONS.md) — architecture decisions and rationale.
- [`RENDER_DEPLOY.md`](RENDER_DEPLOY.md) — full Render deploy walkthrough.
- [`MANUAL_SETUP.md`](MANUAL_SETUP.md) — external integration runbook
  (GitHub App, Slack, email, DB, reverse proxy).
