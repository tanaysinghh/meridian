import { useEffect, useState } from 'react';
import {
  AreaChart, Area, LineChart, Line, XAxis, YAxis, Tooltip,
  ResponsiveContainer, BarChart, Bar, ComposedChart, CartesianGrid
} from 'recharts';
import { motion } from 'framer-motion';
import { api } from '../lib/api.js';
import { Page, Panel, StatTile, Empty } from '../components/ui.jsx';
import EmptyState from '../components/EmptyState.jsx';
import { SkeletonPanel, SkeletonBar } from '../components/Skeleton.jsx';
import { fadeUp, stagger, ease } from '../lib/motion.js';

const AXIS = { stroke: '#5c626e', fontSize: 11, fontFamily: 'IBM Plex Mono', tickLine: false, axisLine: false };
const GRID = '#1e2027';

export default function Analytics() {
  const [size,    setSize]    = useState(null);
  const [cycle,   setCycle]   = useState(null);
  const [heat,    setHeat]    = useState(null);
  const [rev,     setRev]     = useState(null);
  const [authors, setAuthors] = useState(null);

  useEffect(() => {
    api.analytics.size().then(r => setSize(r.series));
    api.analytics.cycle().then(r => setCycle(r.breakdown));
    api.analytics.heatmap().then(r => setHeat(r.cells));
    api.analytics.reverts().then(r => setRev(r.series));
    api.analytics.authors().then(r => setAuthors(r.authors));
  }, []);

  const revData = (rev || []).map(r => ({
    week: fmtWeek(r.week),
    merged: r.merged,
    reverted: r.reverted,
    rate: r.merged ? +(r.reverted / r.merged * 100).toFixed(1) : 0
  }));
  const sizeData = (size || []).map(s => ({
    week: fmtWeek(s.week),
    avg: s.avg_size,
    median: s.median_size,
    prs: s.prs
  }));

  return (
    <Page title="Analytics" subtitle="Trends across the last 90 days">
      <motion.div initial="hidden" animate="visible" variants={stagger(0.06)}>
        <motion.div variants={fadeUp} className="grid grid-cols-3 gap-6">
          <StatTile
            label="Time to first review"
            value={cycle?.hours_to_first_review ?? 0}
            decimals={1}
            suffix="h"
            hint="from PR open"
          />
          <StatTile
            label="First review → approve"
            value={cycle?.hours_first_review_to_approve ?? 0}
            decimals={1}
            hint="round-trips avg"
          />
          <StatTile
            label="Approve → merge"
            value={cycle?.hours_approve_to_merge ?? 0}
            decimals={1}
            hint="how long after LGTM"
          />
        </motion.div>

        <div className="mt-6 grid grid-cols-2 gap-6">
          <motion.div variants={fadeUp}>
            <Panel title="PR size trend" elevated>
              {!size ? <div className="p-4"><SkeletonPanel rows={4} /></div>
                : sizeData.length === 0 ? (
                  <EmptyState variant="chart" title="Not enough data" body="A few merged PRs and this trend will start to shape up." />
                ) : (
                  <div className="p-4 h-72">
                    <ResponsiveContainer>
                      <AreaChart data={sizeData} margin={{ top: 8, right: 16, left: 0, bottom: 0 }}>
                        <defs>
                          <linearGradient id="grad-avg" x1="0" y1="0" x2="0" y2="1">
                            <stop offset="0%"   stopColor="#035BD6" stopOpacity={0.55} />
                            <stop offset="100%" stopColor="#035BD6" stopOpacity={0}    />
                          </linearGradient>
                          <linearGradient id="grad-median" x1="0" y1="0" x2="0" y2="1">
                            <stop offset="0%"   stopColor="#10b981" stopOpacity={0.35} />
                            <stop offset="100%" stopColor="#10b981" stopOpacity={0}    />
                          </linearGradient>
                        </defs>
                        <CartesianGrid strokeDasharray="3 3" stroke={GRID} vertical={false} />
                        <XAxis dataKey="week" {...AXIS} />
                        <YAxis {...AXIS} />
                        <Tooltip contentStyle={tooltipStyle} cursor={{ stroke: '#2a2d36', strokeWidth: 1 }} />
                        <Area type="monotone" dataKey="avg"    stroke="#035BD6" strokeWidth={2} fill="url(#grad-avg)"    animationDuration={900} />
                        <Area type="monotone" dataKey="median" stroke="#10b981" strokeWidth={1.5} fill="url(#grad-median)" animationDuration={900} />
                      </AreaChart>
                    </ResponsiveContainer>
                  </div>
                )}
            </Panel>
          </motion.div>

          <motion.div variants={fadeUp}>
            <Panel title="Revert rate (weekly %)" elevated>
              {!rev ? <div className="p-4"><SkeletonPanel rows={4} /></div>
                : revData.length === 0 ? (
                  <EmptyState variant="chart" title="No merges yet" body="Once PRs start merging, revert-rate will chart here week by week." />
                ) : (
                  <div className="p-4 h-72">
                    <ResponsiveContainer>
                      <ComposedChart data={revData} margin={{ top: 8, right: 16, left: 0, bottom: 0 }}>
                        <CartesianGrid strokeDasharray="3 3" stroke={GRID} vertical={false} />
                        <XAxis dataKey="week" {...AXIS} />
                        <YAxis {...AXIS} />
                        <Tooltip contentStyle={tooltipStyle} cursor={{ fill: 'rgba(3,91,214,0.08)' }} />
                        <Bar  dataKey="merged"   fill="#2a2d36" animationDuration={800} />
                        <Bar  dataKey="reverted" fill="#ef4444" animationDuration={800} />
                        <Line type="monotone" dataKey="rate" stroke="#f97316" strokeWidth={2} dot={{ fill: '#f97316', r: 3 }} animationDuration={900} />
                      </ComposedChart>
                    </ResponsiveContainer>
                  </div>
                )}
            </Panel>
          </motion.div>

          <motion.div variants={fadeUp} className="col-span-2">
            <Panel title="Merge time heatmap" elevated>
              {!heat ? <div className="p-4"><SkeletonPanel rows={3} /></div>
                : heat.length === 0 ? (
                  <EmptyState variant="chart" title="No merges yet" body="Merge patterns show up here after some PRs have shipped." />
                ) : <Heatmap cells={heat} />}
            </Panel>
          </motion.div>

          <motion.div variants={fadeUp} className="col-span-2">
            <Panel title="Author trends (last 90d)" elevated>
              {!authors ? <div className="p-4"><SkeletonPanel rows={4} /></div>
                : authors.length === 0 ? (
                  <EmptyState variant="search" title="No authors yet" body="Author-level trends need at least a few merged PRs to be meaningful." />
                ) : (
                  <>
                    <table className="w-full text-sm">
                      <thead className="text-xs mono text-ink3 uppercase tracking-wider">
                        <tr className="hairline-b">
                          <th className="text-left px-4 py-3">Author</th>
                          <th className="text-right px-4 py-3">PRs</th>
                          <th className="text-right px-4 py-3">Avg risk</th>
                          <th className="text-right px-4 py-3">Reverted</th>
                        </tr>
                      </thead>
                      <tbody>
                        {authors.map((a, i) => (
                          <motion.tr key={a.author_login}
                            initial={{ opacity: 0, y: 4 }} animate={{ opacity: 1, y: 0 }}
                            transition={{ delay: 0.15 + i * 0.04, duration: 0.3, ease }}
                            className="hairline-b hover:bg-panel2 transition-colors row-hover">
                            <td className="px-4 py-2 mono text-ink2">{a.author_login}</td>
                            <td className="px-4 py-2 text-right mono tabular-nums">{a.prs}</td>
                            <td className="px-4 py-2 text-right mono tabular-nums">
                              <RiskDot value={a.avg_risk} />
                              {a.avg_risk?.toFixed(2)}
                            </td>
                            <td className="px-4 py-2 text-right mono tabular-nums">{a.reverted}</td>
                          </motion.tr>
                        ))}
                      </tbody>
                    </table>
                    <div className="p-4 text-xs text-ink3">
                      Shown per-author for pattern-spotting, not scoring people. Ping team leads if a trend concerns you.
                    </div>
                  </>
                )}
            </Panel>
          </motion.div>
        </div>
      </motion.div>
    </Page>
  );
}

