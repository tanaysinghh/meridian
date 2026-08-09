import { useEffect, useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { api } from '../lib/api.js';
import { Page, Panel } from '../components/ui.jsx';
import EmptyState from '../components/EmptyState.jsx';
import { SkeletonPanel } from '../components/Skeleton.jsx';
import { fadeUp, ease } from '../lib/motion.js';

const SEVERITIES = ['sev1','sev2','sev3','sev4'];
const SEV_STYLES = {
  sev1: { text: '#fca5a5', bg: 'rgba(239, 68, 68, 0.14)',  dot: '#ef4444' },
  sev2: { text: '#fdba74', bg: 'rgba(249, 115, 22, 0.14)', dot: '#f97316' },
  sev3: { text: '#fcd34d', bg: 'rgba(234, 179, 8, 0.14)',  dot: '#eab308' },
  sev4: { text: '#9ba1ad', bg: 'rgba(155, 161, 173, 0.10)', dot: '#5c626e' }
};

export default function Incidents() {
  const [items, setItems] = useState(null);
  const [prs, setPrs] = useState([]);
  const [form, setForm] = useState({ title: '', severity: 'sev3', description: '', related_pr_id: '' });

  const load = () => {
    api.incidents().then(r => setItems(r.items));
    api.prs({ state: 'merged' }).then(r => setPrs(r.items));
  };
  useEffect(load, []);

  const submit = async () => {
    if (!form.title) return;
    await api.createIncident({
      ...form,
      related_pr_id: form.related_pr_id || null,
      occurred_at: new Date().toISOString()
    });
    setForm({ title: '', severity: 'sev3', description: '', related_pr_id: '' });
    load();
  };

  return (
    <Page title="Incidents" subtitle="Closes the feedback loop into model validation">
      <div className="grid grid-cols-3 gap-6">
        <motion.div variants={fadeUp} initial="hidden" animate="visible" className="col-span-2">
          <Panel title="Recent incidents" elevated>
            {!items ? <div className="p-4"><SkeletonPanel rows={4} /></div>
              : items.length === 0 ? (
                <EmptyState variant="bell" title="No incidents — nice."
                  body="Report one below to feed real outcomes back into the risk model." />
              ) : (
                <AnimatePresence>
                  <ul>
                    {items.map((i, idx) => (
                      <motion.li key={i.id}
                        layout
                        initial={{ opacity: 0, y: 6 }}
                        animate={{ opacity: 1, y: 0 }}
                        transition={{ delay: idx * 0.04, duration: 0.3, ease }}
                        className="row-hover hairline-b last:shadow-none px-4 py-3.5 hover:bg-panel2 transition-colors">
                        <div className="flex items-center justify-between gap-4">
                          <div className="min-w-0">
                            <div className="text-ink font-medium">{i.title}</div>
                            <div className="text-xs text-ink3 mono mt-1">
                              {i.repo_full_name} · {new Date(i.occurred_at).toLocaleString()}
                              {i.pr_number && <> · linked to #{i.pr_number}</>}
                            </div>
                          </div>
                          <SevPill sev={i.severity} />
                        </div>
                        {i.description && <div className="text-sm text-ink2 mt-2 leading-relaxed">{i.description}</div>}
                      </motion.li>
                    ))}
                  </ul>
                </AnimatePresence>
              )}
          </Panel>
        </motion.div>

        <motion.div variants={fadeUp} initial="hidden" animate="visible" transition={{ delay: 0.06 }}>
          <Panel title="Report an incident" elevated accent>
            <div className="p-4 space-y-2.5">
              <input value={form.title} onChange={e => setForm({...form, title: e.target.value})}
                placeholder="Short title"
                className="w-full bg-bg hairline px-2 py-1.5 text-sm outline-none focus:shadow-accent-glow transition-shadow" />
              <select value={form.severity} onChange={e => setForm({...form, severity: e.target.value})}
                className="w-full bg-bg hairline px-2 py-1.5 text-sm cursor-pointer">
                {SEVERITIES.map(s => <option key={s} value={s}>{s.toUpperCase()}</option>)}
              </select>
              <select value={form.related_pr_id} onChange={e => setForm({...form, related_pr_id: e.target.value})}
                className="w-full bg-bg hairline px-2 py-1.5 text-sm cursor-pointer">
                <option value="">Not linked to a specific PR</option>
                {prs.map(p => <option key={p.id} value={p.id}>{p.repo_full_name} #{p.number} — {p.title.slice(0, 50)}</option>)}
              </select>
              <textarea value={form.description} onChange={e => setForm({...form, description: e.target.value})}
                placeholder="What broke, how did we find it, what fixed it?"
                className="w-full bg-bg hairline p-2 text-sm outline-none min-h-[100px] focus:shadow-accent-glow transition-shadow" />
              <motion.button
                whileHover={{ y: -1 }} whileTap={{ scale: 0.98 }}
                onClick={submit} className="w-full bg-accent hover:bg-accent2 text-white py-2 text-sm font-medium transition-colors">
                Report incident
              </motion.button>
              <div className="text-xs text-ink3 leading-relaxed">
                Linking to a PR marks it as <code className="text-tier-critical">caused_incident</code>, validating the model over time.
              </div>
            </div>
          </Panel>
        </motion.div>
      </div>
    </Page>
  );
}

function SevPill({ sev }) {
  const s = SEV_STYLES[sev];
  return (
    <span className="inline-flex items-center gap-1.5 mono uppercase tracking-wider text-[10px] font-medium px-2 py-0.5"
      style={{ background: s.bg, color: s.text }}>
      <span className="w-1.5 h-1.5" style={{ background: s.dot, boxShadow: `0 0 6px ${s.dot}80` }} />
      {sev}
    </span>
  );
}
