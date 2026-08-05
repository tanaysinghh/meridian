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
├── fixtures/      Realistic mock GitHub webhook payloads for dev/demo.
├── scripts/       DB seeding, fixture replay.
└── docker-compose.yml   Postgres for local dev.
```

Rationale: three deployable units (web, api, ml) — a monorepo keeps shared
contracts (webhook payload shapes, risk tier enums, PR schema) in one place
without publishing packages.

## Data flow
1. GitHub webhook → `api/webhooks/github` → validates signature (stubbed until
   secret provisioned) → normalizes into `pull_request_events` table.
2. On PR open/synchronize → api enqueues scoring job → calls `ml/score` with
   feature vector → stores score + explanation in `pr_risk_scores`.
3. Rules engine runs AFTER ML score → applies org overrides → tier decided by
   `max(rule_tier, model_tier)`.
4. Socket.IO broadcasts updated PR to dashboard room `org:{orgId}`.
5. Post-merge job (nightly) checks for reverts/hotfixes within N days →
   writes to `pr_outcomes` for model validation.

## Auth
- Primary: GitHub OAuth (stubbed for now — needs OAuth app registration).
- Fallback: email/password with bcrypt + JWT.
- JWT in httpOnly cookie + short-lived access token; refresh token in DB.
- Role-based: `developer` / `team_lead` / `admin`. Enforced in middleware.

## Risk scoring — deliberately explainable
- Feature engineering in Python: PR size, files touched, hot-file overlap,
  author historical revert rate, time-of-day, day-of-week, touches-auth flag,
  commit message quality score, review comment density.
- Model: LightGBM classifier trained on synthetic labeled data initially;
  swap in real outcome data as `pr_outcomes` fills.
- Explainability: per-prediction SHAP-lite feature contributions returned in
  the same response. UI renders top 5 positive + top 3 negative contributors.
- Confidence: low if author_pr_count < 5 or repo_pr_count < 20 → surfaced as
  badge, not baked into the score.

## Rules engine
- Stored per-repo in `repo_rules` (JSON). Evaluated in-process (no DSL yet;
  structured predicates: `path_glob`, `author_in`, `size_gt`, etc.).
- Rule matches can escalate tier and add a labeled reason. Never de-escalate.

## Realtime
- Socket.IO namespace `/live`. Rooms are per-org. Events: `pr.updated`,
  `pr.scored`, `pr.merged`, `incident.reported`.

## Things stubbed pending external setup (search TODO(external))
- GitHub App credentials + webhook secret → signature verification is a
  no-op in dev, gated by `GITHUB_WEBHOOK_SECRET`.
- Slack webhook posting → `SLACK_WEBHOOK_URL` gate; logs the payload instead.
- Email sending (digest) → renders HTML + writes to `outbox/` in dev.
- OAuth callback → route exists, redirects to a dev-only "fake GitHub" login
  when `GITHUB_CLIENT_ID` unset.

## Not doing
- Deep learning. LightGBM is the ceiling.
- Multi-tenant SaaS billing. Single-org-per-deploy for now, but schema is
  org-scoped so it's a small lift later.
- Kubernetes / prod deploy config. Local dev via docker-compose only.
