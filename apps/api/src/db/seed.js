import bcrypt from 'bcryptjs';
import crypto from 'node:crypto';
import { pool, tx } from './pool.js';
import { config } from '../utils/env.js';

// Two modes:
//   default        — bootstrap only: create org + one admin user from env vars.
//                    Safe to run in any environment.
//   --demo         — additionally load the sample orgs/PRs/rules/hotness that
//                    used to be the default. Refuses to run when NODE_ENV=production
//                    unless FORCE_DEMO_SEED=yes is set.
//
// Bootstrap admin credentials come from:
//   ADMIN_EMAIL, ADMIN_PASSWORD (required)         → email + password login
//   ADMIN_NAME (optional)
//   ADMIN_GITHUB_LOGIN (optional)
//   ORG_NAME, ORG_SLUG (optional; default 'Meridian' / 'meridian')

const DEMO_REPOS = [
  { full_name: 'acme/platform-api', default_branch: 'main', risk_threshold: 0.62 },
  { full_name: 'acme/billing-service', default_branch: 'main', risk_threshold: 0.55 },
  { full_name: 'acme/web-app', default_branch: 'main', risk_threshold: 0.65 },
  { full_name: 'acme/infra', default_branch: 'main', risk_threshold: 0.50 }
];

// Passwords are randomised per-seed run so we never ship a well-known password.
// The generated credentials are printed once to stdout so the operator can
// capture them; they are not persisted anywhere else.
function randomPassword() {
  return crypto.randomBytes(12).toString('base64url');
}

function buildDemoUsers() {
  const base = [
    { email: 'lead@example.test', name: 'Priya Rao', github_login: 'priya-r', role: 'team_lead' },
    { email: 'dev1@example.test', name: 'Marcus Chen', github_login: 'marcus-c', role: 'developer' },
    { email: 'dev2@example.test', name: 'Sofia Alvarez', github_login: 'sofia-a', role: 'developer' },
    { email: 'dev3@example.test', name: 'Kenji Watanabe', github_login: 'kenji-w', role: 'developer' }
  ];
  return base.map(u => ({ ...u, password: randomPassword() }));
}

const DEMO_SAMPLE_PRS = [
  { repo: 'acme/platform-api', number: 412, title: 'Refactor authentication middleware to use async JWT verification',
    author: 'marcus-c', state: 'open', additions: 340, deletions: 180, changed_files: 14, commits: 6,
    files: ['src/auth/middleware.ts', 'src/auth/jwt.ts', 'src/auth/session.ts', 'src/routes/login.ts', 'tests/auth.spec.ts'],
    labels: ['refactor', 'auth'], reviewers: ['priya-r', 'sofia-a'], hoursAgo: 6 },
  { repo: 'acme/billing-service', number: 88, title: 'Fix double-charge on retry when Stripe returns 409',
    author: 'sofia-a', state: 'open', additions: 42, deletions: 12, changed_files: 3, commits: 2,
    files: ['src/billing/charge.ts', 'src/billing/retry.ts', 'tests/charge.spec.ts'],
    labels: ['bug', 'billing'], reviewers: ['priya-r'], hoursAgo: 2 },
  { repo: 'acme/web-app', number: 1204, title: 'Add dashboard skeleton for reviewer load view',
    author: 'kenji-w', state: 'open', additions: 620, deletions: 8, changed_files: 22, commits: 11,
    files: ['src/pages/reviewers.tsx', 'src/components/LoadChart.tsx', 'src/components/ReviewerRow.tsx'],
    labels: ['feature'], reviewers: ['marcus-c'], hoursAgo: 22 },
  { repo: 'acme/infra', number: 57, title: 'Rotate production database credentials',
    author: 'priya-r', state: 'open', additions: 24, deletions: 18, changed_files: 4, commits: 1,
    files: ['terraform/secrets.tf', 'terraform/rds.tf', 'ops/rotate.sh', '.github/workflows/deploy.yml'],
    labels: ['security', 'infra'], reviewers: ['marcus-c'], hoursAgo: 1 },
  { repo: 'acme/platform-api', number: 410, title: 'chore: bump deps',
    author: 'kenji-w', state: 'merged', additions: 2200, deletions: 2100, changed_files: 58, commits: 1,
    files: ['package.json', 'package-lock.json'],
    labels: ['dependencies'], reviewers: ['priya-r'], hoursAgo: 48, mergedHoursAgo: 40 },
  { repo: 'acme/billing-service', number: 84, title: 'Improve invoice PDF rendering',
    author: 'marcus-c', state: 'merged', additions: 90, deletions: 30, changed_files: 5, commits: 3,
    files: ['src/billing/pdf.ts', 'src/billing/template.hbs', 'tests/pdf.spec.ts'],
    labels: ['enhancement'], reviewers: ['sofia-a'], hoursAgo: 96, mergedHoursAgo: 90 },
  { repo: 'acme/web-app', number: 1198, title: 'Update marketing copy on pricing page',
    author: 'sofia-a', state: 'merged', additions: 22, deletions: 20, changed_files: 1, commits: 1,
    files: ['src/pages/pricing.tsx'], labels: ['docs'], reviewers: ['kenji-w'], hoursAgo: 30, mergedHoursAgo: 28 },
  { repo: 'acme/platform-api', number: 405, title: 'Add rate limiting to /login endpoint',
    author: 'priya-r', state: 'merged', additions: 78, deletions: 4, changed_files: 6, commits: 2,
    files: ['src/routes/login.ts', 'src/middleware/ratelimit.ts', 'tests/ratelimit.spec.ts'],
    labels: ['security'], reviewers: ['marcus-c', 'sofia-a'], hoursAgo: 120, mergedHoursAgo: 110 }
];

