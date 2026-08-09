import clsx from 'clsx';
import { motion } from 'framer-motion';
import TierPill from './TierPill.jsx';
import AnimatedNumber from './AnimatedNumber.jsx';
import { fadeUp } from '../lib/motion.js';

export const TIER_COLOR = {
  low: '#10b981', medium: '#eab308', high: '#f97316', critical: '#ef4444'
};
export const TIER_COLOR_ONBG = {
  low: '#6ee7b7', medium: '#fcd34d', high: '#fdba74', critical: '#fca5a5'
};
export { TierPill };

export function TierBadge({ tier, score }) {
  return <TierPill tier={tier} score={score} />;
}

export function Page({ title, subtitle, right, children }) {
  return (
    <div>
      <header className="px-8 h-16 hairline-bg-b flex items-center justify-between">
        <div>
          <div className="text-ink font-medium text-[15px]">{title}</div>
          {subtitle && <div className="text-xs text-ink3 mt-0.5">{subtitle}</div>}
        </div>
        <div>{right}</div>
      </header>
      <div className="p-8">{children}</div>
    </div>
  );
}

// Dark card surface. `accent` adds a 1px brand-blue top edge — reserve it
// for the "most important" panel on a page. `hover` opts into a lift.
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
      {accent && <div className="absolute top-0 left-0 right-0 h-px bg-accent" />}
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

export function StatTile({ label, value, hint, trend, decimals = 0, emphasis = false }) {
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
      <div className="absolute bottom-0 left-0 right-0 h-px opacity-0 group-hover:opacity-100 transition-opacity duration-300 bg-accent" />
    </motion.div>
  );
}

export function Empty({ children }) {
  return <div className="p-8 text-center text-ink3 text-sm">{children}</div>;
}

// Small primary button — the one and only "loud" affordance. Use once per view.
export function ButtonPrimary({ children, className, ...rest }) {
  return (
    <button
      className={clsx(
        'inline-flex items-center gap-2 bg-accent text-white text-sm font-medium px-4 py-2',
        'hover:bg-accent2 transition-colors',
        className
      )}
      {...rest}
    >
      {children}
    </button>
  );
}

// Secondary — quiet outline against the dark bg.
export function ButtonGhost({ children, className, ...rest }) {
  return (
    <button
      className={clsx(
        'inline-flex items-center gap-2 hairline bg-panel text-ink text-sm px-3 py-2',
        'hover:bg-panel2 transition-colors',
        className
      )}
      {...rest}
    >
      {children}
    </button>
  );
}
