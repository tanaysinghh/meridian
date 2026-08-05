# Manual setup checklist

Things Claude can't do for you — grab these when you're ready to go beyond
local mocks. Each is gated behind an env var; the code path is already built.

## GitHub App / webhooks
1. Create a GitHub App: https://github.com/settings/apps/new
2. Webhook URL: `https://<your-host>/webhooks/github`
3. Subscribe to: Pull request, Pull request review, Pull request review comment,
   Push, Check suite.
4. Copy App ID, generate a private key, set webhook secret.
5. Set in `apps/api/.env`:
   ```
   GITHUB_APP_ID=...
   GITHUB_APP_PRIVATE_KEY_PATH=./secrets/github-app.pem
   GITHUB_WEBHOOK_SECRET=...
   GITHUB_CLIENT_ID=...        # for OAuth login
   GITHUB_CLIENT_SECRET=...
   ```

## Slack
1. Create incoming webhook: https://api.slack.com/messaging/webhooks
2. `SLACK_WEBHOOK_URL=https://hooks.slack.com/services/...`

## Email (weekly digest)
Any SMTP provider works. Suggested: Resend or SendGrid.
```
EMAIL_PROVIDER=resend
RESEND_API_KEY=...
EMAIL_FROM=digest@yourdomain.com
```

## Production DB
Point `DATABASE_URL` at managed Postgres (Neon, Supabase, RDS). Run
`npm run db:migrate` against it.
