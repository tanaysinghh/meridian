# Meridian

PR risk scoring and review intelligence for engineering teams. Ingests GitHub
webhooks, scores every pull request with an explainable model, and surfaces
the ones most likely to cause a revert, hotfix, or incident.

Three services:

| service      | stack                        | port  |
|--------------|------------------------------|-------|
| `apps/api`   | Node · Express · Postgres    | 4000  |
| `apps/web`   | React · Vite · Tailwind      | 5173  |
| `apps/ml`    | FastAPI · LightGBM           | 8000  |

## Local development

```bash
# 1. Postgres
docker compose up -d db

# 2. API
cd apps/api
cp .env.example .env
#   → open .env and set at minimum:
#       JWT_SECRET     (32+ random chars)
#       ADMIN_EMAIL, ADMIN_PASSWORD  (bootstrap admin — 12+ char password)
#       ALLOW_UNSIGNED_WEBHOOKS=true (only if you want to run the fixture replay)
npm install
npm run db:migrate
npm run db:seed           # creates the org + your admin user
# optional — load the demo Acme dataset for exploring the UI:
npm run db:seed:demo
npm run dev               # http://localhost:4000

# 3. ML service
cd apps/ml
python -m venv .venv && source .venv/Scripts/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000

# 4. Web
cd apps/web
npm install
npm run dev               # http://localhost:5173
```

Sign in at http://localhost:5173/login with the admin credentials you set in
`.env`. If you ran `db:seed:demo`, generated passwords for the demo users are
printed to the seed script's stdout — capture them from that log; they are
not stored anywhere else.

## Replaying webhook fixtures (dev only)

```bash
# In .env: ALLOW_UNSIGNED_WEBHOOKS=true, then restart the API.
node scripts/replay-fixtures.js
```

The script refuses to run when `NODE_ENV=production` or when `API_URL`
points at a non-localhost host (unless `FORCE=yes`).

## Production build

```bash
cd apps/web && npm run build      # emits dist/, ready to serve behind a CDN
cd apps/api && NODE_ENV=production node src/index.js
```

## Go-live checklist

The pieces that need real credentials or infrastructure decisions before you
can put this in front of real users:

- [ ] **Domain + TLS.** Set `WEB_ORIGIN`, `ALLOWED_ORIGINS`, and
      `GITHUB_OAUTH_CALLBACK` to your public URLs. Enable `TRUST_PROXY=true`
      if the API sits behind a load balancer / ingress.
- [ ] **Postgres.** Provision a managed instance (Neon, Supabase, RDS, …),
      point `DATABASE_URL` at it, set `DATABASE_SSL=true`, and run
      `NODE_ENV=production npm run db:migrate`. Migrations are idempotent —
      safe to re-run.
- [ ] **Bootstrap admin.** Set `ADMIN_EMAIL` and `ADMIN_PASSWORD` (12+ chars)
      and run `npm run db:seed` once against prod. The seed script refuses to
      load demo data in prod without `FORCE_DEMO_SEED=yes`.
- [ ] **JWT secret.** Generate at least 32 random bytes:
      `node -e "console.log(require('crypto').randomBytes(48).toString('base64'))"`.
      The API refuses to start in production without one.
- [ ] **GitHub App.** Create the app, upload the webhook URL
      (`https://<host>/webhooks/github`), subscribe to *Pull request*,
      *Pull request review*, *Push*, and *Check suite* events, and populate:
      `GITHUB_APP_ID`, `GITHUB_APP_PRIVATE_KEY_PATH`,
      `GITHUB_WEBHOOK_SECRET`, `GITHUB_CLIENT_ID`, `GITHUB_CLIENT_SECRET`,
      `GITHUB_OAUTH_CALLBACK`. Webhook signatures are enforced — the API
      rejects unsigned deliveries in production.
- [ ] **Slack (optional).** Create an incoming webhook and set
      `SLACK_WEBHOOK_URL`. Per-org overrides live in `org_settings`.
- [ ] **Email digest (optional).** Currently supports Resend. Set
      `EMAIL_PROVIDER=resend`, `EMAIL_FROM`, and `RESEND_API_KEY`.
- [ ] **Rate limits.** The defaults are conservative — tune
      `RATE_LIMIT_AUTH_MAX` and `RATE_LIMIT_API_MAX` based on your traffic.
- [ ] **Hosting.** Any Node-friendly host works for API/ML (Fly, Render,
      Railway, ECS/Fargate, GKE, …). Web builds to static assets — put a CDN
      in front. The ML service is internal only; do **not** expose it to the
      public internet.
- [ ] **Observability.** Logs are structured JSON (pino) — pipe them into
      your log aggregator. Health probes: `/health/live`, `/health/ready` on
      both API and ML.

See `DECISIONS.md` for architecture notes and `MANUAL_SETUP.md` for a longer
runbook on external integrations.
