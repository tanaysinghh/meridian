import { useEffect, useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { api } from '../lib/api.js';
import { Page, Panel } from '../components/ui.jsx';
import TierPill from '../components/TierPill.jsx';
import EmptyState from '../components/EmptyState.jsx';
import { SkeletonPanel } from '../components/Skeleton.jsx';
import { fadeUp, ease } from '../lib/motion.js';

export default function Repositories() {
  const [repos, setRepos] = useState(null);
  const [rules, setRules] = useState([]);
  const [selected, setSelected] = useState(null);

  const load = () => {
    api.repos().then(r => setRepos(r.items));
    api.rules().then(r => setRules(r.items));
  };
  useEffect(load, []);

  const rulesFor = id => rules.filter(r => r.repo_id === id);

  return (
    <Page title="Repositories" subtitle="Connected repos + rule overrides">
      <div className="grid grid-cols-2 gap-6">
        <motion.div variants={fadeUp} initial="hidden" animate="visible">
          <Panel title="Connected" elevated>
            {!repos ? <div className="p-4"><SkeletonPanel rows={4} /></div>
              : repos.length === 0 ? (
                <EmptyState variant="search" title="No repos connected"
                  body="Register a GitHub App and Meridian will start ingesting repo events." />
              ) : (
                <ul>
                  {repos.map((r, i) => (
                    <motion.li key={r.id}
                      initial={{ opacity: 0, x: -6 }} animate={{ opacity: 1, x: 0 }}
                      transition={{ delay: i * 0.04, duration: 0.3, ease }}
                      className={`row-hover hairline-b last:shadow-none px-4 py-3 cursor-pointer transition-all ${
                        selected?.id === r.id ? 'bg-panel2 shadow-inner' : 'hover:bg-panel2'
                      }`}
                      onClick={() => setSelected(r)}>
                      <div className="flex items-center justify-between gap-4">
                        <div className="min-w-0">
                          <div className="text-ink text-sm mono font-medium truncate">{r.full_name}</div>
                          <div className="text-xs text-ink3 mono mt-1 flex items-center gap-2">
                            <span>{r.open_prs} open</span>
                            {r.high_risk_prs > 0 && (
                              <>
                                <span>·</span>
                                <span className="text-tier-critical font-medium">{r.high_risk_prs} high-risk</span>
                              </>
                            )}
                            <span>·</span>
                            <span>threshold {r.risk_threshold}</span>
                          </div>
                        </div>
                        <ThresholdEditor repo={r} onSaved={load} />
                      </div>
                    </motion.li>
                  ))}
                </ul>
              )}
          </Panel>
        </motion.div>

        <motion.div variants={fadeUp} initial="hidden" animate="visible" transition={{ delay: 0.06 }}>
          <Panel title={selected ? `Rules for ${selected.full_name}` : 'Select a repo'} elevated>
            {!selected ? (
              <EmptyState variant="search" title="Pick a repo"
                body="Select one on the left to view or edit its rule overrides." />
            ) : (
              <>
                <AnimatePresence mode="popLayout">
                  <ul>
                    {rulesFor(selected.id).map((r, i) => (
                      <motion.li key={r.id}
                        layout
                        initial={{ opacity: 0, y: 6 }}
                        animate={{ opacity: 1, y: 0 }}
                        exit={{ opacity: 0, height: 0 }}
                        transition={{ delay: i * 0.04, duration: 0.28, ease }}
                        className="hairline-b last:shadow-none px-4 py-3 hover:bg-panel2 transition-colors">
                        <div className="flex items-center justify-between">
                          <div>
                            <div className="text-ink text-sm font-medium">{r.name}</div>
                            <div className="text-xs text-ink3 mono mt-1">
                              <span>{r.predicate.type}</span>
                              {r.predicate.paths && <span> · {r.predicate.paths.join(', ')}</span>}
                              {r.predicate.glob && <span> · {r.predicate.glob}</span>}
                            </div>
                          </div>
                          <div className="flex items-center gap-3">
                            <TierPill tier={r.action.escalate_to} />
                            <button className="text-xs text-ink3 hover:text-tier-critical transition-colors"
                              onClick={async () => { await api.deleteRule(r.id); load(); }}>Delete</button>
                          </div>
                        </div>
                        <div className="text-xs text-ink2 mt-1">{r.action.reason}</div>
                      </motion.li>
                    ))}
                    {rulesFor(selected.id).length === 0 && (
                      <li className="p-6 text-xs text-ink3 text-center">No rules yet — add one below.</li>
                    )}
                  </ul>
                </AnimatePresence>
                <NewRuleForm repoId={selected.id} onCreated={load} />
              </>
            )}
          </Panel>
        </motion.div>
      </div>
    </Page>
  );
}

function ThresholdEditor({ repo, onSaved }) {
  const [v, setV] = useState(repo.risk_threshold);
  return (
    <input type="number" min="0" max="1" step="0.05" value={v}
      onChange={e => setV(e.target.value)}
      onClick={e => e.stopPropagation()}
      onBlur={async () => { await api.updateRepo(repo.id, { risk_threshold: Number(v) }); onSaved(); }}
      className="w-20 bg-bg hairline px-2 py-1 mono text-xs text-ink text-right focus:shadow-accent-glow transition-shadow" />
  );
}

function NewRuleForm({ repoId, onCreated }) {
  const [name, setName]     = useState('');
  const [paths, setPaths]   = useState('');
  const [tier, setTier]     = useState('high');
  const [reason, setReason] = useState('');
  return (
    <div className="hairline-t p-4 space-y-2 bg-panel2/40">
      <div className="text-xs text-ink3 mono uppercase tracking-wider mb-1">Add a rule</div>
      <input value={name} onChange={e => setName(e.target.value)} placeholder="e.g. Auth files always high risk"
        className="w-full bg-bg hairline px-2 py-1.5 text-sm text-ink outline-none focus:shadow-accent-glow transition-shadow" />
      <input value={paths} onChange={e => setPaths(e.target.value)} placeholder="path prefixes, comma separated (src/auth/, src/session/)"
        className="w-full bg-bg hairline px-2 py-1.5 text-sm text-ink outline-none mono text-xs focus:shadow-accent-glow transition-shadow" />
      <div className="flex gap-2">
        <select value={tier} onChange={e => setTier(e.target.value)}
          className="bg-bg hairline px-2 py-1.5 text-sm text-ink flex-1 cursor-pointer">
          <option value="medium">medium</option>
          <option value="high">high</option>
          <option value="critical">critical</option>
        </select>
        <input value={reason} onChange={e => setReason(e.target.value)} placeholder="Reason shown on PR"
          className="flex-[2] bg-bg hairline px-2 py-1.5 text-sm text-ink outline-none focus:shadow-accent-glow transition-shadow" />
      </div>
      <motion.button
        whileHover={{ y: -1 }} whileTap={{ scale: 0.98 }}
        onClick={async () => {
          if (!name || !paths) return;
          await api.createRule({
            repo_id: repoId, name,
            predicate: { type: 'touches_paths', paths: paths.split(',').map(s => s.trim()) },
            action: { escalate_to: tier, reason }
          });
          setName(''); setPaths(''); setReason('');
          onCreated();
        }}
        className="w-full bg-accent hover:bg-accent2 text-white py-2 text-sm font-medium transition-colors"
      >
        Add rule
      </motion.button>
    </div>
  );
}
