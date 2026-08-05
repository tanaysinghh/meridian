// Shimmer skeleton primitives — pass `dark` when placed on the marine bg.
import clsx from 'clsx';

export function SkeletonBar({ w = '100%', h = 12, dark = false, className }) {
  return (
    <div
      className={clsx(dark ? 'skeleton-dark' : 'skeleton', className)}
      style={{ width: w, height: h }}
    />
  );
}

export function SkeletonPanel({ rows = 3, dark = false }) {
  return (
    <div className="hairline bg-panel p-4 space-y-3">
      <SkeletonBar w="40%" h={14} dark={dark} />
      {Array.from({ length: rows }).map((_, i) => (
        <div key={i} className="flex items-center gap-3">
          <SkeletonBar w={90} h={20} dark={dark} />
          <SkeletonBar w={`${60 + (i * 7) % 30}%`} h={12} dark={dark} />
        </div>
      ))}
    </div>
  );
}

export function SkeletonTile({ dark = false }) {
  return (
    <div className="hairline bg-panel p-5 space-y-3">
      <SkeletonBar w="55%" h={10} dark={dark} />
      <SkeletonBar w="35%" h={26} dark={dark} />
      <SkeletonBar w="70%" h={10} dark={dark} />
    </div>
  );
}
