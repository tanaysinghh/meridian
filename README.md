# Meridian

PR risk scoring and review intelligence for engineering teams.

## Quick start (local dev)

```bash
# 1. Postgres
docker compose up -d db

# 2. API
cd apps/api
cp .env.example .env
npm install
npm run db:migrate
npm run db:seed
npm run dev            # http://localhost:4000

# 3. ML service
cd apps/ml
python -m venv .venv && source .venv/Scripts/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000

# 4. Web
cd apps/web
npm install
npm run dev            # http://localhost:5173
```

Log in with the seeded demo account (`demo@meridian.dev` / `demo1234`) or click
"Continue with GitHub (dev)" to use the mocked OAuth flow.

## Replay mock GitHub webhooks

```bash
cd scripts
node replay-fixtures.js       # posts fixtures/*.json to /webhooks/github
```

See `DECISIONS.md` for architecture notes and `MANUAL_SETUP.md` for the
external credentials you'll need to wire up before going live.
