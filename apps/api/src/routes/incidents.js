import { Router } from 'express';
import { query } from '../db/pool.js';
import { requireAuth } from '../middleware/auth.js';

export const incidentRoutes = Router();
incidentRoutes.use(requireAuth);

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

incidentRoutes.post('/', async (req, res, next) => {
  try {
    const { title, severity, description, related_pr_id = null, repo_id = null, occurred_at } = req.body || {};
    const { rows } = await query(
      `INSERT INTO incidents (org_id, repo_id, related_pr_id, title, severity, description, reported_by, occurred_at)
       VALUES ($1,$2,$3,$4,$5,$6,$7, COALESCE($8, now())) RETURNING id`,
      [req.user.org_id, repo_id, related_pr_id, title, severity, description, req.user.id, occurred_at]);

    // Automatically mark the PR outcome as incident-causing (closes the feedback loop)
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
