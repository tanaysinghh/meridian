import { useState } from 'react';
import { Link, useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../lib/auth.jsx';
import Logo from '../components/Logo.jsx';

export default function Login() {
  const nav = useNavigate();
  const loc = useLocation();
  const { login } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [err, setErr] = useState(null);
  const [busy, setBusy] = useState(false);

  const submit = async e => {
    e.preventDefault();
    setBusy(true); setErr(null);
    try {
      await login(email, password);
      nav(loc.state?.from || '/app');
    } catch {
      setErr('Invalid email or password.');
    } finally { setBusy(false); }
  };

  // The GitHub button hits the API. If OAuth isn't configured the API returns
  // 503 with a JSON body; probe for that and show an inline hint rather than
  // silently doing nothing.
  const [ghHint, setGhHint] = useState(null);
  const startGitHub = async e => {
    e.preventDefault();
    setGhHint(null);
    try {
      const res = await fetch('/api/auth/github', { credentials: 'include', redirect: 'manual' });
      if (res.type === 'opaqueredirect' || (res.status >= 300 && res.status < 400)) {
        window.location.href = '/api/auth/github';
        return;
      }
      if (res.status === 503) {
        setGhHint('GitHub sign-in isn’t configured on this server yet.');
        return;
      }
      window.location.href = '/api/auth/github';
    } catch {
      setGhHint('GitHub sign-in is unavailable right now.');
    }
  };

  return (
    <div className="min-h-screen flex bg-bg">
      <div className="hidden md:flex flex-1 hairline-bg-r p-10 grid-bg">
        <div className="m-auto max-w-md">
          <LogoLight />
          <div className="mt-16 serif text-5xl leading-tight text-onbg">
            Sign in to see<br />what's about to<br /><span className="italic text-onbg2">break.</span>
          </div>
          <div className="mt-6 text-onbg2 leading-relaxed">
            Meridian gives your review queue a signal it never had.
            Every PR, scored — every score, explained.
          </div>
        </div>
      </div>
      <div className="flex-1 flex items-center justify-center p-6 bg-panel">
        <div className="w-full max-w-sm">
          <div className="mb-8 md:hidden"><Logo /></div>
          <h1 className="text-2xl text-ink font-medium">Sign in</h1>
          <p className="text-ink2 mt-1 text-sm">Continue with GitHub or your work email.</p>

          <button onClick={startGitHub}
            className="mt-8 w-full hairline bg-white px-4 py-2.5 text-sm text-ink flex items-center justify-center gap-2 hover:bg-panel2">
            <GhIcon /> Continue with GitHub
          </button>
          {ghHint && <div className="mt-2 text-xs text-ink3">{ghHint}</div>}

          <div className="my-6 flex items-center gap-4 text-xs text-ink3">
            <div className="flex-1 h-px bg-line" />OR<div className="flex-1 h-px bg-line" />
          </div>

          <form onSubmit={submit} className="space-y-3">
            <Field label="Email">
              <input type="email" autoComplete="email" required
                value={email} onChange={e => setEmail(e.target.value)}
                className="w-full bg-white hairline px-3 py-2 text-ink outline-none focus:shadow-panel" />
            </Field>
            <Field label="Password">
              <input type="password" autoComplete="current-password" required
                value={password} onChange={e => setPassword(e.target.value)}
                className="w-full bg-white hairline px-3 py-2 text-ink outline-none" />
            </Field>
            {err && <div className="text-tier-critical text-sm">{err}</div>}
            <button disabled={busy}
              className="w-full bg-ink text-panel px-4 py-2.5 text-sm font-medium hover:bg-bg2 disabled:opacity-60">
              {busy ? 'Signing in…' : 'Sign in'}
            </button>
          </form>

          <div className="mt-8 text-xs text-ink3">
            <Link to="/" className="hover:text-ink">← Back to landing</Link>
          </div>
        </div>
      </div>
    </div>
  );
}

function Field({ label, children }) {
  return (
    <div className="block">
      <div className="text-xs text-ink3 mono uppercase tracking-wider mb-1">{label}</div>
      {children}
    </div>
  );
}
function LogoLight() {
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
function GhIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
      <path d="M12 .5C5.7.5.5 5.7.5 12c0 5.1 3.3 9.4 7.9 10.9.6.1.8-.3.8-.6v-2c-3.2.7-3.9-1.5-3.9-1.5-.5-1.3-1.3-1.7-1.3-1.7-1-.7.1-.7.1-.7 1.2.1 1.8 1.2 1.8 1.2 1.1 1.8 2.8 1.3 3.5 1 .1-.8.4-1.3.8-1.6-2.6-.3-5.4-1.3-5.4-5.8 0-1.3.5-2.3 1.2-3.2-.1-.3-.5-1.5.1-3.1 0 0 1-.3 3.3 1.2a11.4 11.4 0 0 1 6 0C17.6 4 18.5 4.3 18.5 4.3c.7 1.6.2 2.8.1 3.1.8.8 1.2 1.9 1.2 3.2 0 4.5-2.7 5.5-5.4 5.8.5.4.8 1.1.8 2.3v3.4c0 .3.2.7.8.6 4.6-1.5 7.9-5.8 7.9-10.9C23.5 5.7 18.3.5 12 .5z"/>
    </svg>
  );
}
