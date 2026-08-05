import { useEffect, useState } from 'react';
import { motion } from 'framer-motion';
import { api } from '../lib/api.js';
import { Page, Panel } from '../components/ui.jsx';
import { SkeletonPanel } from '../components/Skeleton.jsx';
import { fadeUp } from '../lib/motion.js';

const TIERS = ['low','medium','high','critical'];

export default function Settings() {
  const [state, setState] = useState(null);
  const [dirty, setDirty] = useState(false);

  const load = () => api.settings().then(setState).catch(err => {
    console.error('[settings] load failed', err);
    setState({ settings: {}, users: [], integrations: {} });
  });
  useEffect(() => { load(); }, []);

  if (!state) return (
    <Page title="Settings"><div className="grid grid-cols-2 gap-6">
      <SkeletonPanel rows={4} /><SkeletonPanel rows={4} /><SkeletonPanel rows={3} /><SkeletonPanel rows={4} />
    </div></Page>
  );

  const s = state.settings || {};
  const users = state.users || [];
  const integrations = state.integrations || {};

  const patch = p => { setState({ ...state, settings: { ...s, ...p } }); setDirty(true); };
  const save = async () => { await api.updateSettings(state.settings); setDirty(false); load(); };

  const toggleTier = (tier, on) => {
    const set = new Set(s.notify_on_tiers || []);
    if (on) set.add(tier); else set.delete(tier);
    patch({ notify_on_tiers: [...set] });
  };

  return (
    <Page title="Settings" subtitle="Org-wide preferences and integrations"
      right={dirty && (
        <motion.button
          initial={{ opacity: 0, scale: 0.9 }}
          animate={{ opacity: 1, scale: 1 }}
          whileHover={{ y: -1 }}
          whileTap={{ scale: 0.97 }}
          onClick={save}
          className="bg-grad-accent text-white px-4 py-1.5 text-xs font-medium shadow-accent-glow">
          Save changes
        </motion.button>
      )}>
      <div className="grid grid-cols-2 gap-6">
        <motion.div variants={fadeUp} initial="hidden" animate="visible">
          <Panel title="Notifications" elevated>
            <div className="p-4 space-y-4">
              <Field label="Slack webhook URL"
                help="Override the env-configured webhook. Leave blank to use the default.">
                <input value={s.slack_webhook_url || ''}
                  onChange={e => patch({ slack_webhook_url: e.target.value })}
                  placeholder="https://hooks.slack.com/services/..."
                  className="w-full bg-white hairline px-2 py-1.5 text-sm text-ink outline-none mono focus:shadow-accent-glow transition-shadow" />
              </Field>
              <Field label="Digest recipients"
                help="Comma-separated email addresses that receive the weekly digest.">
                <input value={(s.digest_recipients || []).join(', ')}
                  onChange={e => patch({
                    digest_recipients: e.target.value.split(',').map(v => v.trim()).filter(Boolean)
                  })}
                  className="w-full bg-white hairline px-2 py-1.5 text-sm text-ink outline-none mono focus:shadow-accent-glow transition-shadow" />
              </Field>
              <Field label="Notify on tiers"
                help="Which tiers trigger a Slack notification when scored.">
                <div className="flex gap-3 mt-1">
                  {TIERS.map(t => {
                    const on = (s.notify_on_tiers || []).includes(t);
                    return (
                      <label key={t} className="flex items-center gap-1.5 text-xs text-ink2 mono cursor-pointer select-none hover:text-ink transition-colors">
                        <input type="checkbox" checked={on}
                          onChange={e => toggleTier(t, e.target.checked)}
                          className="accent-accent" />
                        {t}
                      </label>
                    );
                  })}
                </div>
              </Field>
            </div>
          </Panel>
        </motion.div>

        <motion.div variants={fadeUp} initial="hidden" animate="visible" transition={{ delay: 0.05 }}>
          <Panel title="Escalation SLA" elevated>
            <div className="p-4 space-y-4">
              <Field label="High-risk PR review SLA (hours)"
                help="High/critical PRs unreviewed past this window trigger auto-escalation.">
                <input type="number" min={1} max={168}
                  value={s.high_risk_sla_hours ?? 8}
                  onChange={e => patch({ high_risk_sla_hours: Number(e.target.value) })}
                  className="w-32 bg-white hairline px-2 py-1.5 text-sm text-ink outline-none mono focus:shadow-accent-glow transition-shadow" />
              </Field>
              <Field label="Auto-escalate">
                <label className="flex items-center gap-2 text-sm text-ink2 cursor-pointer">
                  <input type="checkbox" checked={!!s.auto_escalate}
                    onChange={e => patch({ auto_escalate: e.target.checked })}
                    className="accent-accent" />
                  Post to Slack when SLA is breached
                </label>
              </Field>
            </div>
          </Panel>
        </motion.div>

        <motion.div variants={fadeUp} initial="hidden" animate="visible" transition={{ delay: 0.1 }}>
          <Panel title="Integrations status" elevated>
            <ul className="p-4 space-y-3 text-sm">
              <StatusRow ok={!!integrations.github_app_configured} label="GitHub App"
                hint="Configure GITHUB_APP_ID + webhook secret to receive real PR events." />
              <StatusRow ok={!!integrations.slack_configured} label="Slack"
                hint="Set SLACK_WEBHOOK_URL (env) or above (org override)." />
              <StatusRow ok={!!integrations.email_configured} label="Email"
                hint="Set EMAIL_PROVIDER; digests write to outbox/ until configured." />
            </ul>
          </Panel>
        </motion.div>

        <motion.div variants={fadeUp} initial="hidden" animate="visible" transition={{ delay: 0.15 }}>
          <Panel title="Team members" elevated>
            <ul>
              {users.map((u, i) => (
                <motion.li key={u.id}
                  initial={{ opacity: 0, x: -6 }} animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: 0.2 + i * 0.04 }}
                  className="row-hover hairline-b last:shadow-none px-4 py-2.5 flex items-center gap-4 hover:bg-panel2 transition-colors">
                  <img src={u.avatar_url} className="w-7 h-7 hairline" alt="" />
                  <div className="flex-1 min-w-0">
                    <div className="text-ink text-sm truncate">{u.name}</div>
                    <div className="text-xs text-ink3 mono truncate">{u.email}</div>
                  </div>
                  <span className="mono text-xs text-ink2 uppercase tracking-wider">{u.role}</span>
                </motion.li>
              ))}
            </ul>
          </Panel>
        </motion.div>
      </div>
    </Page>
  );
}

function Field({ label, help, children }) {
  return (
    <div>
      <div className="text-xs text-ink3 mono uppercase tracking-wider mb-1.5">{label}</div>
      {children}
      {help && <div className="text-xs text-ink3 mt-1.5 leading-relaxed">{help}</div>}
    </div>
  );
}

function StatusRow({ ok, label, hint }) {
  return (
    <li className="flex items-start gap-3">
      <span
        className={`w-2 h-2 shrink-0 mt-1.5 ${ok ? 'bg-tier-low' : 'bg-tier-medium'}`}
        style={ok
          ? { boxShadow: '0 0 8px #2fa77e60' }
          : { boxShadow: '0 0 8px #e88b1a60' }} />
      <div className="flex-1">
        <div className="text-ink">
          {label}
          <span className="text-xs text-ink3 mono ml-2">{ok ? 'configured' : 'stubbed'}</span>
        </div>
        <div className="text-xs text-ink3 mt-0.5">{hint}</div>
      </div>
    </li>
  );
}
