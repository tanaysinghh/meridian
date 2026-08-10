# Meridian

**PR risk scoring and review intelligence for engineering teams.** Ingests
GitHub webhooks, scores every pull request with an explainable model, and
surfaces the ones most likely to cause a revert, hotfix, or incident.

## What's in the box

- Ranked dashboard of open PRs with a per-PR risk tier
  (`low` / `medium` / `high` / `critical`) and the top contributing signals.
- Rules engine (per-repo, JSON-defined, Zod-validated) that can escalate
  tiers on top of the model — never de-escalate.
- Realtime updates over Socket.IO to the dashboard.
- Reviewer suggestions based on file ownership + hot-file overlap.
- Incident log with links back to the PRs that likely caused each one, to
  close the feedback loop on model training.
- Weekly digest (email via Resend, and/or Slack webhook).

## Tech stack

| service      | stack                          | port  |
|--------------|--------------------------------|-------|
| `apps/api`   | Node · Express · Postgres 16   | 4000  |
| `apps/web`   | React 18 · Vite · Tailwind     | 5173  |
| `apps/ml`    | FastAPI · LightGBM · scikit    | 8000  |

- Auth: email + password (bcrypt cost 12) with JWT in httpOnly cookies,
  plus GitHub OAuth. CSRF via double-submit tokens.
- Realtime: Socket.IO `/live` namespace, rooms per org.
- Explainability: SHAP-lite contributions returned inline with every score.
- Fallback: if the ML service is unreachable, the API degrades to a
  rule-only score so ingestion doesn't stall.

Full architectural notes live in [`DECISIONS.md`](DECISIONS.md).

## Local development

```bash
# 1. Postgres via docker-compose
docker compose up -d db

# 2. API
cd apps/api
cp .env.example .env
#   → set at minimum:
#       JWT_SECRET     (32+ random chars)
#       ADMIN_EMAIL, ADMIN_PASSWORD  (bootstrap admin, 12+ chars)
#       ALLOW_UNSIGNED_WEBHOOKS=true (only if you want to run the fixture replay)
npm install
npm run db:migrate
npm run db:seed              # creates the org + your admin user
npm run db:seed:demo         # optional — loads the Acme demo dataset
npm run dev                  # http://localhost:4000

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
in `.env`. If you ran `db:seed:demo`, generated passwords for the demo
users are printed to the seed script's stdout — capture them from that
log; they are not stored anywhere else.

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
cd apps/api && NODE_ENV=production node src/index.js
```

## Deployment

Meridian ships with a **Render Blueprint** at [`render.yaml`](render.yaml)
that provisions all four resources (Postgres + api + ml + web) in a single
apply. Step-by-step walkthrough in [`RENDER_DEPLOY.md`](RENDER_DEPLOY.md).

**Current deployment status: paused.** The Blueprint validates and the
individual services build cleanly, but rollout is on hold pending a plan
decision — Render's free tier only permits one Postgres database per
account, and this account already has one attached to another project.
Resolving is straightforward (either upgrade the DB to a paid plan, drop
the existing free DB, or apply the Blueprint from a separate Render
workspace) — see `RENDER_DEPLOY.md § 5` for the options. Nothing in the
codebase needs to change.

External hosts other than Render work fine — the API and ML services are
plain Node / Python and don't depend on Render primitives.

## Known limitations / next steps

The pieces that are stubbed out or deferred, in rough priority order:

- **GitHub App not yet registered.** OAuth login and webhook ingestion
  need a real GitHub App. Until one is created and its credentials
  (`GITHUB_CLIENT_*`, `GITHUB_APP_ID`, `GITHUB_WEBHOOK_SECRET`,
  `GITHUB_OAUTH_CALLBACK`) are set, the "Continue with GitHub" button
  returns a clean 503 and no PRs get ingested. Email + password auth
  and manual fixture replay both work without it. Runbook:
  `MANUAL_SETUP.md § GitHub App / webhooks`.
- **Render deployment gated on DB plan.** See "Deployment" above.
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

## More docs

- [`DECISIONS.md`](DECISIONS.md) — architecture decisions and rationale.
- [`RENDER_DEPLOY.md`](RENDER_DEPLOY.md) — full Render deploy walkthrough.
- [`MANUAL_SETUP.md`](MANUAL_SETUP.md) — external integration runbook
  (GitHub App, Slack, email, DB, reverse proxy).
