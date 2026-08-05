import { Router } from 'express';
import { query } from '../db/pool.js';
import { requireAuth } from '../middleware/auth.js';
import { suggestReviewers } from '../services/reviewerSuggest.js';

export const reviewerRoutes = Router();
reviewerRoutes.use(requireAuth);

// Reviewer load view — current open review queue per reviewer.
reviewerRoutes.get('/', async (req, res, next) => {
  try {
    const { rows: users } = await query(
      `SELECT id, github_login, name, avatar_url, role
         FROM users WHERE org_id = $1 AND github_login IS NOT NULL`,
      [req.user.org_id]);

    // count of open PRs where each user is a requested reviewer
    const { rows: loads } = await query(
      `SELECT unnest(p.requested_reviewers) AS login, COUNT(*)::int AS open_reviews,
              AVG(s.score)::float AS avg_risk
         FROM pull_requests p
         JOIN repos r ON r.id = p.repo_id
         LEFT JOIN LATERAL (
           SELECT score FROM pr_risk_scores WHERE pr_id = p.id
             ORDER BY scored_at DESC LIMIT 1
         ) s ON true
        WHERE r.org_id = $1 AND p.state = 'open'
        GROUP BY login`,
      [req.user.org_id]);

    const loadMap = new Map(loads.map(l => [l.login, l]));
    const rows = users.map(u => {
      const l = loadMap.get(u.github_login);
      return {
        ...u,
        open_reviews: l?.open_reviews ?? 0,
        avg_risk: l?.avg_risk ?? 0
      };
    }).sort((a, b) => b.open_reviews - a.open_reviews);

    res.json({ items: rows });
  } catch (err) { next(err); }
});

// Suggest reviewers for a PR (used from PR detail view)
reviewerRoutes.get('/suggest', async (req, res, next) => {
  try {
    const { pr_id } = req.query;
    const suggestions = await suggestReviewers({ orgId: req.user.org_id, prId: pr_id });
    res.json({ suggestions });
  } catch (err) { next(err); }
});
