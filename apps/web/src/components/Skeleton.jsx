// Shimmer skeleton primitives. Only one variant now — the whole app is dark.
import clsx from 'clsx';

export function SkeletonBar({ w = '100%', h = 12, className }) {
  return (
    <div
      className={clsx('skeleton', className)}
      style={{ width: w, height: h }}
    />
  );
}

export function SkeletonPanel({ rows = 3 }) {
  return (
    <div className="hairline bg-panel p-4 space-y-3">
      <SkeletonBar w="40%" h={14} />
      {Array.from({ length: rows }).map((_, i) => (
        <div key={i} className="flex items-center gap-3">
          <SkeletonBar w={90} h={20} />
          <SkeletonBar w={`${60 + (i * 7) % 30}%`} h={12} />
        </div>
      ))}
    </div>
  );
}

export function SkeletonTile() {
  return (
    <div className="hairline bg-panel p-5 space-y-3">
      <SkeletonBar w="55%" h={10} />
      <SkeletonBar w="35%" h={26} />
      <SkeletonBar w="70%" h={10} />
    </div>
  );
}
