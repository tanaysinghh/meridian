import { NavLink, Outlet, useNavigate, useLocation } from 'react-router-dom';
import { useEffect, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import ErrorBoundary from '../components/ErrorBoundary.jsx';
import { useAuth } from '../lib/auth.jsx';
import { getSocket } from '../lib/socket.js';
import { pageTransition } from '../lib/motion.js';

const NAV = [
  { to: '/app',              label: 'Overview',     end: true, icon: IconGrid },
  { to: '/app/prs',          label: 'Pull requests',            icon: IconMerge },
  { to: '/app/reviewers',    label: 'Reviewers',                icon: IconUsers },
  { to: '/app/analytics',    label: 'Analytics',                icon: IconChart },
  { to: '/app/repositories', label: 'Repositories',             icon: IconRepo },
  { to: '/app/incidents',    label: 'Incidents',                icon: IconAlert },
  { to: '/app/settings',     label: 'Settings',                 icon: IconGear }
];

export default function AppShell() {
  const { user, logout } = useAuth();
  const nav = useNavigate();
  const loc = useLocation();
  const [live, setLive] = useState('connecting');

  useEffect(() => {
    const s = getSocket();
    s.on('connect', () => setLive('live'));
    s.on('disconnect', () => setLive('offline'));
    s.on('connect_error', () => setLive('offline'));
    return () => { s.off('connect'); s.off('disconnect'); s.off('connect_error'); };
  }, []);

  return (
    <div className="min-h-screen flex bg-bg text-onbg">
      <aside className="w-60 shrink-0 hairline-bg-r flex flex-col relative">
        <div className="absolute inset-y-0 right-0 w-px bg-gradient-to-b from-transparent via-line2 to-transparent pointer-events-none" />
        <div className="px-5 h-16 flex items-center hairline-bg-b">
          <LogoOnBg />
        </div>
        <nav className="p-3 space-y-0.5 text-sm">
          {NAV.map(n => (
            <NavLink key={n.to} to={n.to} end={n.end}
              className={({ isActive }) =>
                `group relative flex items-center gap-3 px-3 py-2 transition-all duration-150 ${
                  isActive
                    ? 'text-onbg bg-bg2 shadow-marine'
                    : 'text-onbg2 hover:text-onbg hover:bg-bg2/60'
                }`}
            >
              {({ isActive }) => (
                <>
                  {isActive && (
                    <motion.span
                      layoutId="nav-active"
                      className="absolute left-0 top-0 bottom-0 w-[3px] bg-accent"
                      style={{ boxShadow: '0 0 12px #f5b544' }}
                      transition={{ type: 'spring', stiffness: 380, damping: 30 }}
                    />
                  )}
                  <n.icon active={isActive} />
                  <span>{n.label}</span>
                </>
              )}
            </NavLink>
          ))}
        </nav>
        <div className="mt-auto p-4 hairline-bg-t text-xs text-onbg3">
          <div className="flex items-center gap-2 mb-3">
            <span className={`inline-block w-1.5 h-1.5 rounded-full transition-colors ${
              live === 'live' ? 'bg-tier-low' : 'bg-onbg3'
            }`}
              style={live === 'live' ? { boxShadow: '0 0 8px #6ad9b0' } : {}} />
            <span className="mono uppercase tracking-wider">{live}</span>
          </div>
          <div className="flex items-center gap-2.5">
            <div className="w-7 h-7 bg-grad-accent flex items-center justify-center text-white text-xs font-medium">
              {(user?.name || '?').slice(0, 1).toUpperCase()}
            </div>
            <div className="min-w-0">
              <div className="text-onbg truncate">{user?.name}</div>
              <div className="mono text-[10px] truncate">{user?.role}</div>
            </div>
          </div>
          <button onClick={async () => { await logout(); nav('/'); }}
            className="mt-3 text-onbg2 hover:text-onbg transition-colors">
            Sign out →
          </button>
        </div>
      </aside>
      <main className="flex-1 min-w-0 relative">
        <ErrorBoundary key={loc.pathname}>
          <AnimatePresence mode="wait">
            <motion.div key={loc.pathname} {...pageTransition}>
              <Outlet />
            </motion.div>
          </AnimatePresence>
        </ErrorBoundary>
      </main>
    </div>
  );
}

function LogoOnBg() {
  return (
    <div className="flex items-center gap-2 select-none">
      <svg width={22} height={22} viewBox="0 0 24 24" fill="none">
        <path d="M2 20 L8 6 L12 14 L16 4 L22 20" stroke="#f2f6fb" strokeWidth="1.75" strokeLinejoin="miter" strokeLinecap="square" />
        <path d="M2 20 H22" stroke="#f5b544" strokeWidth="1.25" />
      </svg>
      <span className="font-medium tracking-tight text-onbg" style={{ letterSpacing: '-0.01em' }}>Meridian</span>
    </div>
  );
}

/* --- Icons — thin-stroke, sharp corners, house style --- */
function iconProps(active) {
  return {
    width: 14, height: 14, viewBox: '0 0 16 16', fill: 'none',
    stroke: 'currentColor', strokeWidth: 1.5, strokeLinecap: 'square', strokeLinejoin: 'miter'
  };
}
function IconGrid({ active }) { return (
  <svg {...iconProps(active)}>
    <rect x="2" y="2" width="5" height="5"/><rect x="9" y="2" width="5" height="5"/>
    <rect x="2" y="9" width="5" height="5"/><rect x="9" y="9" width="5" height="5"/>
  </svg>
); }
function IconMerge({ active }) { return (
  <svg {...iconProps(active)}>
    <circle cx="4" cy="3" r="1.5"/><circle cx="4" cy="13" r="1.5"/><circle cx="12" cy="8" r="1.5"/>
    <path d="M4 5 V 11"/><path d="M4 5 Q 4 8 12 8"/>
  </svg>
); }
function IconUsers({ active }) { return (
  <svg {...iconProps(active)}>
    <circle cx="6" cy="5" r="2"/><path d="M2 14 Q 6 9 10 14"/>
    <circle cx="11.5" cy="6" r="1.5"/><path d="M9 14 Q 12 11 14 14"/>
  </svg>
); }
function IconChart({ active }) { return (
  <svg {...iconProps(active)}>
    <path d="M2 13 L6 8 L9 10 L14 4"/><path d="M2 14 H 14"/>
  </svg>
); }
function IconRepo({ active }) { return (
  <svg {...iconProps(active)}>
    <path d="M3 2 H 12 V 12 H 4 Q 3 12 3 13 V 3 Q 3 2 4 2 Z"/><path d="M3 13 H 12"/>
  </svg>
); }
function IconAlert({ active }) { return (
  <svg {...iconProps(active)}>
    <path d="M8 2 L 14 13 H 2 Z"/><path d="M8 6 V 9"/><circle cx="8" cy="11.2" r="0.5" fill="currentColor" stroke="none"/>
  </svg>
); }
function IconGear({ active }) { return (
  <svg {...iconProps(active)}>
    <circle cx="8" cy="8" r="2"/>
    <path d="M8 1 V 3 M8 13 V 15 M1 8 H 3 M13 8 H 15 M3 3 L 4.5 4.5 M11.5 11.5 L 13 13 M3 13 L 4.5 11.5 M11.5 4.5 L 13 3"/>
  </svg>
); }
