import { useCountUp } from '../hooks/useCountUp';

export default function AnimatedNumber({ value, decimals = 0, suffix = '', prefix = '', duration = 900 }) {
  const v = useCountUp(value, { decimals, duration });
  return <span>{prefix}{formatNum(v, decimals)}{suffix}</span>;
}

function formatNum(v, decimals) {
  if (!isFinite(v)) return '—';
  const opts = { minimumFractionDigits: decimals, maximumFractionDigits: decimals };
  return v.toLocaleString(undefined, opts);
}
