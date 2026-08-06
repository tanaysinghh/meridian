import { Router } from 'express';
import { z } from 'zod';
import { query } from '../db/pool.js';
import { requireAuth } from '../middleware/auth.js';
import { httpError } from '../middleware/error.js';
import { validate } from '../utils/validate.js';

export const incidentRoutes = Router();
incidentRoutes.use(requireAuth);

const uuid = z.string().uuid();

const createSchema = z.object({
  title: z.string().min(1).max(500),
  severity: z.enum(['sev1', 'sev2', 'sev3', 'sev4']),
  description: z.string().max(10_000).optional().default(''),
  related_pr_id: uuid.nullable().optional().default(null),
  repo_id: uuid.nullable().optional().default(null),
  occurred_at: z.string().datetime().nullable().optional().default(null)
});

incidentRoutes.get('/', async (req, res, next) => {
  try {
    const { rows } = await query(
      `SELECT i.*, r.full_name AS repo_full_name,
              p.number AS pr_number, p.title AS pr_title
         FROM incidents i
         LEFT JOIN repos r ON r.id = i.repo_id
         LEFT JOIN pull_requests p ON p.id = i.related_pr_id
        WHERE i.org_id = $1
        ORDER BY i.occurred_at DESC`,
      [req.user.org_id]);
    res.json({ items: rows });
  } catch (err) { next(err); }
});

incidentRoutes.post('/', validate({ body: createSchema }), async (req, res, next) => {
  try {
    const { title, severity, description, related_pr_id, repo_id, occurred_at } = req.body;
    // Scope: linked PR/repo must belong to the caller's org.
    if (related_pr_id) {
      const owned = await query(
        `SELECT 1 FROM pull_requests p JOIN repos r ON r.id=p.repo_id
          WHERE p.id=$1 AND r.org_id=$2`, [related_pr_id, req.user.org_id]);
      if (!owned.rows[0]) throw httpError(400, 'invalid_related_pr');
    }
    if (repo_id) {
      const owned = await query('SELECT 1 FROM repos WHERE id=$1 AND org_id=$2', [repo_id, req.user.org_id]);
      if (!owned.rows[0]) throw httpError(400, 'invalid_repo');
    }
    const { rows } = await query(
      `INSERT INTO incidents (org_id, repo_id, related_pr_id, title, severity, description, reported_by, occurred_at)
       VALUES ($1,$2,$3,$4,$5,$6,$7, COALESCE($8, now())) RETURNING id`,
      [req.user.org_id, repo_id, related_pr_id, title, severity, description, req.user.id, occurred_at]);

    if (related_pr_id) {
      await query(
        `INSERT INTO pr_outcomes (pr_id, caused_incident, outcome_notes)
         VALUES ($1, true, $2)
         ON CONFLICT (pr_id) DO UPDATE SET
           caused_incident = true,
           outcome_notes = COALESCE(pr_outcomes.outcome_notes, '') || E'\n' || EXCLUDED.outcome_notes`,
        [related_pr_id, `Incident: ${title}`]);
    }
    res.status(201).json({ id: rows[0].id });
  } catch (err) { next(err); }
});
