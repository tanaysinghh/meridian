import { Router } from 'express';
import { z } from 'zod';
import { query } from '../db/pool.js';
import { requireAuth, requireRole } from '../middleware/auth.js';
import { httpError } from '../middleware/error.js';
import { validate } from '../utils/validate.js';

export const rulesRoutes = Router();
rulesRoutes.use(requireAuth);

const uuid = z.string().uuid();

// Predicate schemas — one per supported rule type. Enforced up front so the
// evaluator never sees unexpected shapes.
const predicateSchema = z.discriminatedUnion('type', [
  z.object({ type: z.literal('path_glob'), glob: z.string().min(1).max(500) }),
  z.object({ type: z.literal('touches_paths'), paths: z.array(z.string().min(1).max(500)).min(1).max(50) }),
  z.object({ type: z.literal('author_in'), authors: z.array(z.string().min(1).max(200)).min(1).max(200) }),
  z.object({ type: z.literal('size_gt'), lines: z.number().int().positive().max(1_000_000) }),
  z.object({ type: z.literal('regex_match'), pattern: z.string().min(1).max(500), target: z.enum(['title', 'body', 'diff']).default('title') })
]);

const actionSchema = z.object({
  escalate_to: z.enum(['medium', 'high', 'critical']),
  reason: z.string().min(1).max(500)
});

const createSchema = z.object({
  repo_id: uuid,
  name: z.string().min(1).max(200),
  predicate: predicateSchema,
  action: actionSchema,
  enabled: z.boolean().optional().default(true)
});

const patchSchema = z.object({
  name: z.string().min(1).max(200).optional(),
  predicate: predicateSchema.optional(),
  action: actionSchema.optional(),
  enabled: z.boolean().optional()
});

rulesRoutes.get('/', async (req, res, next) => {
  try {
    const { rows } = await query(
      `SELECT rr.id, rr.name, rr.enabled, rr.predicate, rr.action, rr.created_at,
              r.full_name AS repo_full_name, r.id AS repo_id
         FROM repo_rules rr JOIN repos r ON r.id = rr.repo_id
        WHERE r.org_id = $1
        ORDER BY r.full_name, rr.name`,
      [req.user.org_id]);
    res.json({ items: rows });
  } catch (err) { next(err); }
});

rulesRoutes.post('/', requireRole('admin', 'team_lead'), validate({ body: createSchema }),
  async (req, res, next) => {
    try {
      const { repo_id, name, predicate, action, enabled } = req.body;
      const owned = await query('SELECT 1 FROM repos WHERE id=$1 AND org_id=$2', [repo_id, req.user.org_id]);
      if (!owned.rows[0]) throw httpError(404, 'repo_not_found');
      const { rows } = await query(
        `INSERT INTO repo_rules (repo_id, name, enabled, predicate, action)
         VALUES ($1,$2,$3,$4,$5) RETURNING id`,
        [repo_id, name, enabled, predicate, action]);
      res.status(201).json({ id: rows[0].id });
    } catch (err) { next(err); }
  });

rulesRoutes.patch('/:id', requireRole('admin', 'team_lead'),
  validate({ params: z.object({ id: uuid }), body: patchSchema }),
  async (req, res, next) => {
    try {
      const { name, predicate, action, enabled } = req.body;
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

rulesRoutes.delete('/:id', requireRole('admin', 'team_lead'),
  validate({ params: z.object({ id: uuid }) }),
  async (req, res, next) => {
    try {
      await query(
        `DELETE FROM repo_rules
          WHERE id = $1 AND repo_id IN (SELECT id FROM repos WHERE org_id = $2)`,
        [req.params.id, req.user.org_id]);
      res.json({ ok: true });
    } catch (err) { next(err); }
  });
