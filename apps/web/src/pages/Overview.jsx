import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { motion } from 'framer-motion';
import { api } from '../lib/api.js';
import { getSocket } from '../lib/socket.js';
import { Page, Panel, StatTile, Empty } from '../components/ui.jsx';
import TierPill, { TIER_STYLES } from '../components/TierPill.jsx';
import EmptyState from '../components/EmptyState.jsx';
import { SkeletonTile, SkeletonBar } from '../components/Skeleton.jsx';
import { fadeUp, stagger, ease } from '../lib/motion.js';
import AnimatedNumber from '../components/AnimatedNumber.jsx';

export default function Overview() {
  const [d, setD] = useState(null);
  const [flash, setFlash] = useState(null);

  const load = () => api.overview().then(setD);
  useEffect(() => { load(); }, []);

  useEffect(() => {
    const s = getSocket();
    const onScored = (p) => {
      setFlash(p);
      setTimeout(() => setFlash(null), 3000);
      load();
    };
    s.on('pr.scored', onScored);
    s.on('pr.updated', load);
    return () => { s.off('pr.scored', onScored); s.off('pr.updated', load); };
  }, []);

  if (!d) return <OverviewSkeleton />;

  const tierMap = Object.fromEntries(d.tiers.map(t => [t.tier, t.n]));
  const totalOpen = d.tiers.reduce((a, b) => a + b.n, 0);
  const criticalHigh = (tierMap.high || 0) + (tierMap.critical || 0);
  const mergedTotal = d.throughput_30d.reduce((a, b) => a + b.merged, 0);

  return (
    <Page
      title="Overview"
      subtitle="Team-wide review posture at a glance"
      right={flash && (
        <motion.div
          initial={{ opacity: 0, x: 10 }}
          animate={{ opacity: 1, x: 0 }}
          className="text-xs mono flex items-center gap-2"
          style={{ color: '#10b981' }}
        >
          <span className="w-1.5 h-1.5 rounded-full bg-tier-low animate-pulseGlow"
            style={{ boxShadow: '0 0 8px #10b981' }} />
          just now · {flash.repo} scored {flash.tier}
        </motion.div>
      )}
    >
      <motion.div initial="hidden" animate="visible" variants={stagger(0.05)}>
        {/* Hero: dominant risk-mix panel spanning wider */}
        <motion.div variants={fadeUp} className="grid grid-cols-12 gap-6">
          <div className="col-span-12 lg:col-span-8">
            <RiskMixHero tierMap={tierMap} totalOpen={totalOpen} slaBreaches={d.sla_breaches} />
          </div>
          <div className="col-span-12 lg:col-span-4 grid grid-cols-1 gap-4">
            <StatTile
              label="Open PRs"
              value={totalOpen}
              hint="across all repos"
            />
            <StatTile
              label="Merged · 30d"
              value={mergedTotal}
              hint="cross-repo throughput"
            />
          </div>
        </motion.div>

        {/* Secondary — recent activity feed */}
        <motion.div variants={fadeUp} className="mt-6">
          <Panel title="Recent activity" elevated>
            {d.recent.length === 0 ? (
              <EmptyState
                variant="search"
                title="Nothing to see yet"
                body="Once PR events start flowing in, they'll appear here in real time."
              />
            ) : (
              <ul>
                {d.recent.map((pr, i) => (
                  <motion.li
                    key={pr.id}
                    initial={{ opacity: 0, x: -8 }}
                    animate={{ opacity: 1, x: 0 }}
                    transition={{ delay: 0.15 + i * 0.04, duration: 0.35, ease }}
                    className="row-hover px-4 py-3 flex items-center gap-4 hairline-b last:shadow-none hover:bg-panel2"
                  >
                    <TierPill tier={pr.tier} score={pr.score} />
                    <Link to={`/app/prs/${pr.id}`} className="flex-1 truncate group">
                      <span className="text-ink group-hover:text-accent2 transition-colors">{pr.title}</span>
                      <span className="text-ink3 ml-2 mono text-xs">
                        {pr.repo_full_name} #{pr.number} · {pr.author_login}
                      </span>
                    </Link>
                    <span className="text-ink3 text-xs mono tabular-nums">{timeAgo(pr.updated_at)}</span>
                  </motion.li>
                ))}
              </ul>
            )}
          </Panel>
        </motion.div>
      </motion.div>
    </Page>
  );
}

