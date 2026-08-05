// Predicate-based rules engine. Never de-escalates a tier; only escalates.

const TIER_ORDER = ['low', 'medium', 'high', 'critical'];
const tierIdx = t => TIER_ORDER.indexOf(t);

function globMatch(glob, path) {
  // minimal glob: ** wildcard, * segment wildcard
  const re = new RegExp(
    '^' + glob
      .replace(/[.+^${}()|[\]\\]/g, '\\$&')
      .replace(/\*\*/g, '.*')
      .replace(/\*/g, '[^/]*') + '$');
  return re.test(path);
}

function predicateMatches(pred, ctx) {
  switch (pred.type) {
    case 'path_glob':
      return ctx.file_paths.some(p => globMatch(pred.glob, p));
    case 'touches_paths':
      return ctx.file_paths.some(p => pred.paths.some(prefix => p.includes(prefix)));
    case 'author_in':
      return pred.authors.includes(ctx.author_login);
    case 'size_gt':
      return (ctx.additions + ctx.deletions) > pred.value;
    case 'files_gt':
      return ctx.changed_files > pred.value;
    case 'regex_match':
      return ctx.file_paths.some(p => new RegExp(pred.pattern).test(p));
    case 'label_in':
      return (ctx.labels || []).some(l => pred.labels.includes(l));
    default:
      return false;
  }
}

// Also: hard-coded security regexes we always want to flag even without an org rule
const SECRET_PATTERNS = [
  { name: 'aws_access_key', re: /AKIA[0-9A-Z]{16}/ },
  { name: 'private_key', re: /-----BEGIN (RSA|EC|OPENSSH|PGP) PRIVATE KEY-----/ },
  { name: 'generic_secret', re: /(api[_-]?key|secret|token)['"\s:=]+[A-Za-z0-9_\-]{20,}/i }
];

const SECURITY_PATH_PATTERNS = [
  /auth/i, /session/i, /token/i, /permission/i, /\.env/i, /secret/i, /credential/i
];

export function evaluateRules({ rules, pr, diffText = '' }) {
  const hits = [];
  let escalated = 'low';

  for (const r of rules) {
    if (!r.enabled) continue;
    if (predicateMatches(r.predicate, pr)) {
      hits.push({ rule: r.name, reason: r.action.reason, tier: r.action.escalate_to, source: 'rule' });
      if (tierIdx(r.action.escalate_to) > tierIdx(escalated)) escalated = r.action.escalate_to;
    }
  }

  // built-in security escalation — always on
  if (pr.file_paths?.some(p => SECURITY_PATH_PATTERNS.some(re => re.test(p)))) {
    hits.push({
      rule: 'security:sensitive-paths',
      reason: 'Diff touches auth/secret/permission code',
      tier: 'high',
      source: 'builtin'
    });
    if (tierIdx('high') > tierIdx(escalated)) escalated = 'high';
  }

  // basic secret leak detection on diff text
  for (const p of SECRET_PATTERNS) {
    if (p.re.test(diffText)) {
      hits.push({
        rule: `security:possible-${p.name}`,
        reason: 'Possible secret detected in diff',
        tier: 'critical',
        source: 'builtin'
      });
      escalated = 'critical';
    }
  }

  return { escalated_tier: escalated, hits };
}
