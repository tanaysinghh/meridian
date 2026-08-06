# Manual setup runbook

Deeper detail on the external integrations. The high-level checklist lives in
the README under "Go-live checklist" — start there.

## Generate a JWT secret

```bash
node -e "console.log(require('crypto').randomBytes(48).toString('base64'))"
```

Put the output in `JWT_SECRET`. The API refuses to start in production if
this is unset or shorter than 32 characters.

## Bootstrap the admin user

The seed script (`npm run db:seed`) creates the org row and one admin user
from these env vars — required in production:

```
ADMIN_EMAIL=you@yourdomain.com
ADMIN_PASSWORD=<12+ chars>
ADMIN_NAME=Full Name
ADMIN_GITHUB_LOGIN=your-gh-handle    # optional
ORG_NAME=Meridian
ORG_SLUG=meridian
```

Run once against your production DB:

```bash
NODE_ENV=production npm run db:migrate
NODE_ENV=production npm run db:seed
```

The demo dataset (`npm run db:seed:demo`) refuses to load in production
unless `FORCE_DEMO_SEED=yes` — don't use it against a real deployment.

## GitHub App / webhooks

1. Create a GitHub App: https://github.com/settings/apps/new
2. Webhook URL: `https://<your-host>/webhooks/github`
3. Subscribe to: Pull request, Pull request review, Pull request review
   comment, Push, Check suite.
4. Copy App ID, generate a private key, set webhook secret.
5. Populate:
   ```
   GITHUB_APP_ID=...
   GITHUB_APP_PRIVATE_KEY_PATH=./secrets/github-app.pem
   GITHUB_WEBHOOK_SECRET=...          # REQUIRED in prod
   GITHUB_CLIENT_ID=...               # for OAuth login
   GITHUB_CLIENT_SECRET=...
   GITHUB_OAUTH_CALLBACK=https://<your-host>/auth/github/callback
   ```

Webhook signatures are enforced in production — deliveries missing or
failing HMAC verification return 401 and are not persisted.

## Slack

1. Create incoming webhook: https://api.slack.com/messaging/webhooks
2. Set `SLACK_WEBHOOK_URL=https://hooks.slack.com/services/...`

The API rejects Slack URLs that don't live under `hooks.slack.com` when
they're set from the Settings UI.

## Email (weekly digest)

Currently the only implemented provider is Resend:

```
EMAIL_PROVIDER=resend
RESEND_API_KEY=...
EMAIL_FROM=digest@yourdomain.com
```

Leave `EMAIL_PROVIDER` empty to disable email delivery (in dev this falls
back to writing HTML into `apps/api/outbox/`).

## Production DB

Point `DATABASE_URL` at managed Postgres (Neon, Supabase, RDS). Set
`DATABASE_SSL=true`. Run `NODE_ENV=production npm run db:migrate` against
it; the schema is idempotent, safe to re-run.

Never run `npm run db:reset` against production — it refuses without
`CONFIRM_RESET=yes` for exactly this reason.

## Behind a proxy / load balancer

If the API sits behind an ingress that terminates TLS:

```
TRUST_PROXY=true
WEB_ORIGIN=https://app.yourdomain.com
ALLOWED_ORIGINS=https://app.yourdomain.com
```

Trusted proxy is needed so rate limiting uses the real client IP and so
`Secure` cookies flip on when the request arrived over HTTPS.
