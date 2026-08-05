import { Router } from 'express';
import { query } from '../db/pool.js';
import { requireAuth } from '../middleware/auth.js';

export const analyticsRoutes = Router();
analyticsRoutes.use(requireAuth);

// Overview: high-level tiles for the Overview tab.
analyticsRoutes.get('/overview', async (req, res, next) => {
  try {
    const orgId = req.user.org_id;
    const [tiers, throughput, sla, recent] = await Promise.all([
      query(
        `SELECT COALESCE(s.tier, 'low') AS tier, COUNT(*)::int AS n
           FROM pull_requests p
           JOIN repos r ON r.id = p.repo_id
           LEFT JOIN LATERAL (
             SELECT tier FROM pr_risk_scores WHERE pr_id = p.id
               ORDER BY scored_at DESC LIMIT 1
           ) s ON true
          WHERE r.org_id = $1 AND p.state = 'open'
          GROUP BY 1`, [orgId]),
      query(
        `SELECT date_trunc('day', p.merged_at) AS day, COUNT(*)::int AS merged
           FROM pull_requests p
           JOIN repos r ON r.id = p.repo_id
          WHERE r.org_id = $1 AND p.merged_at IS NOT NULL
            AND p.merged_at > now() - interval '30 days'
          GROUP BY 1 ORDER BY 1`, [orgId]),
      query(
        `SELECT COUNT(*)::int AS breached
           FROM pull_requests p
           JOIN repos r ON r.id = p.repo_id
           JOIN LATERAL (
             SELECT tier FROM pr_risk_scores WHERE pr_id = p.id
               ORDER BY scored_at DESC LIMIT 1
           ) s ON true
           JOIN org_settings os ON os.org_id = r.org_id
          WHERE r.org_id = $1 AND p.state = 'open'
            AND s.tier IN ('high','critical')
            AND p.opened_at < now() - (os.high_risk_sla_hours || ' hours')::interval`,
        [orgId]),
      query(
        `SELECT p.id, p.number, p.title, p.author_login, p.updated_at,
                r.full_name AS repo_full_name, s.tier, s.score
           FROM pull_requests p
           JOIN repos r ON r.id = p.repo_id
           LEFT JOIN LATERAL (
             SELECT tier, score FROM pr_risk_scores WHERE pr_id = p.id
               ORDER BY scored_at DESC LIMIT 1
           ) s ON true
          WHERE r.org_id = $1
          ORDER BY p.updated_at DESC LIMIT 8`, [orgId])
    ]);

    res.json({
      tiers: tiers.rows,
      throughput_30d: throughput.rows,
      sla_breaches: sla.rows[0]?.breached ?? 0,
      recent: recent.rows
    });
  } catch (err) { next(err); }
});

// PR size trend
analyticsRoutes.get('/pr-size-trend', async (req, res, next) => {
  try {
    const { rows } = await query(
      `SELECT date_trunc('week', p.opened_at) AS week,
              AVG(p.additions + p.deletions)::int AS avg_size,
              PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY p.additions + p.deletions)::int AS median_size,
              COUNT(*)::int AS prs
         FROM pull_requests p
         JOIN repos r ON r.id = p.repo_id
        WHERE r.org_id = $1 AND p.opened_at > now() - interval '12 weeks'
        GROUP BY 1 ORDER BY 1`,
      [req.user.org_id]);
    res.json({ series: rows });
  } catch (err) { next(err); }
});

// Cycle time breakdown (open → first_review → approved → merged)
analyticsRoutes.get('/cycle-time', async (req, res, next) => {
  try {
    const { rows } = await query(
      `SELECT
         AVG(EXTRACT(EPOCH FROM (first_review_at - opened_at)) / 3600)::float AS hours_to_first_review,
         AVG(EXTRACT(EPOCH FROM (approved_at - first_review_at)) / 3600)::float AS hours_first_review_to_approve,
         AVG(EXTRACT(EPOCH FROM (merged_at - approved_at)) / 3600)::float AS hours_approve_to_merge
       FROM pull_requests p
       JOIN repos r ON r.id = p.repo_id
      WHERE r.org_id = $1 AND merged_at IS NOT NULL
        AND merged_at > now() - interval '30 days'`,
      [req.user.org_id]);
    res.json({ breakdown: rows[0] || {} });
  } catch (err) { next(err); }
});

// Merge time heatmap (are risky PRs merged Friday 11pm?)
analyticsRoutes.get('/merge-heatmap', async (req, res, next) => {
  try {
    const { rows } = await query(
      `SELECT EXTRACT(ISODOW FROM merged_at)::int AS dow,
              EXTRACT(HOUR FROM merged_at)::int AS hour,
              COUNT(*)::int AS merges,
              AVG(s.score)::float AS avg_risk
         FROM pull_requests p
         JOIN repos r ON r.id = p.repo_id
         LEFT JOIN LATERAL (
           SELECT score FROM pr_risk_scores WHERE pr_id = p.id
             ORDER BY scored_at DESC LIMIT 1
         ) s ON true
        WHERE r.org_id = $1 AND merged_at IS NOT NULL
          AND merged_at > now() - interval '90 days'
        GROUP BY 1,2`,
      [req.user.org_id]);
    res.json({ cells: rows });
  } catch (err) { next(err); }
});

// Revert rate over time
analyticsRoutes.get('/revert-rate', async (req, res, next) => {
  try {
    const { rows } = await query(
      `SELECT date_trunc('week', p.merged_at) AS week,
              COUNT(*)::int AS merged,
              SUM(CASE WHEN o.reverted THEN 1 ELSE 0 END)::int AS reverted
         FROM pull_requests p
         JOIN repos r ON r.id = p.repo_id
         LEFT JOIN pr_outcomes o ON o.pr_id = p.id
        WHERE r.org_id = $1 AND p.merged_at IS NOT NULL
          AND p.merged_at > now() - interval '12 weeks'
        GROUP BY 1 ORDER BY 1`,
      [req.user.org_id]);
    res.json({ series: rows });
  } catch (err) { next(err); }
});

// Author risk trend (constructive framing on frontend)
analyticsRoutes.get('/author-trends', async (req, res, next) => {
  try {
    const { rows } = await query(
      `SELECT p.author_login,
              COUNT(*)::int AS prs,
              AVG(s.score)::float AS avg_risk,
              SUM(CASE WHEN o.reverted THEN 1 ELSE 0 END)::int AS reverted
         FROM pull_requests p
         JOIN repos r ON r.id = p.repo_id
         LEFT JOIN LATERAL (
           SELECT score FROM pr_risk_scores WHERE pr_id = p.id
             ORDER BY scored_at DESC LIMIT 1
         ) s ON true
         LEFT JOIN pr_outcomes o ON o.pr_id = p.id
        WHERE r.org_id = $1 AND p.opened_at > now() - interval '90 days'
        GROUP BY p.author_login
        ORDER BY prs DESC`,
      [req.user.org_id]);
    res.json({ authors: rows });
  } catch (err) { next(err); }
});
