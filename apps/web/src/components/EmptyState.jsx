// Editorial linework illustrations, monochrome + brand-blue only. Reads
// against the dark panel bg.

export default function EmptyState({ title, body, action, variant = 'search' }) {
  return (
    <div className="p-10 flex flex-col items-center text-center gap-4">
      {ILLOS[variant]}
      <div className="serif text-xl text-ink">{title}</div>
      {body && <div className="text-sm text-ink2 max-w-sm leading-relaxed">{body}</div>}
      {action && <div className="mt-2">{action}</div>}
    </div>
  );
}

const LINE  = '#2a2d36';
const INK   = '#9ba1ad';
const BLUE  = '#035BD6';

const ILLOS = {
  search: (
    <svg width="120" height="90" viewBox="0 0 120 90" fill="none">
      <rect x="10" y="14" width="70" height="10" fill={LINE} />
      <rect x="10" y="32" width="90" height="10" fill={LINE} />
      <rect x="10" y="50" width="50" height="10" fill={LINE} />
      <circle cx="94" cy="66" r="14" stroke={BLUE} strokeWidth="2" fill="none" />
      <line x1="104" y1="76" x2="115" y2="86" stroke={BLUE} strokeWidth="2" strokeLinecap="square" />
    </svg>
  ),
  chart: (
    <svg width="140" height="90" viewBox="0 0 140 90" fill="none">
      <path d="M0 80 L20 60 L40 70 L60 40 L80 50 L100 30 L140 45"
        stroke={BLUE} strokeWidth="2" fill="none" strokeLinejoin="miter" strokeLinecap="square" />
      {[0,20,40,60,80,100,140].map((x, i) =>
        <circle key={i} cx={x} cy={[80,60,70,40,50,30,45][i]} r="2.5" fill={BLUE} />
      )}
      <line x1="0" y1="88" x2="140" y2="88" stroke={LINE} strokeWidth="1" />
    </svg>
  ),
  bell: (
    <svg width="90" height="90" viewBox="0 0 90 90" fill="none">
      <path d="M45 15 C 30 15 22 27 22 44 C 22 55 18 60 15 65 L 75 65 C 72 60 68 55 68 44 C 68 27 60 15 45 15 Z"
        fill="none" stroke={INK} strokeWidth="2" strokeLinejoin="miter" />
      <rect x="40" y="9" width="10" height="8" fill={INK} />
      <path d="M38 70 Q 45 78 52 70" stroke={INK} strokeWidth="2" fill="none" strokeLinecap="square" />
      <circle cx="70" cy="22" r="6" fill={BLUE} />
    </svg>
  )
};
