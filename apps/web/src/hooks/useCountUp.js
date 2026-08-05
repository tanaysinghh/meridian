import { useEffect, useRef, useState } from 'react';

// Smooth count-up for numeric stat tiles. Handles integers and 1-decimal
// values. Skips animation when the user prefers reduced motion.
export function useCountUp(target, { duration = 900, decimals = 0 } = {}) {
  const [value, setValue] = useState(0);
  const rafRef = useRef();
  const startRef = useRef();
  const fromRef = useRef(0);

  useEffect(() => {
    if (target == null || Number.isNaN(Number(target))) return;
    const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    const to = Number(target);
    if (reduce) { setValue(to); return; }

    fromRef.current = value;
    startRef.current = performance.now();
    cancelAnimationFrame(rafRef.current);

    const tick = (t) => {
      const p = Math.min(1, (t - startRef.current) / duration);
      // ease-out-cubic
      const eased = 1 - Math.pow(1 - p, 3);
      const v = fromRef.current + (to - fromRef.current) * eased;
      setValue(v);
      if (p < 1) rafRef.current = requestAnimationFrame(tick);
    };
    rafRef.current = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(rafRef.current);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [target, duration]);

  const factor = 10 ** decimals;
  return Math.round(value * factor) / factor;
}
