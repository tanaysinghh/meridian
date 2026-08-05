import clsx from 'clsx';

// Tier pill tints — text/bg calibrated for the warm ivory panel bg (#f8f4ec)
// AA-contrast checked. Dot color = the "true" tier hue with a soft glow.
const STYLES = {
  low: {
    text: '#0d5f45',
    bg:   '#daf1e5',
    dot:  '#2fa77e',
    glow: 'shadow-glow-low'
  },
  medium: {
    text: '#7a4d0a',
    bg:   '#fbe7c8',
    dot:  '#e88b1a',
    glow: 'shadow-glow-medium'
  },
  high: {
    text: '#7a2818',
    bg:   '#fbd7c9',
    dot:  '#e6552a',
    glow: 'shadow-glow-high'
  },
  critical: {
    text: '#7a1230',
    bg:   '#fbd0dc',
    dot:  '#d92e58',
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
        style={{ background: s.dot, boxShadow: `0 0 8px ${s.dot}90` }}
      />
      {tier}
      {score != null && (
        <span className="opacity-70 ml-0.5">{Number(score).toFixed(2)}</span>
      )}
    </span>
  );
}

export const TIER_STYLES = STYLES;
