# Manual setup runbook

Deeper detail on the external integrations. The high-level go-live checklist
lives in the README; the full Render walkthrough lives in `RENDER_DEPLOY.md`.
Start with those, come here for specifics.

## JWT secret

```bash
node -e "console.log(require('crypto').randomBytes(48).toString('base64'))"
```

Put the output in `JWT_SECRET`. The API refuses to start in production if
this is unset or shorter than 32 characters. On Render this is generated
automatically (`generateValue: true` in `render.yaml`).

## Bootstrap admin user

Starting the api with `--seed` creates the org row and one admin user from
these env vars — required in production:

```
ADMIN_EMAIL=you@yourdomain.com
ADMIN_PASSWORD=<12+ chars>
ADMIN_NAME=Full Name
ADMIN_GITHUB_LOGIN=your-gh-handle    # optional
ORG_NAME=Meridian
ORG_SLUG=meridian
```

Flyway applies the schema during startup, so there is nothing to migrate by
hand. Run the bootstrap once against your production deployment:

```bash
SPRING_PROFILES_ACTIVE=prod java -jar meridian-api.jar --seed
```

It is idempotent — re-running updates the admin's password and name rather
than creating a duplicate — and refuses to run in production without both
`ADMIN_EMAIL` and `ADMIN_PASSWORD`. Clear `ADMIN_PASSWORD` from the
environment once the account exists.

The demo dataset the old Node seeder could also load was not carried over;
see `DECISIONS.md § Migration to Spring Boot`.

## GitHub App / webhooks

1. Create a GitHub App at https://github.com/settings/apps/new.
2. Webhook URL: `https://<your-api-host>/webhooks/github`.
3. Subscribe to: **Pull request**, **Pull request review**, **Pull request
   review comment**, **Push**, **Check suite**.
4. Generate a private key and set a webhook secret.
5. Populate on the API service:
   ```
   GITHUB_APP_ID=...
   GITHUB_APP_PRIVATE_KEY_PATH=./secrets/github-app.pem
   GITHUB_WEBHOOK_SECRET=...          # REQUIRED in prod
   GITHUB_CLIENT_ID=...               # for OAuth login
   GITHUB_CLIENT_SECRET=...
   GITHUB_OAUTH_CALLBACK=https://<your-api-host>/auth/github/callback
   ```

Webhook signatures are enforced in production — deliveries missing or
failing HMAC verification return 401 and are not persisted.

## ML service — shared-secret auth

The ML service (`apps/ml`) requires `ML_INTERNAL_SECRET` on every non-health
request via the `X-Internal-Secret` header. The API sends it automatically
from its own `ML_INTERNAL_SECRET` env var. Both sides **must** use the same
value.

- On Render: bound automatically. The ml service generates the secret; the
  api reads it via `fromService`.
- Off Render: set the same value on both services yourself. In production,
  the ml service refuses to boot if the secret is unset.

## Slack

1. Create an incoming webhook: https://api.slack.com/messaging/webhooks
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

Leave `EMAIL_PROVIDER` empty to disable email delivery — in dev this falls
back to writing HTML files into `apps/api/outbox/`.

## Production DB

Point `DATABASE_URL` at managed Postgres (Neon, Supabase, RDS, Render
Postgres); either the `postgres://user:pass@host:port/db` form or a
`jdbc:postgresql://…` URL works. Set `DATABASE_SSL=true`.

Flyway migrates on startup and records what it has applied in
`flyway_schema_history`, so deploys are safe to repeat. Against a database
the old Node service already provisioned, `baseline-on-migrate` adopts the
existing schema rather than trying to recreate it.

## Behind a proxy / load balancer

If the API sits behind an ingress that terminates TLS:

```
TRUST_PROXY=true
WEB_ORIGIN=https://app.yourdomain.com
ALLOWED_ORIGINS=https://app.yourdomain.com
```

Trusted-proxy mode is required so rate limiting uses the real client IP and
so `Secure` cookies flip on when the request arrived over HTTPS.
