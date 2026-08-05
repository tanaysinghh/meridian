import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { motion } from 'framer-motion';
import { api } from '../lib/api.js';
import { Page, Panel } from '../components/ui.jsx';
import TierPill from '../components/TierPill.jsx';
import EmptyState from '../components/EmptyState.jsx';
import { SkeletonPanel } from '../components/Skeleton.jsx';
import { fadeUp, ease } from '../lib/motion.js';

const TIERS = ['', 'low', 'medium', 'high', 'critical'];
const STATES = ['', 'open', 'merged', 'closed'];

export default function PRs() {
  const [items, setItems] = useState(null);
  const [filters, setFilters] = useState({ state: 'open', tier: '', repo: '', author: '' });
  const [repos, setRepos] = useState([]);

  useEffect(() => { api.repos().then(r => setRepos(r.items)); }, []);
  useEffect(() => {
    setItems(null);
    api.prs(filters).then(r => setItems(r.items));
  }, [filters]);

  const authors = useMemo(() => [...new Set((items || []).map(i => i.author_login))], [items]);

  return (
    <Page title="Pull requests" subtitle={items ? `${items.length} shown` : 'Loading…'}>
      <Panel elevated accent>
        <div className="p-3 hairline-b flex gap-2 flex-wrap items-center">
          <Select label="State"  value={filters.state}  onChange={v => setFilters(f => ({ ...f, state:  v }))} options={STATES} />
          <Select label="Tier"   value={filters.tier}   onChange={v => setFilters(f => ({ ...f, tier:   v }))} options={TIERS}  />
          <Select label="Repo"   value={filters.repo}   onChange={v => setFilters(f => ({ ...f, repo:   v }))} options={['', ...repos.map(r => r.full_name)]} />
          <Select label="Author" value={filters.author} onChange={v => setFilters(f => ({ ...f, author: v }))} options={['', ...authors]} />
        </div>
        {!items ? <div className="p-4"><SkeletonPanel rows={6} /></div>
          : items.length === 0 ? (
              <EmptyState
                variant="search"
                title="No PRs match those filters"
                body="Try clearing a filter, or wait for new PR events to come in."
              />
          ) : (
          <table className="w-full text-sm">
            <thead className="text-xs text-ink3 mono uppercase tracking-wider">
              <tr className="hairline-b">
                <th className="text-left px-4 py-3 w-40">Risk</th>
                <th className="text-left px-4 py-3">PR</th>
                <th className="text-left px-4 py-3 w-40">Repo</th>
                <th className="text-left px-4 py-3 w-32">Author</th>
                <th className="text-right px-4 py-3 w-28">Size</th>
                <th className="text-right px-4 py-3 w-20">Files</th>
                <th className="text-right px-4 py-3 w-20">Updated</th>
              </tr>
            </thead>
            <tbody>
              {items.map((pr, i) => (
                <motion.tr
                  key={pr.id}
                  initial={{ opacity: 0, y: 4 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ delay: Math.min(i * 0.02, 0.3), duration: 0.3, ease }}
                  className="row-hover hairline-b hover:bg-panel2 transition-colors group"
                >
                  <td className="px-4 py-3"><TierPill tier={pr.tier} score={pr.score} /></td>
                  <td className="px-4 py-3">
                    <Link to={`/app/prs/${pr.id}`} className="text-ink group-hover:text-accent2 transition-colors">
                      <span className="mono text-ink3 mr-2">#{pr.number}</span>{pr.title}
                    </Link>
                  </td>
                  <td className="px-4 py-3 mono text-xs text-ink2">{pr.repo_full_name}</td>
                  <td className="px-4 py-3 text-xs text-ink2">{pr.author_login}</td>
                  <td className="px-4 py-3 text-right mono text-xs tabular-nums">
                    <span className="text-tier-low">+{pr.additions}</span>
                    <span className="text-ink3"> / </span>
                    <span className="text-tier-critical">−{pr.deletions}</span>
                  </td>
                  <td className="px-4 py-3 text-right mono text-xs text-ink2 tabular-nums">{pr.changed_files}</td>
                  <td className="px-4 py-3 text-right mono text-xs text-ink3 tabular-nums">{timeAgo(pr.updated_at)}</td>
                </motion.tr>
              ))}
            </tbody>
          </table>
        )}
      </Panel>
    </Page>
  );
}

function Select({ label, value, onChange, options }) {
  return (
    <label className="flex items-center gap-2 text-xs">
      <span className="text-ink3 mono uppercase tracking-wider">{label}</span>
      <select value={value} onChange={e => onChange(e.target.value)}
        className="bg-white hairline px-2 py-1.5 text-ink text-xs hover:bg-panel2 transition-colors cursor-pointer">
        {options.map(o => <option key={o} value={o}>{o || 'any'}</option>)}
      </select>
    </label>
  );
}
function timeAgo(iso) {
  const s = (Date.now() - new Date(iso)) / 1000;
  if (s < 3600)  return `${Math.floor(s/60)}m`;
  if (s < 86400) return `${Math.floor(s/3600)}h`;
  return `${Math.floor(s/86400)}d`;
}