/* --- The visually-dominant hero panel --- */
function RiskMixHero({ tierMap, totalOpen, slaBreaches }) {
  const tiers = ['critical', 'high', 'medium', 'low'];
  return (
    <div className="relative bg-panel shadow-lift overflow-hidden">
      <div className="absolute top-0 left-0 right-0 h-px bg-accent" />
      <div className="p-6 pb-8">
        <div className="flex items-baseline justify-between mb-8">
          <div>
            <div className="text-[10px] mono text-ink3 uppercase tracking-widest">Risk mix — open PRs</div>
            <div className="serif text-ink text-2xl mt-1">Where your review attention should land</div>
          </div>
          {slaBreaches > 0 && (
            <div className="flex items-center gap-2">
              <TierPill tier="critical" pulse />
              <span className="text-xs text-ink2">
                <b className="tabular-nums"><AnimatedNumber value={slaBreaches} /></b> past SLA
              </span>
            </div>
          )}
        </div>

        {/* Segmented risk bar */}
        <div className="flex h-3 overflow-hidden">
          {tiers.map(t => {
            const n = tierMap[t] || 0;
            const pct = totalOpen ? (n / totalOpen) * 100 : 0;
            return (
              <motion.div
                key={t}
                initial={{ width: 0 }}
                animate={{ width: `${pct}%` }}
                transition={{ duration: 0.9, delay: 0.15, ease }}
                style={{ background: TIER_STYLES[t].dot }}
                title={`${t}: ${n}`}
              />
            );
          })}
        </div>

        <div className="grid grid-cols-4 gap-6 mt-8">
          {tiers.map((t, i) => {
            const n = tierMap[t] || 0;
            return (
              <motion.div
                key={t}
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: 0.2 + i * 0.05, duration: 0.4, ease }}
              >
                <div className="flex items-center gap-2 mb-2">
                  <span className="w-1.5 h-1.5" style={{ background: TIER_STYLES[t].dot }} />
                  <span className="text-[10px] mono uppercase tracking-wider text-ink3">{t}</span>
                </div>
                <div className="serif text-3xl text-ink tabular-nums">
                  <AnimatedNumber value={n} duration={800 + i * 100} />
                </div>
              </motion.div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

function OverviewSkeleton() {
  return (
    <Page title="Overview" subtitle="Loading team posture…">
      <div className="grid grid-cols-12 gap-6">
        <div className="col-span-12 lg:col-span-8 bg-panel shadow-soft p-6 space-y-6">
          <SkeletonBar w="30%" h={12} />
          <SkeletonBar w="60%" h={26} />
          <SkeletonBar h={12} />
          <div className="grid grid-cols-4 gap-6">
            {[0,1,2,3].map(i => <div key={i} className="space-y-2">
              <SkeletonBar w="60%" h={10} />
              <SkeletonBar w="40%" h={28} />
            </div>)}
          </div>
        </div>
        <div className="col-span-12 lg:col-span-4 space-y-4">
          <SkeletonTile /><SkeletonTile />
        </div>
      </div>
      <div className="mt-6"><SkeletonTile /></div>
    </Page>
  );
}

function timeAgo(iso) {
  const s = (Date.now() - new Date(iso)) / 1000;
  if (s < 60)    return `${Math.floor(s)}s`;
  if (s < 3600)  return `${Math.floor(s/60)}m`;
  if (s < 86400) return `${Math.floor(s/3600)}h`;
  return `${Math.floor(s/86400)}d`;
}