const DEMO_RULES = [
  { repo: 'acme/platform-api', name: 'Auth files always high risk',
    predicate: { type: 'touches_paths', paths: ['src/auth/', 'src/middleware/auth'] },
    action: { escalate_to: 'high', reason: 'Touches authentication code' } },
  { repo: 'acme/billing-service', name: 'Billing code always high risk',
    predicate: { type: 'path_glob', glob: 'src/billing/**' },
    action: { escalate_to: 'high', reason: 'Touches billing pathway' } },
  { repo: 'acme/infra', name: 'Terraform + secrets escalates to critical',
    predicate: { type: 'touches_paths', paths: ['terraform/', 'secrets', '.env'] },
    action: { escalate_to: 'critical', reason: 'Infra + secrets change' } }
];

const tierFromScore = s =>
  s >= 0.85 ? 'critical' : s >= 0.65 ? 'high' : s >= 0.35 ? 'medium' : 'low';

function synthScore(pr) {
  let s = 0.15;
  s += Math.min(pr.additions / 1000, 0.35);
  s += Math.min(pr.changed_files / 40, 0.2);
  if (pr.files.some(f => /auth|secret|billing|terraform|\.env/i.test(f))) s += 0.25;
  if (pr.title.toLowerCase().startsWith('chore')) s -= 0.05;
  return Math.max(0.05, Math.min(0.98, +s.toFixed(3)));
}

async function bootstrapAdmin(client) {
  const adminEmail = (process.env.ADMIN_EMAIL || '').toLowerCase().trim();
  const adminPassword = process.env.ADMIN_PASSWORD || '';
  if (!adminEmail || !adminPassword) {
    if (config.isProd) {
      throw new Error('[seed] ADMIN_EMAIL and ADMIN_PASSWORD are required in production');
    }
    console.warn('[seed] ADMIN_EMAIL / ADMIN_PASSWORD not set — skipping admin bootstrap');
    return null;
  }
  if (adminPassword.length < 12) {
    throw new Error('[seed] ADMIN_PASSWORD must be at least 12 characters');
  }
  const orgName = process.env.ORG_NAME || 'Meridian';
  const orgSlug = process.env.ORG_SLUG || 'meridian';
  const adminName = process.env.ADMIN_NAME || 'Admin';
  const ghLogin = process.env.ADMIN_GITHUB_LOGIN || null;

  const org = (await client.query(
    `INSERT INTO orgs (name, slug) VALUES ($1,$2)
     ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name RETURNING id`,
    [orgName, orgSlug]
  )).rows[0];

  await client.query(
    `INSERT INTO org_settings (org_id) VALUES ($1) ON CONFLICT (org_id) DO NOTHING`,
    [org.id]);

  const hash = await bcrypt.hash(adminPassword, config.bcryptCost);
  await client.query(
    `INSERT INTO users (org_id, email, name, password_hash, github_login, role, avatar_url)
     VALUES ($1,$2,$3,$4,$5,'admin',NULL)
     ON CONFLICT (email) DO UPDATE SET
       name = EXCLUDED.name, role = 'admin', github_login = EXCLUDED.github_login`,
    [org.id, adminEmail, adminName, hash, ghLogin]
  );
  console.log(`[seed] admin bootstrapped: ${adminEmail} (org: ${orgSlug})`);
  return org;
}

