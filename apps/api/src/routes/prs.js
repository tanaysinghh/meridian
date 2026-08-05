import { Router } from 'express';
import { query } from '../db/pool.js';
import { requireAuth } from '../middleware/auth.js';
import { httpError } from '../middleware/error.js';

export const prRoutes = Router();
prRoutes.use(requireAuth);

// GET /prs?state=open&repo=acme/platform-api&tier=high&author=x&limit=50
prRoutes.get('/', async (req, res, next) => {
  try {
    const { state, repo, tier, author, limit = 100 } = req.query;
    const params = [req.user.org_id];
    const where = ['r.org_id = $1'];
    if (state) { params.push(state); where.push(`p.state = $${params.length}`); }
    if (repo) { params.push(repo); where.push(`r.full_name = $${params.length}`); }
    if (author) { params.push(author); where.push(`p.author_login = $${params.length}`); }
    if (tier) { params.push(tier); where.push(`s.tier = $${params.length}`); }
    params.push(Math.min(Number(limit) || 100, 500));

    const { rows } = await query(
      `SELECT p.id, p.number, p.title, p.author_login, p.author_avatar, p.state,
              p.additions, p.deletions, p.changed_files, p.commits_count,
              p.labels, p.requested_reviewers, p.url, p.opened_at, p.updated_at, p.merged_at,
              r.full_name AS repo_full_name,
              s.score, s.tier, s.confidence, s.rule_hits
         FROM pull_requests p
         JOIN repos r ON r.id = p.repo_id
         LEFT JOIN LATERAL (
           SELECT score, tier, confidence, rule_hits
             FROM pr_risk_scores WHERE pr_id = p.id
             ORDER BY scored_at DESC LIMIT 1
         ) s ON true
        WHERE ${where.join(' AND ')}
        ORDER BY p.updated_at DESC
        LIMIT $${params.length}`,
      params
    );
    res.json({ items: rows });
  } catch (err) { next(err); }
});

prRoutes.get('/:id', async (req, res, next) => {
  try {
    const { rows } = await query(
      `SELECT p.*, r.full_name AS repo_full_name,
              s.score, s.tier, s.confidence, s.features, s.contributions,
              s.rule_hits, s.model_version, s.scored_at,
              o.reverted, o.hotfixed, o.caused_incident, o.outcome_notes
         FROM pull_requests p
         JOIN repos r ON r.id = p.repo_id
         LEFT JOIN LATERAL (
           SELECT * FROM pr_risk_scores WHERE pr_id = p.id
             ORDER BY scored_at DESC LIMIT 1
         ) s ON true
         LEFT JOIN pr_outcomes o ON o.pr_id = p.id
        WHERE p.id = $1 AND r.org_id = $2`,
      [req.params.id, req.user.org_id]
    );
    if (!rows[0]) throw httpError(404, 'not_found');
    const events = await query(
      `SELECT event_type, actor_login, occurred_at, payload
         FROM pr_events WHERE pr_id = $1 ORDER BY occurred_at DESC LIMIT 200`,
      [req.params.id]);
    res.json({ pr: rows[0], events: events.rows });
  } catch (err) { next(err); }
});

// mark outcome (for the Incidents / feedback loop tab)
prRoutes.post('/:id/outcome', async (req, res, next) => {
  try {
    const { reverted = false, hotfixed = false, caused_incident = false, notes = null } = req.body || {};
    await query(
      `INSERT INTO pr_outcomes (pr_id, reverted, hotfixed, caused_incident, outcome_notes)
       VALUES ($1,$2,$3,$4,$5)
       ON CONFLICT (pr_id) DO UPDATE SET
         reverted=EXCLUDED.reverted, hotfixed=EXCLUDED.hotfixed,
         caused_incident=EXCLUDED.caused_incident, outcome_notes=EXCLUDED.outcome_notes,
         observed_at = now()`,
      [req.params.id, reverted, hotfixed, caused_incident, notes]
    );
    res.json({ ok: true });
  } catch (err) { next(err); }
});
