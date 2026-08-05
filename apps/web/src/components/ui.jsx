import clsx from 'clsx';
import { motion } from 'framer-motion';
import TierPill from './TierPill.jsx';
import AnimatedNumber from './AnimatedNumber.jsx';
import { fadeUp } from '../lib/motion.js';

export const TIER_COLOR = {
  low: '#2fa77e', medium: '#e88b1a', high: '#e6552a', critical: '#d92e58'
};
export const TIER_COLOR_ONBG = {
  low: '#6ad9b0', medium: '#ffc061', high: '#ff8f6a', critical: '#ff6b8a'
};
export { TierPill };

// Kept for back-compat where old TierBadge is imported.
export function TierBadge({ tier, score }) {
  return <TierPill tier={tier} score={score} />;
}

export function Page({ title, subtitle, right, children }) {
  return (
    <div>
      <header className="px-8 h-16 hairline-bg-b flex items-center justify-between">
        <div>
          <div className="text-onbg font-medium text-[15px]">{title}</div>
          {subtitle && <div className="text-xs text-onbg3 mt-0.5">{subtitle}</div>}
        </div>
        <div>{right}</div>
      </header>
      <div className="p-8">{children}</div>
    </div>
  );
}

// Panel — default is static; pass `hover` to opt into lift + shadow on hover.
// `dense` reduces internal padding for tables/lists. `accent` adds an
// accent-colored top edge for the "most important" panel on a page.
export function Panel({ title, className, children, right, hover, accent, elevated }) {
  return (
    <div
      className={clsx(
        'bg-panel relative',
        hover && 'card-lift hover:shadow-lift',
        elevated ? 'shadow-lift' : 'shadow-soft',
        className
      )}
    >
      {accent && <div className="absolute top-0 left-0 right-0 h-[2px] bg-grad-accent" />}
      {title && (
        <div className="px-4 h-11 hairline-b flex items-center justify-between">
          <div className="text-sm text-ink font-medium">{title}</div>
          {right}
        </div>
      )}
      <div>{children}</div>
    </div>
  );
}

// StatTile — count-up numeric hero, optional trend, optional gradient
// underline for hierarchy. `emphasis` scales up the numeral.
export function StatTile({ label, value, hint, trend, decimals = 0, emphasis = false, accentColor }) {
  return (
    <motion.div
      variants={fadeUp}
      className="bg-panel shadow-soft p-5 card-lift hover:shadow-lift relative overflow-hidden group"
    >
      <div className="text-[10px] text-ink3 mono uppercase tracking-widest">{label}</div>
      <div className={clsx('serif text-ink mt-2 tabular-nums', emphasis ? 'text-5xl' : 'text-4xl')}>
        {typeof value === 'number'
          ? <AnimatedNumber value={value} decimals={decimals} />
          : value}
      </div>
      <div className="flex items-center gap-2 mt-1.5">
        {hint && <span className="text-xs text-ink3">{hint}</span>}
        {trend != null && (
          <span className={clsx('text-[11px] mono',
            trend > 0 ? 'text-tier-high' : trend < 0 ? 'text-tier-low' : 'text-ink3')}>
            {trend > 0 ? '↑' : trend < 0 ? '↓' : '·'} {Math.abs(trend)}%
          </span>
        )}
      </div>
      <div
        className="absolute bottom-0 left-0 right-0 h-[2px] opacity-0 group-hover:opacity-100 transition-opacity duration-300"
        style={{ background: accentColor || 'linear-gradient(90deg, #f5b544, #ff8a5a)' }}
      />
    </motion.div>
  );
}

export function Empty({ children }) {
  return <div className="p-8 text-center text-ink3 text-sm">{children}</div>;
}