async function seedDemo(client, bootstrappedOrg) {
  if (config.isProd && process.env.FORCE_DEMO_SEED !== 'yes') {
    throw new Error('[seed] refusing to load demo data in production; set FORCE_DEMO_SEED=yes to override');
  }
  const org = bootstrappedOrg || (await client.query(
    `INSERT INTO orgs (name, slug) VALUES ('Acme Engineering','acme')
     ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name RETURNING id`
  )).rows[0];
  await client.query(
    `INSERT INTO org_settings (org_id) VALUES ($1) ON CONFLICT (org_id) DO NOTHING`, [org.id]);

  console.log('[seed:demo] users');
  const demoUsers = buildDemoUsers();
  const userIds = {};
  const generatedPasswords = [];
  for (const u of demoUsers) {
    const hash = await bcrypt.hash(u.password, config.bcryptCost);
    const row = (await client.query(
      `INSERT INTO users (org_id, email, name, password_hash, github_login, role, avatar_url)
       VALUES ($1,$2,$3,$4,$5,$6,$7)
       ON CONFLICT (email) DO UPDATE SET
         name = EXCLUDED.name, role = EXCLUDED.role, github_login = EXCLUDED.github_login,
         password_hash = EXCLUDED.password_hash
       RETURNING id`,
      [org.id, u.email, u.name, hash, u.github_login, u.role,
       `https://api.dicebear.com/9.x/identicon/svg?seed=${u.github_login}`]
    )).rows[0];
    userIds[u.github_login] = row.id;
    generatedPasswords.push({ email: u.email, password: u.password, role: u.role });
  }

  console.log('[seed:demo] repos + rules');
  const repoIds = {};
  for (const r of DEMO_REPOS) {
    const row = (await client.query(
      `INSERT INTO repos (org_id, full_name, default_branch, risk_threshold)
       VALUES ($1,$2,$3,$4)
       ON CONFLICT (org_id, full_name) DO UPDATE SET risk_threshold = EXCLUDED.risk_threshold
       RETURNING id`,
      [org.id, r.full_name, r.default_branch, r.risk_threshold]
    )).rows[0];
    repoIds[r.full_name] = row.id;
  }
  await client.query(
    `DELETE FROM repo_rules WHERE repo_id IN (SELECT id FROM repos WHERE org_id=$1)`, [org.id]);
  for (const rule of DEMO_RULES) {
    await client.query(
      `INSERT INTO repo_rules (repo_id, name, predicate, action) VALUES ($1,$2,$3,$4)`,
      [repoIds[rule.repo], rule.name, rule.predicate, rule.action]);
  }

  console.log('[seed:demo] pull requests + scores');
  await client.query(
    `DELETE FROM pr_risk_scores WHERE pr_id IN (
       SELECT p.id FROM pull_requests p JOIN repos r ON r.id=p.repo_id WHERE r.org_id=$1)`,
    [org.id]);
  await client.query(
    `DELETE FROM pr_outcomes WHERE pr_id IN (
       SELECT p.id FROM pull_requests p JOIN repos r ON r.id=p.repo_id WHERE r.org_id=$1)`,
    [org.id]);
  await client.query(
    `DELETE FROM pr_events WHERE pr_id IN (
       SELECT p.id FROM pull_requests p JOIN repos r ON r.id=p.repo_id WHERE r.org_id=$1)`,
    [org.id]);
  await client.query(
    `DELETE FROM pull_requests WHERE repo_id IN (SELECT id FROM repos WHERE org_id=$1)`,
    [org.id]);

  for (const pr of DEMO_SAMPLE_PRS) {
    const opened = new Date(Date.now() - pr.hoursAgo * 3600_000);
    const merged = pr.mergedHoursAgo ? new Date(Date.now() - pr.mergedHoursAgo * 3600_000) : null;
    const prRow = (await client.query(
      `INSERT INTO pull_requests
        (repo_id, number, title, author_login, author_avatar, state, base_ref, head_ref,
         additions, deletions, changed_files, commits_count, file_paths, labels,
         requested_reviewers, url, opened_at, updated_at, merged_at, closed_at)
       VALUES ($1,$2,$3,$4,$5,$6,'main',$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19)
       RETURNING id`,
      [repoIds[pr.repo], pr.number, pr.title, pr.author,
       `https://api.dicebear.com/9.x/identicon/svg?seed=${pr.author}`,
       pr.state, `feat/${pr.number}`, pr.additions, pr.deletions, pr.changed_files, pr.commits,
       pr.files, pr.labels, pr.reviewers,
       `https://github.com/${pr.repo}/pull/${pr.number}`,
       opened, merged ?? opened, merged, merged]
    )).rows[0];

    const score = synthScore(pr);
    const tier = tierFromScore(score);
    const features = {
      additions: pr.additions, deletions: pr.deletions,
      changed_files: pr.changed_files, commits_count: pr.commits,
      touches_auth: pr.files.some(f => /auth/i.test(f)) ? 1 : 0,
      touches_billing: pr.files.some(f => /billing/i.test(f)) ? 1 : 0,
      touches_infra: pr.files.some(f => /terraform|infra/i.test(f)) ? 1 : 0,
      touches_secret: pr.files.some(f => /secret|\.env/i.test(f)) ? 1 : 0,
      commit_msg_quality: pr.title.length > 30 ? 0.8 : 0.4,
      author_pr_count: 20
    };
    const contributions = [
      { feature: 'changed_files', weight: +(Math.min(pr.changed_files / 40, 0.2)).toFixed(3), direction: 'up' },
      { feature: 'additions', weight: +(Math.min(pr.additions / 1000, 0.35)).toFixed(3), direction: 'up' },
      features.touches_auth && { feature: 'touches_auth', weight: 0.18, direction: 'up' },
      features.touches_billing && { feature: 'touches_billing', weight: 0.16, direction: 'up' },
      features.touches_infra && { feature: 'touches_infra', weight: 0.20, direction: 'up' },
      features.touches_secret && { feature: 'touches_secret', weight: 0.15, direction: 'up' },
      { feature: 'commit_msg_quality', weight: 0.04, direction: features.commit_msg_quality > 0.6 ? 'down' : 'up' }
    ].filter(Boolean);

    await client.query(
      `INSERT INTO pr_risk_scores
        (pr_id, score, tier, confidence, features, contributions, rule_hits, model_version)
       VALUES ($1,$2,$3,'high',$4,$5,'[]','seed-0.1.0')`,
      [prRow.id, score, tier, features, JSON.stringify(contributions)]);

    if (pr.state === 'merged' && pr.number === 410) {
      await client.query(
        `INSERT INTO pr_outcomes (pr_id, reverted, outcome_notes)
         VALUES ($1, true, 'Revert PR #411: dep upgrade broke prod checkout')`,
        [prRow.id]);
      await client.query(
        `INSERT INTO incidents (org_id, repo_id, related_pr_id, title, severity, description, occurred_at, resolved_at)
         VALUES ($1,$2,$3,$4,'sev2','Checkout 500s after dep upgrade',
           now() - interval '38 hours', now() - interval '35 hours')`,
        [org.id, repoIds[pr.repo], prRow.id, 'Checkout outage after dep bump']);
    }
  }

  console.log('[seed:demo] file hotness + ownership');
  const hot = [
    ['acme/platform-api', 'src/auth/', 22, 3],
    ['acme/billing-service', 'src/billing/', 41, 2],
    ['acme/infra', 'terraform/', 15, 1],
    ['acme/web-app', 'src/pages/', 60, 0]
  ];
  for (const [repo, p, changes, reverts] of hot) {
    await client.query(
      `INSERT INTO file_hotness (repo_id, path, change_count, revert_count, score)
       VALUES ($1,$2,$3,$4,$5)
       ON CONFLICT (repo_id, path) DO UPDATE SET
         change_count=EXCLUDED.change_count, revert_count=EXCLUDED.revert_count, score=EXCLUDED.score`,
      [repoIds[repo], p, changes, reverts, Math.min(0.99, changes / 60 + reverts * 0.1)]);
  }
  const own = [
    ['acme/platform-api', 'src/auth/', 'priya-r', 34],
    ['acme/platform-api', 'src/auth/', 'marcus-c', 12],
    ['acme/billing-service', 'src/billing/', 'sofia-a', 40],
    ['acme/billing-service', 'src/billing/', 'marcus-c', 8],
    ['acme/web-app', 'src/pages/', 'kenji-w', 55],
    ['acme/infra', 'terraform/', 'priya-r', 20]
  ];
  for (const [repo, prefix, login, commits] of own) {
    await client.query(
      `INSERT INTO file_ownership (repo_id, path_prefix, owner_login, commits)
       VALUES ($1,$2,$3,$4)
       ON CONFLICT (repo_id, path_prefix, owner_login) DO UPDATE SET commits = EXCLUDED.commits`,
      [repoIds[repo], prefix, login, commits]);
  }

  console.log('\n=== DEMO CREDENTIALS (generated this run — capture now, not stored elsewhere) ===');
  for (const c of generatedPasswords) {
    console.log(`  ${c.role.padEnd(10)}  ${c.email.padEnd(28)}  ${c.password}`);
  }
  console.log('=================================================================================\n');
}

async function main() {
  const wantDemo = process.argv.includes('--demo');
  await tx(async client => {
    const org = await bootstrapAdmin(client);
    if (wantDemo) await seedDemo(client, org);
  });
  console.log('[seed] done');
  await pool.end();
}

main().catch(err => { console.error(err); process.exit(1); });
