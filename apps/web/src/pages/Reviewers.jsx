import { useEffect, useState } from 'react';
import { motion } from 'framer-motion';
import { api } from '../lib/api.js';
import { Page, Panel } from '../components/ui.jsx';
import EmptyState from '../components/EmptyState.jsx';
import { SkeletonPanel } from '../components/Skeleton.jsx';
import { fadeUp, ease } from '../lib/motion.js';

export default function Reviewers() {
  const [items, setItems] = useState(null);
  useEffect(() => { api.reviewers().then(r => setItems(r.items)); }, []);

  const max = Math.max(1, ...(items || []).map(i => i.open_reviews));

  return (
    <Page title="Reviewers" subtitle="Live review load across the team">
      <motion.div variants={fadeUp} initial="hidden" animate="visible">
        <Panel elevated accent>
          {!items ? <div className="p-4"><SkeletonPanel rows={5} /></div>
            : items.length === 0 ? (
              <EmptyState variant="search" title="No reviewers yet"
                body="Once teammates start reviewing PRs, they'll show up here with their live queue and average risk load."/>
            ) : (
              <ul>
                {items.map((u, i) => (
                  <motion.li
                    key={u.id}
                    initial={{ opacity: 0, y: 6 }}
                    animate={{ opacity: 1, y: 0 }}
                    transition={{ delay: i * 0.04, duration: 0.35, ease }}
                    className="row-hover hairline-b last:shadow-none px-4 py-3 flex items-center gap-4 hover:bg-panel2 transition-colors"
                  >
                    <img src={u.avatar_url} className="w-9 h-9 hairline shadow-soft" alt="" />
                    <div className="w-56">
                      <div className="text-ink text-sm font-medium">{u.name}</div>
                      <div className="text-xs text-ink3 mono">{u.github_login} · {u.role}</div>
                    </div>
                    <div className="flex-1">
                      <div className="h-2 bg-line overflow-hidden">
                        <motion.div
                          className="h-2 bg-accent"
                          initial={{ width: 0 }}
                          animate={{ width: `${(u.open_reviews / max) * 100}%` }}
                          transition={{ duration: 0.9, delay: 0.1 + i * 0.04, ease }}
                        />
                      </div>
                    </div>
                    <div className="w-36 text-right mono text-xs text-ink2 tabular-nums">
                      <span className="font-medium text-ink">{u.open_reviews}</span> open
                      <span className="text-ink3"> · </span>
                      avg risk {u.avg_risk ? Number(u.avg_risk).toFixed(2) : '—'}
                    </div>
                  </motion.li>
                ))}
              </ul>
            )}
        </Panel>
      </motion.div>
      <div className="mt-4 text-xs text-onbg3">
        Suggestion logic (see PR detail): ranks by historical file ownership, then dampens by open-review count so the queue stays balanced.
      </div>
    </Page>
  );
}
