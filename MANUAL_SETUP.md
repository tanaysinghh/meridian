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

### Demo dataset

`--seed-demo` (or `MERIDIAN_SEED_DEMO=true`) additionally loads the sample
Acme dataset the old Node `db:seed:demo` script produced — four repos, eight
pull requests with scores and factors, three rules, file hotness and
ownership, and one sev2 incident linked to a PR. It attaches to whatever org
`--seed` just bootstrapped, and clears its own rows first, so re-running
replaces rather than duplicates.

Under the `prod` profile it refuses to run unless `FORCE_DEMO_SEED=yes` is
also set — demo data in a real tenant's database is not a mistake you want
to make by leaving an env var behind. Unset both once the data has landed.

The demo *users* it creates (Priya, Marcus, Dana, Sam) have no password
hash, so they cannot sign in; they exist to make authorship, review load and
ownership look real. Sign in as the `ADMIN_EMAIL` account above.

## GitHub App / webhooks

The app backing the live deployment is `meridian-tanaysinghh` (App ID
`5059654`, Client ID `Iv23licnOWMduaim78gC`). It is **not installed on any
repository**, so no webhook deliveries actually arrive in production — OAuth
sign-in is the only part of it exercised today. To create your own:

1. Create a GitHub App at https://github.com/settings/apps/new.
2. Webhook URL: `https://<your-api-host>/webhooks/github`
   (live: `https://meridian-api-il0f.onrender.com/webhooks/github`).
3. Callback URL: `https://<your-api-host>/auth/github/callback` — must match
   `GITHUB_OAUTH_CALLBACK` exactly
   (live: `https://meridian-api-il0f.onrender.com/auth/github/callback`).
4. Subscribe to exactly two events: **Pull request** and **Pull request
   review**. `GithubWebhookController` handles only these; every other event
   is acknowledged and dropped.
5. Permissions — the minimum the code uses, and no more:
   - Repository → **Metadata**: Read-only (mandatory, auto-selected)
   - Repository → **Pull requests**: Read-only
   - Account → **Email addresses**: Read-only

   Note the last one. `GithubOAuthService` requests the classic OAuth scopes
   `read:user user:email` on the authorize URL, but **GitHub Apps ignore the
   `scope` parameter** and use their configured permissions instead. Access to
   `/user/emails` therefore depends entirely on this permission; without it,
   sign-in fails with `github_no_verified_email`.
6. Set a webhook secret, then populate on the API service:
   ```
   GITHUB_APP_ID=...
   GITHUB_WEBHOOK_SECRET=...          # REQUIRED in prod
   GITHUB_CLIENT_ID=...               # for OAuth login
   GITHUB_CLIENT_SECRET=...
   GITHUB_OAUTH_CALLBACK=https://<your-api-host>/auth/github/callback
   ```
   Receiving real deliveries also needs the app **installed** on a
   repository (app settings → Install App). Creating the app alone is
   enough for OAuth sign-in but not for ingestion.
   A GitHub App private key is only needed for server-to-server (installation
   token) calls, which this service does not make — it uses the webhook
   signature for ingestion and a user-to-server token for OAuth. There is no
   `GITHUB_APP_PRIVATE_KEY_PATH` setting.

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

## Split-origin deployments

If the SPA and the API answer on two different *sites* — not just two
origins — the auth and CSRF cookies become third-party cookies and browsers
drop them. `app.co` / `api.app.co` are fine; `meridian-web-x.onrender.com`
and `meridian-api-y.onrender.com` are not, because `onrender.com` is on the
Public Suffix List and each subdomain is therefore its own site.

The `prod` profile already handles this: `application-prod.yml` pins
`cookie-secure: true` and defaults `cookie-same-site` to `None`, which is
what the `.onrender.com` deployment needs. `COOKIE_SAME_SITE` is the
override, and the only reason to set it is to tighten back to `Lax` once
both services sit under one apex on a custom domain:

```
COOKIE_SAME_SITE=Lax      # only when web and api share a site
```

The client half matters too: the CSRF token is published in an
`X-CSRF-Token` response header (and exposed via
`Access-Control-Expose-Headers`) precisely because the SPA cannot read the
API's cookie across sites. Both halves are needed; either alone leaves
login returning 403 or every authenticated call returning 401. Reasoning
and the tradeoff are in `DECISIONS.md § Split-origin cookies`.

## Behind a proxy / load balancer

If the API sits behind an ingress that terminates TLS:

```
TRUST_PROXY=true
WEB_ORIGIN=https://app.yourdomain.com
ALLOWED_ORIGINS=https://app.yourdomain.com
```

Trusted-proxy mode is required so rate limiting uses the real client IP and
so `Secure` cookies flip on when the request arrived over HTTPS.