function RiskDot({ value }) {
  const color = value >= 0.65 ? '#ef4444' : value >= 0.35 ? '#eab308' : '#10b981';
  return <span className="inline-block w-1.5 h-1.5 mr-2 align-middle" style={{ background: color }} />;
}

function Heatmap({ cells }) {
  const grid = {};
  let max = 0;
  cells.forEach(c => {
    grid[`${c.dow}-${c.hour}`] = c;
    if (c.merges > max) max = c.merges;
  });
  const days = ['Mon','Tue','Wed','Thu','Fri','Sat','Sun'];
  return (
    <div className="p-5 overflow-x-auto">
      <table className="mono text-[10px] border-collapse">
        <thead>
          <tr>
            <th></th>
            {Array.from({length: 24}, (_, h) =>
              <th key={h} className="text-ink3 w-6 text-center font-normal">
                {h % 3 === 0 ? h : ''}
              </th>)}
          </tr>
        </thead>
        <tbody>
          {days.map((d, di) => (
            <tr key={d}>
              <td className="text-ink3 pr-3 font-normal">{d}</td>
              {Array.from({length: 24}, (_, h) => {
                const c = grid[`${di+1}-${h}`];
                const intensity = c ? c.merges / max : 0;
                const risk = c?.avg_risk || 0;
                // tier-based palette — green → yellow → orange → red
                const tierRGB = risk < 0.35 ? '16, 185, 129'
                              : risk < 0.60 ? '234, 179, 8'
                              : risk < 0.80 ? '249, 115, 22'
                                            : '239, 68, 68';
                const bg = c
                  ? `rgba(${tierRGB}, ${0.14 + intensity * 0.70})`
                  : '#0f1013';
                return (
                  <motion.td key={h}
                    initial={{ opacity: 0, scale: 0.6 }}
                    animate={{ opacity: 1, scale: 1 }}
                    transition={{ delay: (di * 24 + h) * 0.003, duration: 0.25 }}
                    title={c ? `${c.merges} merges · avg risk ${risk.toFixed(2)}` : ''}
                    className="w-6 h-6 hover:outline hover:outline-2 hover:outline-accent transition-all"
                    style={{ background: bg, border: '1px solid #08090b' }} />
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
      <div className="mt-4 text-xs text-ink3">
        Warmer cells = higher avg risk. Watch for Friday-evening + late-night patterns on risky changes.
      </div>
    </div>
  );
}

const tooltipStyle = {
  background: '#0d0e11',
  border: '1px solid #2a2d36',
  borderRadius: 0,
  fontFamily: 'IBM Plex Mono',
  fontSize: 11,
  color: '#f5f6f8',
  boxShadow: '0 8px 24px -8px rgba(0, 0, 0, 0.65)',
  padding: '8px 10px'
};

function fmtWeek(iso) {
  const d = new Date(iso);
  return `${String(d.getMonth()+1).padStart(2,'0')}/${String(d.getDate()).padStart(2,'0')}`;
}
