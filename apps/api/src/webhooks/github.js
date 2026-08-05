import { Router } from 'express';
import crypto from 'node:crypto';
import { query, tx } from '../db/pool.js';
import { scorePR } from '../services/mlClient.js';
import { evaluateRules } from '../rules/evaluate.js';
import { emitToOrg } from '../realtime/io.js';
import { notifySlack, riskPRBlock } from '../integrations/slack.js';

export const webhookRoutes = Router();

function verifySignature(req) {
  const secret = process.env.GITHUB_WEBHOOK_SECRET;
  // TODO(external): once secret is provisioned, remove this dev bypass
  if (!secret) return true;
  const sig = req.headers['x-hub-signature-256'];
  if (!sig) return false;
  const expected = 'sha256=' + crypto.createHmac('sha256', secret).update(req.body).digest('hex');
  try {
    return crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(expected));
  } catch { return false; }
}

// Commit message quality: crude heuristic — conventional commit prefixes,
// length, no "wip"/"fix"/"stuff" solo messages.
function commitMsgQuality(commits = []) {
  if (!commits.length) return 0.5;
  const scores = commits.map(c => {
    const msg = (c.message || '').split('\n')[0].trim();
    let s = 0.5;
    if (/^(feat|fix|chore|docs|refactor|test|perf|build|ci)(\(.+\))?:/.test(msg)) s += 0.3;
    if (msg.length >= 20) s += 0.1;
    if (/^(wip|fix|stuff|misc|.)$/i.test(msg)) s -= 0.3;
    return Math.max(0, Math.min(1, s));
  });
  return scores.reduce((a, b) => a + b, 0) / scores.length;
}

async function buildFeatures(client, pr, repoId) {
  const authorHist = await client.query(
    `SELECT COUNT(*)::int AS prs,
            COUNT(*) FILTER (WHERE o.reverted)::int AS reverts
       FROM pull_requests p LEFT JOIN pr_outcomes o ON o.pr_id=p.id
      WHERE p.author_login=$1`, [pr.author_login]);
  const hot = await client.query(
    `SELECT path, score FROM file_hotness WHERE repo_id=$1`, [repoId]);
  const hotOverlap = pr.file_paths.reduce((acc, p) => {
    const h = hot.rows.find(h => p.startsWith(h.path));
    return h ? acc + Number(h.score) : acc;
  }, 0);
  const opened = new Date(pr.opened_at);

  return {
    additions: pr.additions,
    deletions: pr.deletions,
    changed_files: pr.changed_files,
    commits_count: pr.commits_count,
    touches_auth: pr.file_paths.some(f => /auth|session|token|permission/i.test(f)) ? 1 : 0,
    touches_billing: pr.file_paths.some(f => /billing|payment|invoice/i.test(f)) ? 1 : 0,
    touches_infra: pr.file_paths.some(f => /terraform|infra|kubernetes|helm/i.test(f)) ? 1 : 0,
    touches_secret: pr.file_paths.some(f => /secret|\.env|credential/i.test(f)) ? 1 : 0,
    hot_file_overlap: +hotOverlap.toFixed(3),
    commit_msg_quality: commitMsgQuality(pr.commits),
    author_pr_count: authorHist.rows[0].prs,
    author_revert_rate: authorHist.rows[0].prs
      ? authorHist.rows[0].reverts / authorHist.rows[0].prs : 0,
    opened_hour: opened.getUTCHours(),
    opened_dow: opened.getUTCDay()
  };
}

async function upsertPR({ orgId, event, payload, action }) {
  return tx(async client => {
    // find or create repo
    const repoFull = payload.repository.full_name;
    const repoRow = (await client.query(
      `INSERT INTO repos (org_id, github_id, full_name, default_branch)
       VALUES ($1,$2,$3,$4)
       ON CONFLICT (org_id, full_name) DO UPDATE
         SET github_id = EXCLUDED.github_id
       RETURNING id`,
      [orgId, payload.repository.id, repoFull, payload.repository.default_branch || 'main']
    )).rows[0];

    const p = payload.pull_request;
    const state = p.merged ? 'merged' : p.state; // open|closed|merged
    const prRow = (await client.query(
      `INSERT INTO pull_requests
        (repo_id, number, github_id, title, body, author_login, author_avatar,
         state, draft, base_ref, head_ref,
         additions, deletions, changed_files, commits_count,
         file_paths, labels, requested_reviewers, url,
         opened_at, updated_at, merged_at, closed_at)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,$23)
       ON CONFLICT (repo_id, number) DO UPDATE SET
         title=EXCLUDED.title, body=EXCLUDED.body, state=EXCLUDED.state, draft=EXCLUDED.draft,
         additions=EXCLUDED.additions, deletions=EXCLUDED.deletions,
         changed_files=EXCLUDED.changed_files, commits_count=EXCLUDED.commits_count,
         file_paths=EXCLUDED.file_paths, labels=EXCLUDED.labels,
         requested_reviewers=EXCLUDED.requested_reviewers,
         updated_at=EXCLUDED.updated_at, merged_at=EXCLUDED.merged_at, closed_at=EXCLUDED.closed_at
       RETURNING id, number, title, author_login, additions, deletions, changed_files, url`,
      [repoRow.id, p.number, p.id, p.title, p.body, p.user.login, p.user.avatar_url,
       state, !!p.draft, p.base.ref, p.head.ref,
       p.additions ?? 0, p.deletions ?? 0, p.changed_files ?? 0, p.commits ?? 0,
       p.file_paths || [], (p.labels || []).map(l => l.name),
       (p.requested_reviewers || []).map(u => u.login), p.html_url,
       p.created_at, p.updated_at, p.merged_at, p.closed_at]
    )).rows[0];

    await client.query(
      `INSERT INTO pr_events (pr_id, event_type, actor_login, payload)
       VALUES ($1, $2, $3, $4)`,
      [prRow.id, `${event}.${action}`, payload.sender?.login || null, payload]);

    return { prRow, repoRow, prFull: {
      ...prRow,
      repo_full_name: repoFull,
      opened_at: p.created_at,
      commits: p.commits_details || [],
      file_paths: p.file_paths || []
    }};
  });
}

