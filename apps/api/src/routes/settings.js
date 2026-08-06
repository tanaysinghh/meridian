import { Router } from 'express';
import { z } from 'zod';
import { query } from '../db/pool.js';
import { requireAuth, requireRole } from '../middleware/auth.js';
import { validate } from '../utils/validate.js';
import { config } from '../utils/env.js';

export const settingsRoutes = Router();
settingsRoutes.use(requireAuth);

const uuid = z.string().uuid();

const updateSchema = z.object({
  // Slack incoming-webhook URLs live under hooks.slack.com; reject anything else
  // so an operator can't accidentally point notifications at an attacker host.
  slack_webhook_url: z.string()
    .url()
    .max(500)
    .refine(v => v.startsWith('https://hooks.slack.com/'), 'must be a hooks.slack.com URL')
    .nullable()
    .optional(),
  digest_recipients: z.array(z.string().email().max(320)).max(50).optional(),
  high_risk_sla_hours: z.number().int().positive().max(24 * 30).optional(),
  auto_escalate: z.boolean().optional(),
  notify_on_tiers: z.array(z.enum(['low', 'medium', 'high', 'critical'])).max(4).optional()
});

const roleSchema = z.object({ role: z.enum(['developer', 'team_lead', 'admin']) });

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
        github_app_configured: !!config.github.appId,
        github_oauth_configured: !!(config.github.clientId && config.github.clientSecret),
        webhook_secret_configured: !!config.github.webhookSecret,
        slack_configured: !!(config.slack.webhookUrl || rows[0]?.slack_webhook_url),
        email_configured: !!config.email.provider
      }
    });
  } catch (err) { next(err); }
});

settingsRoutes.patch('/', requireRole('admin'), validate({ body: updateSchema }),
  async (req, res, next) => {
    try {
      const { slack_webhook_url, digest_recipients, high_risk_sla_hours, auto_escalate, notify_on_tiers } = req.body;
      await query(
        `INSERT INTO org_settings (org_id, slack_webhook_url, digest_recipients, high_risk_sla_hours, auto_escalate, notify_on_tiers)
         VALUES ($1,$2,COALESCE($3, '{}'::text[]),COALESCE($4,8),COALESCE($5,true),COALESCE($6, ARRAY['high','critical']::text[]))
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

settingsRoutes.patch('/users/:id/role', requireRole('admin'),
  validate({ params: z.object({ id: uuid }), body: roleSchema }),
  async (req, res, next) => {
    try {
      await query(
        `UPDATE users SET role = $1 WHERE id = $2 AND org_id = $3`,
        [req.body.role, req.params.id, req.user.org_id]);
      res.json({ ok: true });
    } catch (err) { next(err); }
  });
