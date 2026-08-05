import { Router } from 'express';
import { query } from '../db/pool.js';
import { requireAuth, requireRole } from '../middleware/auth.js';

export const repoRoutes = Router();
repoRoutes.use(requireAuth);

repoRoutes.get('/', async (req, res, next) => {
  try {
    const { rows } = await query(
      `SELECT r.id, r.full_name, r.default_branch, r.risk_threshold, r.connected_at,
              COUNT(p.id)::int AS open_prs,
              COUNT(p.id) FILTER (WHERE s.tier IN ('high','critical'))::int AS high_risk_prs
         FROM repos r
         LEFT JOIN pull_requests p ON p.repo_id = r.id AND p.state = 'open'
         LEFT JOIN LATERAL (
           SELECT tier FROM pr_risk_scores WHERE pr_id = p.id
             ORDER BY scored_at DESC LIMIT 1
         ) s ON true
        WHERE r.org_id = $1
        GROUP BY r.id
        ORDER BY r.full_name`,
      [req.user.org_id]);
    res.json({ items: rows });
  } catch (err) { next(err); }
});

repoRoutes.patch('/:id', requireRole('admin','team_lead'), async (req, res, next) => {
  try {
    const { risk_threshold } = req.body || {};
    await query(
      `UPDATE repos SET risk_threshold = $1
        WHERE id = $2 AND org_id = $3`,
      [risk_threshold, req.params.id, req.user.org_id]);
    res.json({ ok: true });
  } catch (err) { next(err); }
});