async function scoreAndPersist({ orgId, repoRow, prFull }) {
  const client = await (await import('../db/pool.js')).pool.connect();
  try {
    const features = await buildFeatures(client, prFull, repoRow.id);
    const ml = await scorePR(features);
    const { rows: rules } = await client.query(
      `SELECT id, name, enabled, predicate, action FROM repo_rules WHERE repo_id=$1`,
      [repoRow.id]);
    const { escalated_tier, hits } = evaluateRules({
      rules,
      pr: {
        author_login: prFull.author_login,
        file_paths: prFull.file_paths,
        additions: prFull.additions,
        deletions: prFull.deletions,
        changed_files: prFull.changed_files,
        labels: prFull.labels || []
      },
      diffText: prFull.diff_text || ''
    });
    const TIER_ORDER = ['low','medium','high','critical'];
    const finalTier = TIER_ORDER.indexOf(escalated_tier) > TIER_ORDER.indexOf(ml.tier)
      ? escalated_tier : ml.tier;
    await client.query(
      `INSERT INTO pr_risk_scores
        (pr_id, score, tier, confidence, features, contributions, rule_hits, model_version)
       VALUES ($1,$2,$3,$4,$5,$6,$7,$8)`,
      [prFull.id, ml.score, finalTier, ml.confidence,
       features, JSON.stringify(ml.contributions), JSON.stringify(hits), ml.model_version]);

    emitToOrg(orgId, 'pr.scored', {
      pr_id: prFull.id, score: ml.score, tier: finalTier, repo: prFull.repo_full_name
    });

    // Slack notify if tier is in configured set
    const { rows: settings } = await client.query(
      `SELECT slack_webhook_url, notify_on_tiers FROM org_settings WHERE org_id=$1`, [orgId]);
    if (settings[0] && (settings[0].notify_on_tiers || []).includes(finalTier)) {
      await notifySlack({
        orgSlackUrl: settings[0].slack_webhook_url,
        text: `[${finalTier.toUpperCase()}] ${prFull.repo_full_name}#${prFull.number}`,
        blocks: riskPRBlock(prFull, { tier: finalTier, score: ml.score })
      });
    }
  } finally { client.release(); }
}

async function findOrgIdForRepo(repoFullName) {
  // For now single-org — pick the first org that owns the repo (or the only org).
  const { rows } = await query(
    `SELECT r.org_id FROM repos r WHERE r.full_name=$1
      UNION SELECT id AS org_id FROM orgs LIMIT 1`, [repoFullName]);
  return rows[0]?.org_id;
}

webhookRoutes.post('/', async (req, res, next) => {
  try {
    if (!verifySignature(req)) return res.status(401).json({ error: 'bad_signature' });
    const event = req.headers['x-github-event'];
    const payload = JSON.parse(req.body.toString('utf8'));
    const action = payload.action || 'event';

    // We handle: pull_request.{opened,synchronize,edited,closed,reopened,ready_for_review}
    //            pull_request_review.submitted
    //            pull_request_review_comment.created
    //            push (for file hotness updates — not implemented in the demo)
    if (event === 'pull_request') {
      const orgId = await findOrgIdForRepo(payload.repository.full_name);
      if (!orgId) return res.status(202).json({ ignored: 'no_org' });
      const { repoRow, prFull } = await upsertPR({ orgId, event, payload, action });
      emitToOrg(orgId, 'pr.updated', { pr_id: prFull.id, action });
      if (['opened','synchronize','reopened','edited','ready_for_review'].includes(action)) {
        // fire-and-forget scoring; response is fast
        scoreAndPersist({ orgId, repoRow, prFull }).catch(e => console.error('[score] failed', e));
      }
      return res.json({ ok: true });
    }

    if (event === 'pull_request_review') {
      // record the event; timestamps are useful for cycle-time
      const orgId = await findOrgIdForRepo(payload.repository.full_name);
      const prRow = await query(
        `SELECT p.id FROM pull_requests p JOIN repos r ON r.id=p.repo_id
          WHERE r.org_id=$1 AND r.full_name=$2 AND p.number=$3`,
        [orgId, payload.repository.full_name, payload.pull_request.number]);
      if (prRow.rows[0]) {
        const prId = prRow.rows[0].id;
        await query(
          `INSERT INTO pr_events (pr_id, event_type, actor_login, payload)
           VALUES ($1, 'review.submitted', $2, $3)`,
          [prId, payload.review?.user?.login, payload]);
        // stamp first_review_at once
        await query(
          `UPDATE pull_requests SET first_review_at = COALESCE(first_review_at, now())
            WHERE id=$1`, [prId]);
        if (payload.review?.state === 'approved') {
          await query(
            `UPDATE pull_requests SET approved_at = COALESCE(approved_at, now()) WHERE id=$1`,
            [prId]);
        }
        emitToOrg(orgId, 'pr.updated', { pr_id: prId, action: 'review' });
      }
      return res.json({ ok: true });
    }

    // fallthrough — accept but log
    console.log('[webhook] unhandled event', event, action);
    res.json({ ok: true, ignored: true });
  } catch (err) { next(err); }
});
