export default function Logo({ size = 22 }) {
  return (
    <div className="flex items-center gap-2 select-none">
      <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
        <path d="M2 20 L8 6 L12 14 L16 4 L22 20" stroke="#f5f6f8" strokeWidth="1.75" strokeLinejoin="miter" strokeLinecap="square" />
        <path d="M2 20 H22" stroke="#035BD6" strokeWidth="1.25" />
      </svg>
      <span className="font-medium tracking-tight text-ink" style={{ letterSpacing: '-0.01em' }}>Meridian</span>
    </div>
  );
}
