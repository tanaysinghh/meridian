import { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { motion } from 'framer-motion';
import { api } from '../lib/api.js';
import { Page, Panel, TIER_COLOR } from '../components/ui.jsx';
import TierPill from '../components/TierPill.jsx';
import EmptyState from '../components/EmptyState.jsx';
import { SkeletonPanel, SkeletonBar } from '../components/Skeleton.jsx';
import AnimatedNumber from '../components/AnimatedNumber.jsx';
import { fadeUp, ease, stagger } from '../lib/motion.js';

export default function PRDetail() {
  const { id } = useParams();
  const [data, setData] = useState(null);
  const [sugg, setSugg] = useState([]);

  useEffect(() => {
    setData(null);
    api.pr(id).then(setData);
    api.suggest(id).then(r => setSugg(r.suggestions));
  }, [id]);

  if (!data) return (
    <Page title="Pull request">
      <div className="grid grid-cols-3 gap-6">
        <div className="col-span-2 space-y-6">
          <SkeletonPanel rows={4} /><SkeletonPanel rows={5} />
        </div>
        <div className="space-y-6">
          <SkeletonPanel rows={3} /><SkeletonPanel rows={3} />
        </div>
      </div>
    </Page>
  );
  const pr = data.pr;

  return (
    <Page
      title={<span><span className="mono text-onbg3">#{pr.number}</span> {pr.title}</span>}
      subtitle={<span className="mono">{pr.repo_full_name} · {pr.author_login} → {pr.base_ref}</span>}
      right={<a href={pr.url} target="_blank" rel="noreferrer"
        className="text-xs mono text-onbg3 hover:text-onbg transition-colors flex items-center gap-1">
        Open on GitHub <span className="opacity-70">↗</span>
      </a>}
    >
      <motion.div initial="hidden" animate="visible" variants={stagger(0.05)} className="grid grid-cols-3 gap-6">
        <div className="col-span-2 space-y-6">
          <motion.div variants={fadeUp}>
            <Panel elevated accent>
              <div className="p-6 flex items-start gap-8">
                <ScoreDial score={pr.score} tier={pr.tier} />
                <div className="flex-1">
                  <div className="flex items-center gap-3 mb-2">
                    <TierPill tier={pr.tier} score={pr.score} size="lg" pulse={pr.tier === 'critical'} />
                    <span className="text-xs text-ink3 mono">
                      confidence: {pr.confidence} · model {pr.model_version}
                    </span>
                  </div>
                  <div className="text-sm text-ink2 leading-relaxed mt-2">
                    {pr.tier === 'critical' && 'Multiple signals point to this being a high-blast-radius change. Review carefully; consider a second approver.'}
                    {pr.tier === 'high'     && 'This change touches sensitive code paths or is unusually large. Recommended to be reviewed by a domain owner.'}
                    {pr.tier === 'medium'   && 'Standard review process is fine, though a few features nudge risk upward.'}
                    {pr.tier === 'low'      && 'Model considers this change low-blast-radius. Safe to review at normal pace.'}
                  </div>
                  {(pr.rule_hits || []).length > 0 && (
                    <div className="mt-4 hairline bg-panel2 p-3 text-xs">
                      <div className="text-ink3 mono uppercase tracking-wider mb-2">Rule escalations</div>
                      {pr.rule_hits.map((h, i) => (
                        <div key={i} className="flex justify-between text-ink2 py-0.5">
                          <span>{h.rule}</span>
                          <span className="mono" style={{ color: TIER_COLOR[h.tier] }}>{h.tier}</span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            </Panel>
          </motion.div>

          <motion.div variants={fadeUp}>
            <Panel title="Why this score">
              <div className="p-4 space-y-1">
                {(pr.contributions || []).map((c, i) => (
                  <ContributionBar key={i} contrib={c} delay={0.15 + i * 0.06} />
                ))}
              </div>
            </Panel>
          </motion.div>

          <motion.div variants={fadeUp}>
            <Panel title="Diff summary">
              <div className="p-4 grid grid-cols-3 text-sm">
                <StatBig label="Additions" value={pr.additions} color="#10b981" prefix="+" />
                <StatBig label="Deletions" value={pr.deletions} color="#ef4444" prefix="−" />
                <StatBig label="Files"     value={pr.changed_files} color="#035BD6" />
              </div>
              <div className="hairline-t p-4">
                <div className="text-xs text-ink3 mono uppercase mb-2">Files touched</div>
                <ul className="text-sm text-ink2 space-y-1 mono">
                  {(pr.file_paths || []).map(p => (
                    <li key={p} className="hover:text-ink transition-colors">{p}</li>
                  ))}
                </ul>
              </div>
            </Panel>
          </motion.div>

          <motion.div variants={fadeUp}>
            <Panel title="Timeline">
              <ul className="divide-y divide-line text-sm">
                {data.events.map((e, i) => (
                  <li key={i} className="px-4 py-2.5 flex items-center gap-4 hover:bg-panel2 transition-colors">
                    <span className="mono text-xs text-ink3 w-40 tabular-nums">{new Date(e.occurred_at).toLocaleString()}</span>
                    <span className="mono text-xs text-accent2 w-40">{e.event_type}</span>
                    <span className="text-ink2">{e.actor_login}</span>
                  </li>
                ))}
              </ul>
            </Panel>
          </motion.div>
        </div>

        <div className="space-y-6">
          <motion.div variants={fadeUp}>
            <Panel title="Suggested reviewers">
              {sugg.length === 0 ? (
                <EmptyState
                  variant="search"
                  title="No signal yet"
                  body="No ownership history for these files. Suggestions will appear as commits accumulate."
                />
              ) : (
                <ul>
                  {sugg.map((s, i) => (
                    <motion.li key={s.login}
                      initial={{ opacity: 0, x: -6 }}
                      animate={{ opacity: 1, x: 0 }}
                      transition={{ delay: 0.15 + i * 0.06, duration: 0.3, ease }}
                      className="hairline-b last:shadow-none px-4 py-3 flex items-center justify-between hover:bg-panel2 transition-colors"
                    >
                      <div>
                        <div className="text-ink text-sm">{s.login}</div>
                        <div className="text-xs text-ink3 mono">
                          ownership {s.ownership_score} · {s.open_reviews} open
                        </div>
                      </div>
                      <div className="mono text-xs text-accent2 tabular-nums">{s.score}</div>
                    </motion.li>
                  ))}
                </ul>
              )}
            </Panel>
          </motion.div>

          <motion.div variants={fadeUp}>
            <Panel title="Post-merge outcome">
              <OutcomeForm prId={pr.id} initial={{
                reverted: pr.reverted, hotfixed: pr.hotfixed,
                caused_incident: pr.caused_incident, notes: pr.outcome_notes || ''
              }} />
            </Panel>
          </motion.div>

          <motion.div variants={fadeUp}>
            <Panel title="Reviewers requested">
              <ul className="p-4 text-sm text-ink2 space-y-1">
                {(pr.requested_reviewers || []).map(r => <li key={r} className="mono">{r}</li>)}
                {(pr.requested_reviewers || []).length === 0 && <li className="text-ink3 text-xs">None yet</li>}
              </ul>
            </Panel>
          </motion.div>
        </div>
      </motion.div>

      <div className="mt-8">
        <Link to="/app/prs" className="text-xs mono text-onbg3 hover:text-onbg transition-colors">← Back to all PRs</Link>
      </div>
    </Page>
  );
}

/* --- Animated dial (SVG stroke-dashoffset) --- */
function ScoreDial({ score, tier }) {
  const size = 140, stroke = 12, r = (size - stroke) / 2, c = 2 * Math.PI * r;
  const val = Math.max(0, Math.min(1, score || 0));
  const color = TIER_COLOR[tier];

  return (
    <div className="relative shrink-0">
      <svg width={size} height={size}>
        <defs>
          <linearGradient id={`dial-${tier}`} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0"   stopColor={color} stopOpacity="0.6" />
            <stop offset="1"   stopColor={color} stopOpacity="1" />
          </linearGradient>
        </defs>
        <circle cx={size/2} cy={size/2} r={r} stroke="#2a2d36" strokeWidth={stroke} fill="none" />
        <motion.circle
          cx={size/2} cy={size/2} r={r}
          stroke={`url(#dial-${tier})`}
          strokeWidth={stroke} fill="none"
          strokeLinecap="butt"
          strokeDasharray={c}
          initial={{ strokeDashoffset: c }}
          animate={{ strokeDashoffset: c * (1 - val) }}
          transition={{ duration: 1.1, ease }}
          transform={`rotate(-90 ${size/2} ${size/2})`}
          style={{ filter: `drop-shadow(0 0 6px ${color}80)` }}
        />
      </svg>
      <div className="absolute inset-0 flex items-center justify-center">
        <span className="serif text-4xl text-ink tabular-nums">
          <AnimatedNumber value={val} decimals={2} duration={1100} />
        </span>
      </div>
    </div>
  );
}

function ContributionBar({ contrib, delay }) {
  const isUp = contrib.direction === 'up';
  const color = isUp ? '#f97316' : '#10b981';
  const width = Math.min(contrib.weight * 100, 50);
  return (
    <div className="flex items-center gap-3 py-1">
      <div className="w-40 text-xs text-ink2">{contrib.label || contrib.feature}</div>
      <div className="flex-1 h-2 bg-line relative">
        <motion.div
          className="absolute inset-y-0"
          style={{
            left: isUp ? '50%' : `${50 - width}%`,
            background: color
          }}
          initial={{ width: 0 }}
          animate={{ width: `${width}%` }}
          transition={{ duration: 0.7, delay, ease }}
        />
        <div className="absolute inset-y-0 left-1/2 w-px bg-ink3/40" />
      </div>
      <div className="w-16 text-right mono text-xs text-ink3 tabular-nums">
        {isUp ? '↑' : '↓'} {contrib.weight}
      </div>
      <div className="w-16 text-right mono text-xs text-ink2 tabular-nums">
        {typeof contrib.value === 'number'
          ? contrib.value.toFixed(2).replace(/\.?0+$/, '')
          : String(contrib.value)}
      </div>
    </div>
  );
}

function StatBig({ label, value, color, prefix = '' }) {
  return (
    <div>
      <div className="text-xs text-ink3 mono uppercase">{label}</div>
      <div className="serif text-3xl mt-1 tabular-nums" style={{ color }}>
        {prefix}<AnimatedNumber value={value} />
      </div>
    </div>
  );
}

function OutcomeForm({ prId, initial }) {
  const [v, setV] = useState(initial);
  const [saved, setSaved] = useState(false);
  const save = async () => {
    await api.prOutcome(prId, v);
    setSaved(true);
    setTimeout(() => setSaved(false), 1500);
  };
  return (
    <div className="p-4 space-y-3">
      <Toggle label="Reverted"        checked={v.reverted}         onChange={x => setV({ ...v, reverted:        x })} />
      <Toggle label="Hotfixed"        checked={v.hotfixed}         onChange={x => setV({ ...v, hotfixed:        x })} />
      <Toggle label="Caused incident" checked={v.caused_incident}  onChange={x => setV({ ...v, caused_incident: x })} />
      <textarea value={v.notes || ''} onChange={e => setV({ ...v, notes: e.target.value })}
        placeholder="Notes (optional)"
        className="w-full bg-bg hairline p-2 text-sm text-ink outline-none min-h-[80px] focus:outline-accent" />
      <motion.button
        whileHover={{ y: -1 }}
        whileTap={{ scale: 0.98 }}
        onClick={save}
        className="w-full bg-accent hover:bg-accent2 text-white py-2 text-sm font-medium transition-colors"
      >
        {saved ? 'Saved ✓' : 'Save outcome'}
      </motion.button>
      <div className="text-xs text-ink3">Feeds the model's post-merge validation loop.</div>
    </div>
  );
}
function Toggle({ label, checked, onChange }) {
  return (
    <label className="flex items-center justify-between text-sm text-ink2 cursor-pointer">
      <span>{label}</span>
      <input type="checkbox" checked={!!checked} onChange={e => onChange(e.target.checked)}
        className="accent-accent w-4 h-4" />
    </label>
  );
}
