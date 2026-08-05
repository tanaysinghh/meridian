import { Router } from 'express';
import { query } from '../db/pool.js';
import { requireAuth, requireRole } from '../middleware/auth.js';

export const settingsRoutes = Router();
settingsRoutes.use(requireAuth);

settingsRoutes.get('/', async (req, res, next) => {
  try {
    const { rows } = await query(
      'SELECT * FROM org_settings WHERE org_id = $1', [req.user.org_id]);
    const users = await query(
      `SELECT id, email, name, role, github_login, avatar_url
         FROM users WHERE org_id = $1 ORDER BY role, name`,
      [req.user.org_id]);
    res.json({
      settings: rows[0] || {},
      users: users.rows,
      integrations: {
        github_app_configured: !!process.env.GITHUB_APP_ID,
        slack_configured: !!(process.env.SLACK_WEBHOOK_URL || rows[0]?.slack_webhook_url),
        email_configured: !!process.env.EMAIL_PROVIDER
      }
    });
  } catch (err) { next(err); }
});

settingsRoutes.patch('/', requireRole('admin'), async (req, res, next) => {
  try {
    const { slack_webhook_url, digest_recipients, high_risk_sla_hours, auto_escalate, notify_on_tiers } = req.body || {};
    await query(
      `INSERT INTO org_settings (org_id, slack_webhook_url, digest_recipients, high_risk_sla_hours, auto_escalate, notify_on_tiers)
       VALUES ($1,$2,COALESCE($3, '{}'::text[]),$4,$5,COALESCE($6, ARRAY['high','critical']::text[]))
       ON CONFLICT (org_id) DO UPDATE SET
         slack_webhook_url = COALESCE($2, org_settings.slack_webhook_url),
         digest_recipients = COALESCE($3, org_settings.digest_recipients),
         high_risk_sla_hours = COALESCE($4, org_settings.high_risk_sla_hours),
         auto_escalate = COALESCE($5, org_settings.auto_escalate),
         notify_on_tiers = COALESCE($6, org_settings.notify_on_tiers)`,
      [req.user.org_id, slack_webhook_url, digest_recipients, high_risk_sla_hours, auto_escalate, notify_on_tiers]);
    res.json({ ok: true });
  } catch (err) { next(err); }
});

settingsRoutes.patch('/users/:id/role', requireRole('admin'), async (req, res, next) => {
  try {
    const { role } = req.body || {};
    await query(
      `UPDATE users SET role = $1 WHERE id = $2 AND org_id = $3`,
      [role, req.params.id, req.user.org_id]);
    res.json({ ok: true });
  } catch (err) { next(err); }
});
