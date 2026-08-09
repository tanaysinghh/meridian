import clsx from 'clsx';

// Tier pill tints — dark-panel variant. Text sits AA-legible on the tinted
// bg; dot uses the "true" tier hue with a soft glow.
const STYLES = {
  low: {
    text: '#6ee7b7',
    bg:   'rgba(16, 185, 129, 0.14)',
    dot:  '#10b981',
    glow: 'shadow-glow-low'
  },
  medium: {
    text: '#fcd34d',
    bg:   'rgba(234, 179, 8, 0.14)',
    dot:  '#eab308',
    glow: 'shadow-glow-medium'
  },
  high: {
    text: '#fdba74',
    bg:   'rgba(249, 115, 22, 0.14)',
    dot:  '#f97316',
    glow: 'shadow-glow-high'
  },
  critical: {
    text: '#fca5a5',
    bg:   'rgba(239, 68, 68, 0.14)',
    dot:  '#ef4444',
    glow: 'shadow-glow-critical'
  }
};

export default function TierPill({ tier, score, variant = 'solid', size = 'sm', pulse = false }) {
  if (!tier) return <span className="mono text-xs text-ink3">—</span>;
  const s = STYLES[tier];
  const sizing = size === 'lg' ? 'text-xs px-2.5 py-1' : 'text-[10px] px-2 py-0.5';
  return (
    <span
      className={clsx(
        'inline-flex items-center gap-1.5 mono uppercase tracking-wider font-medium',
        sizing,
        variant === 'solid' ? s.glow : 'hairline'
      )}
      style={{
        background: variant === 'solid' ? s.bg : 'transparent',
        color: s.text
      }}
    >
      <span
        className={clsx('w-1.5 h-1.5 shrink-0', pulse && 'animate-pulseGlow')}
        style={{ background: s.dot, boxShadow: `0 0 6px ${s.dot}80` }}
      />
      {tier}
      {score != null && (
        <span className="opacity-70 ml-0.5">{Number(score).toFixed(2)}</span>
      )}
    </span>
  );
}

export const TIER_STYLES = STYLES;
