import { Router } from 'express';
import { query } from '../db/pool.js';
import { requireAuth, requireRole } from '../middleware/auth.js';
import { httpError } from '../middleware/error.js';

export const rulesRoutes = Router();
rulesRoutes.use(requireAuth);

rulesRoutes.get('/', async (req, res, next) => {
  try {
    const { rows } = await query(
      `SELECT rr.id, rr.name, rr.enabled, rr.predicate, rr.action, rr.created_at,
              r.full_name AS repo_full_name, r.id AS repo_id
         FROM repo_rules rr
         JOIN repos r ON r.id = rr.repo_id
        WHERE r.org_id = $1
        ORDER BY r.full_name, rr.name`,
      [req.user.org_id]);
    res.json({ items: rows });
  } catch (err) { next(err); }
});

rulesRoutes.post('/', requireRole('admin','team_lead'), async (req, res, next) => {
  try {
    const { repo_id, name, predicate, action, enabled = true } = req.body || {};
    if (!repo_id || !name || !predicate || !action) throw httpError(400, 'missing_fields');
    // ownership check
    const owned = await query('SELECT 1 FROM repos WHERE id=$1 AND org_id=$2', [repo_id, req.user.org_id]);
    if (!owned.rows[0]) throw httpError(404, 'repo_not_found');
    const { rows } = await query(
      `INSERT INTO repo_rules (repo_id, name, enabled, predicate, action)
       VALUES ($1,$2,$3,$4,$5) RETURNING id`,
      [repo_id, name, enabled, predicate, action]);
    res.status(201).json({ id: rows[0].id });
  } catch (err) { next(err); }
});

rulesRoutes.patch('/:id', requireRole('admin','team_lead'), async (req, res, next) => {
  try {
    const { name, predicate, action, enabled } = req.body || {};
    await query(
      `UPDATE repo_rules SET
         name = COALESCE($1, name),
         predicate = COALESCE($2, predicate),
         action = COALESCE($3, action),
         enabled = COALESCE($4, enabled)
       WHERE id = $5
         AND repo_id IN (SELECT id FROM repos WHERE org_id = $6)`,
      [name, predicate, action, enabled, req.params.id, req.user.org_id]);
    res.json({ ok: true });
  } catch (err) { next(err); }
});

rulesRoutes.delete('/:id', requireRole('admin','team_lead'), async (req, res, next) => {
  try {
    await query(
      `DELETE FROM repo_rules
        WHERE id = $1 AND repo_id IN (SELECT id FROM repos WHERE org_id = $2)`,
      [req.params.id, req.user.org_id]);
    res.json({ ok: true });
  } catch (err) { next(err); }
});
