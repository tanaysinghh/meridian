import { query } from '../db/pool.js';

// Suggest reviewers for a PR: rank by ownership overlap with touched files,
// then penalize by current open-review load to spread the work.
export async function suggestReviewers({ orgId, prId }) {
  const { rows: prRows } = await query(
    `SELECT p.id, p.file_paths, p.author_login, r.id AS repo_id
       FROM pull_requests p JOIN repos r ON r.id = p.repo_id
      WHERE p.id = $1 AND r.org_id = $2`,
    [prId, orgId]);
  const pr = prRows[0];
  if (!pr) return [];

  const { rows: owners } = await query(
    `SELECT path_prefix, owner_login, commits
       FROM file_ownership WHERE repo_id = $1`,
    [pr.repo_id]);

  const { rows: loads } = await query(
    `SELECT unnest(p.requested_reviewers) AS login, COUNT(*)::int AS open_reviews
       FROM pull_requests p JOIN repos r ON r.id = p.repo_id
      WHERE r.org_id = $1 AND p.state = 'open'
      GROUP BY login`, [orgId]);
  const loadMap = new Map(loads.map(l => [l.login, l.open_reviews]));

  const scoreByLogin = new Map();
  for (const path of pr.file_paths) {
    for (const own of owners) {
      if (path.startsWith(own.path_prefix)) {
        scoreByLogin.set(
          own.owner_login,
          (scoreByLogin.get(own.owner_login) ?? 0) + own.commits);
      }
    }
  }

  const suggestions = [...scoreByLogin.entries()]
    .filter(([login]) => login !== pr.author_login)
    .map(([login, ownershipScore]) => {
      const load = loadMap.get(login) ?? 0;
      // final: ownership signal, dampened by current load
      const finalScore = ownershipScore / (1 + load * 0.5);
      return { login, ownership_score: ownershipScore, open_reviews: load, score: +finalScore.toFixed(2) };
    })
    .sort((a, b) => b.score - a.score)
    .slice(0, 5);

  return suggestions;
}
