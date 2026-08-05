import { Link } from 'react-router-dom';
import { motion } from 'framer-motion';
import { useEffect, useState } from 'react';
import GradientOrbs from '../components/GradientOrb.jsx';
import TierPill from '../components/TierPill.jsx';
import { fadeUp, stagger, ease } from '../lib/motion.js';

export default function Landing() {
  return (
    <div className="min-h-screen bg-bg text-onbg">
      <Nav />
      <Hero />
      <Problem />
      <How />
      <Features />
      <Screenshot />
      <FAQ />
      <Footer />
    </div>
  );
}

/* --- Reveal wrapper: fades + slides on scroll into view --- */
function Reveal({ children, delay = 0, className }) {
  return (
    <motion.div
      className={className}
      initial={{ opacity: 0, y: 20 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, margin: '-80px' }}
      transition={{ duration: 0.6, delay, ease }}
    >
      {children}
    </motion.div>
  );
}

/* --- Logo variants --- */
function LogoLight({ size = 22 }) {
  return (
    <div className="flex items-center gap-2 select-none">
      <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
        <path d="M2 20 L8 6 L12 14 L16 4 L22 20" stroke="#f2f6fb" strokeWidth="1.75" strokeLinejoin="miter" strokeLinecap="square" />
        <path d="M2 20 H22" stroke="#f5b544" strokeWidth="1.25" />
      </svg>
      <span className="font-medium tracking-tight text-onbg" style={{ letterSpacing: '-0.01em' }}>Meridian</span>
    </div>
  );
}

function Nav() {
  const [scrolled, setScrolled] = useState(false);
  useEffect(() => {
    const on = () => setScrolled(window.scrollY > 20);
    on(); window.addEventListener('scroll', on, { passive: true });
    return () => window.removeEventListener('scroll', on);
  }, []);
  return (
    <nav className={`sticky top-0 z-30 transition-all duration-300 ${
      scrolled ? 'bg-bg/85 backdrop-blur-md hairline-bg-b' : 'bg-transparent'
    }`}>
      <div className="mx-auto max-w-6xl px-6 h-14 flex items-center justify-between">
        <LogoLight />
        <div className="flex items-center gap-6 text-sm text-onbg2">
          <a href="#how" className="hover:text-onbg transition-colors">How it works</a>
          <a href="#features" className="hover:text-onbg transition-colors">Features</a>
          <a href="#faq" className="hover:text-onbg transition-colors">FAQ</a>
          <a href="https://github.com/tanaysinghh/meridian" className="hover:text-onbg transition-colors">GitHub</a>
          <Link to="/login"
            className="text-onbg hairline-bg px-3 py-1.5 hover:bg-bg2 hover:shadow-accent-glow transition-all duration-200">
            Sign in
          </Link>
        </div>
      </div>
    </nav>
  );
}

function Hero() {
  return (
    <section className="relative overflow-hidden hero-wash">
      <GradientOrbs />
      <div className="absolute inset-0 grid-bg opacity-30 pointer-events-none" />
      <div className="mx-auto max-w-6xl px-6 pt-20 pb-32 relative grid md:grid-cols-[1.15fr_1fr] gap-16 items-center">
        <motion.div initial="hidden" animate="visible" variants={stagger(0.2)}>
          <motion.div variants={fadeUp}
            className="inline-flex items-center gap-2 text-[11px] mono text-onbg3 tracking-widest uppercase mb-8 hairline-bg px-3 py-1.5 bg-bg/40 backdrop-blur">
            <span className="w-1 h-1 bg-accent animate-pulseGlow" style={{ boxShadow: '0 0 8px #f5b544' }} />
            Review intelligence · v0.1
          </motion.div>
          <motion.h1 variants={fadeUp}
            className="serif text-5xl md:text-7xl leading-[0.98] tracking-tight text-onbg max-w-4xl">
            Know which pull request<br />
            <span className="italic" style={{
              background: 'linear-gradient(135deg, #f5b544 0%, #ff8a5a 50%, #e5406b 100%)',
              WebkitBackgroundClip: 'text', WebkitTextFillColor: 'transparent',
              backgroundClip: 'text'
            }}>will hurt you</span>
            <span className="text-onbg"> — before it merges.</span>
          </motion.h1>
          <motion.p variants={fadeUp}
            className="mt-8 max-w-2xl text-lg text-onbg2 leading-relaxed">
            Meridian scores every PR your team opens for the likelihood it will cause a revert,
            hotfix, or incident. Explainable signals, no black box. Reviewer load balancing,
            post-merge feedback, and an SLA that keeps risky code from sitting overnight.
          </motion.p>
          <motion.div variants={fadeUp} className="mt-10 flex items-center gap-3 flex-wrap">
            <motion.a href="/api/auth/github"
              whileHover={{ y: -2 }}
              transition={{ duration: 0.2, ease }}
              className="bg-grad-accent text-white px-5 py-3 text-sm font-medium inline-flex items-center gap-2 shadow-accent-glow hover:shadow-lg transition-shadow">
              <GhIcon /> Continue with GitHub
            </motion.a>
            <Link to="/login" className="text-sm text-onbg2 px-4 py-3 hover:text-onbg transition-colors">
              Sign in with email →
            </Link>
          </motion.div>
          <motion.div variants={fadeUp} className="mt-4 text-xs text-onbg3 mono">
            GitHub app isn't provisioned yet — the button drops you into a fully-seeded demo org.
          </motion.div>
        </motion.div>

        {/* Live PR ticker — the "product proof" */}
        <motion.div
          initial={{ opacity: 0, x: 20 }}
          animate={{ opacity: 1, x: 0 }}
          transition={{ duration: 0.7, delay: 0.35, ease }}
        >
          <LivePRTicker />
        </motion.div>
      </div>

      {/* Bottom stat strip */}
      <div className="mx-auto max-w-6xl px-6 relative">
        <div className="grid grid-cols-2 md:grid-cols-4 gap-px bg-line2 hairline-bg glass">
          {[
            ['0.42', 'model AUC on synthetic labels'],
            ['4 tiers', 'low · medium · high · critical'],
            ['<200ms', 'p50 score latency'],
            ['SHAP-lite', 'per-PR explainability']
          ].map(([n, l], i) => (
            <motion.div key={l}
              initial={{ opacity: 0, y: 12 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: 0.55 + i * 0.08, duration: 0.5, ease }}
              className="px-6 py-6 hover:bg-bg2/30 transition-colors">
              <div className="serif text-3xl text-onbg">{n}</div>
              <div className="mt-1 text-xs text-onbg3 mono uppercase tracking-wider">{l}</div>
            </motion.div>
          ))}
        </div>
      </div>
    </section>
  );
}

/* --- Live PR ticker: cycles synthetic PR scores every few seconds --- */
const TICKER_PRS = [
  { repo: 'acme/platform-api', title: 'Refactor auth middleware',       author: 'marcus-c', tier: 'critical', score: 0.94 },
  { repo: 'acme/billing',      title: 'Fix Stripe 409 retry double-charge', author: 'sofia-a',  tier: 'high',     score: 0.72 },
  { repo: 'acme/web-app',      title: 'Add reviewer load dashboard',    author: 'kenji-w',  tier: 'medium',   score: 0.48 },
  { repo: 'acme/infra',        title: 'Rotate prod DB creds → vault',   author: 'priya-r',  tier: 'critical', score: 0.91 },
  { repo: 'acme/web-app',      title: 'docs: onboarding copy tweak',    author: 'sofia-a',  tier: 'low',      score: 0.08 },
  { repo: 'acme/platform-api', title: 'Rate-limit /login endpoint',     author: 'priya-r',  tier: 'medium',   score: 0.38 }
];

function LivePRTicker() {
  const [items, setItems] = useState(TICKER_PRS.slice(0, 4));
  const [pulseIdx, setPulseIdx] = useState(0);

  useEffect(() => {
    const iv = setInterval(() => {
      setItems(prev => {
        const next = [TICKER_PRS[Math.floor(Math.random() * TICKER_PRS.length)], ...prev.slice(0, 3)];
        return next;
      });
      setPulseIdx(i => i + 1);
    }, 3400);
    return () => clearInterval(iv);
  }, []);

  return (
    <div className="glass p-4 relative">
      <div className="flex items-center justify-between mb-3">
        <div className="text-xs mono text-onbg2 uppercase tracking-widest flex items-center gap-2">
          <span className="w-1.5 h-1.5 bg-tier-low animate-pulseGlow rounded-full"
            style={{ boxShadow: '0 0 8px #6ad9b0' }} />
          Live · risk scored PRs
        </div>
        <div className="text-[10px] mono text-onbg3">demo stream</div>
      </div>
      <ul className="space-y-1.5">
        {items.map((pr, i) => (
          <motion.li
            key={`${pulseIdx}-${i}`}
            initial={i === 0 ? { opacity: 0, y: -14, scale: 0.98 } : false}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            transition={{ duration: 0.4, ease }}
            className="hairline-bg bg-bg/40 px-3 py-2.5 flex items-center gap-3"
          >
            <TierPill tier={pr.tier} score={pr.score} pulse={i === 0} />
            <div className="flex-1 min-w-0">
              <div className="text-sm text-onbg truncate">{pr.title}</div>
              <div className="text-[10px] mono text-onbg3 truncate">{pr.repo} · {pr.author}</div>
            </div>
          </motion.li>
        ))}
      </ul>
    </div>
  );
}

function Problem() {
  return (
    <section className="hairline-bg-t bg-panel relative">
      <div className="mx-auto max-w-6xl px-6 py-24 grid md:grid-cols-2 gap-12">
        <Reveal>
          <div className="mono text-xs text-ink3 uppercase tracking-widest">The problem</div>
          <h2 className="serif text-4xl md:text-5xl mt-4 leading-tight text-ink">
            The riskiest PR of the week<br />looks just like all the others.
          </h2>
        </Reveal>
        <Reveal delay={0.1}>
          <div className="space-y-6 text-ink2 leading-relaxed">
            <p>
              Modern review tooling treats every PR the same. A one-line typo fix
              and a 400-line rewrite of the auth middleware sit in the same queue,
              competing for the same attention.
            </p>
            <p>
              The result: risky changes get rubber-stamped at 5:57pm on a Friday,
              and the team finds out at 2am when checkout starts 500ing.
            </p>
            <p className="text-ink font-medium">
              Meridian gives you a signal your reviewers can actually use — a
              calibrated risk score, backed by explainable features, so the
              attention lands where it needs to.
            </p>
          </div>
        </Reveal>
      </div>
    </section>
  );
}

function How() {
  const steps = [
    { n: '01', t: 'Ingest',           d: 'A GitHub App streams PR lifecycle events — opens, syncs, reviews, merges — into Meridian.' },
    { n: '02', t: 'Score',            d: 'A LightGBM classifier trained on outcomes returns a probability, tier, and top contributing features per PR.' },
    { n: '03', t: 'Route & escalate', d: 'Rules layer on top of the model — always-critical paths, load-balanced reviewer suggestions, SLA breach alerts.' },
    { n: '04', t: 'Close the loop',   d: 'Post-merge, Meridian tracks reverts, hotfixes, and incidents — feeding real outcomes back into the model.' }
  ];
  return (
    <section id="how" className="hairline-bg-t bg-grad-night relative overflow-hidden">
      <div className="absolute inset-0 opacity-20 grid-bg pointer-events-none" />
      <div className="mx-auto max-w-6xl px-6 py-24 relative">
        <Reveal>
          <div className="mono text-xs text-onbg3 uppercase tracking-widest">How it works</div>
          <h2 className="serif text-4xl md:text-5xl mt-4 text-onbg">A pipeline, not a plugin.</h2>
        </Reveal>
        <div className="mt-12 grid md:grid-cols-4 gap-px bg-line2 hairline-bg">
          {steps.map((s, i) => (
            <motion.div key={s.n}
              initial={{ opacity: 0, y: 24 }}
              whileInView={{ opacity: 1, y: 0 }}
              viewport={{ once: true, margin: '-80px' }}
              transition={{ duration: 0.5, delay: i * 0.08, ease }}
              whileHover={{ y: -4 }}
              className="bg-bg p-8 group cursor-default hover:bg-bg2 transition-colors">
              <div className="mono text-xs text-onbg3 group-hover:text-accent transition-colors">{s.n}</div>
              <div className="mt-6 text-lg text-onbg">{s.t}</div>
              <div className="mt-2 text-sm text-onbg2 leading-relaxed">{s.d}</div>
            </motion.div>
          ))}
        </div>
      </div>
    </section>
  );
}

function Features() {
  const items = [
    ['Explainable risk score', 'Every prediction ships with its top contributing features and a confidence badge for low-data cases.'],
    ['Rules engine on top of ML', 'Force auth/, billing/, terraform/ to always land in the review queue — no matter what the model says.'],
    ['Load-balanced reviewer suggestions', 'Ranks by historical file ownership, then dampens by current open-review count.'],
    ['SLA & auto-escalation', 'High-risk PRs sitting past N hours page the on-call reviewer via Slack.'],
    ['Analytics that matter', 'PR size trends, cycle time breakdown, revert rate, and a merge-time heatmap that surfaces "Friday-night deploys" as a pattern.'],
    ['Post-merge feedback loop', 'Reverts and incidents flow back into pr_outcomes and validate the model over time.'],
    ['Secret leak detection', 'Regex + entropy sweep on every diff — flags AWS keys, private keys, and generic API tokens.'],
    ['Author risk trends', 'Framed constructively for the individual. Pattern-spotting for team leads, not a leaderboard.']
  ];
  return (
    <section id="features" className="hairline-bg-t bg-panel">
      <div className="mx-auto max-w-6xl px-6 py-24">
        <Reveal>
          <div className="mono text-xs text-ink3 uppercase tracking-widest">What's inside</div>
          <h2 className="serif text-4xl md:text-5xl mt-4 text-ink">Everything a review lead wishes their code host shipped.</h2>
        </Reveal>
        <div className="mt-12 grid md:grid-cols-2 gap-px bg-line hairline">
          {items.map(([t, d], i) => (
            <motion.div key={t}
              initial={{ opacity: 0, y: 20 }}
              whileInView={{ opacity: 1, y: 0 }}
              viewport={{ once: true, margin: '-40px' }}
              transition={{ duration: 0.45, delay: (i % 2) * 0.06 + Math.floor(i / 2) * 0.04, ease }}
              whileHover={{ backgroundColor: '#f4f7fc' }}
              className="bg-panel p-8 group">
              <div className="flex items-start gap-3">
                <div className="w-1.5 h-1.5 bg-accent2 mt-2 group-hover:h-6 transition-all duration-300" />
                <div>
                  <div className="text-ink font-medium">{t}</div>
                  <div className="mt-2 text-sm text-ink2 leading-relaxed">{d}</div>
                </div>
              </div>
            </motion.div>
          ))}
        </div>
      </div>
    </section>
  );
}

function Screenshot() {
  return (
    <section className="hairline-bg-t bg-grad-night relative overflow-hidden">
      <div className="mx-auto max-w-6xl px-6 py-24 relative">
        <Reveal>
          <div className="mono text-xs text-onbg3 uppercase tracking-widest">A glimpse</div>
          <h2 className="serif text-4xl md:text-5xl mt-4 text-onbg">Dense, quiet, keyboard-first.</h2>
        </Reveal>
        <Reveal delay={0.15}>
          <div className="mt-10 glass p-3">
            <div className="grid md:grid-cols-3 gap-3">
              <MiniTile tier="critical" title="Rotate prod DB creds" repo="acme/infra" score={0.91} />
              <MiniTile tier="high"     title="Rewrite session token issuance" repo="acme/platform-api" score={0.78} />
              <MiniTile tier="low"      title="Fix onboarding typo" repo="acme/web-app" score={0.09} />
            </div>
          </div>
        </Reveal>
      </div>
    </section>
  );
}

function MiniTile({ tier, title, repo, score }) {
  return (
    <motion.div
      whileHover={{ y: -3 }}
      transition={{ duration: 0.22, ease }}
      className="bg-panel shadow-soft p-5 card-lift hover:shadow-lift"
    >
      <div className="flex items-center justify-between text-xs mono text-ink3">
        <span>{repo}</span>
        <TierPill tier={tier} />
      </div>
      <div className="mt-3 text-ink text-sm">{title}</div>
      <div className="mt-5 h-1 bg-line">
        <motion.div
          initial={{ width: 0 }}
          whileInView={{ width: `${score * 100}%` }}
          viewport={{ once: true }}
          transition={{ duration: 0.9, ease }}
          className="h-1 bg-grad-accent"
        />
      </div>
      <div className="mt-2 text-xs mono text-ink3">score {score.toFixed(2)}</div>
    </motion.div>
  );
}

function FAQ() {
  const qs = [
    ['Is Meridian a linter?', 'No. Linters check the code; Meridian scores the change. A perfectly-formatted 800-line rewrite is still risky.'],
    ['Do you require deep learning?', 'No. LightGBM plus SHAP-style attributions — chosen for explainability, not accuracy chest-thumping.'],
    ['What data do you send off-box?', 'Nothing in local dev. In production, only what your GitHub App is scoped to see. No diff bodies leave your infra unless you opt in.'],
    ['Does it work without incident data?', 'Yes — the model bootstraps on synthetic labels, and confidence starts at "low" until real outcomes accumulate. It gets better as your team uses it.']
  ];
  return (
    <section id="faq" className="hairline-bg-t bg-panel">
      <div className="mx-auto max-w-6xl px-6 py-24 grid md:grid-cols-2 gap-12">
        <Reveal>
          <div className="mono text-xs text-ink3 uppercase tracking-widest">FAQ</div>
          <h2 className="serif text-4xl md:text-5xl mt-4 text-ink">Questions people ask.</h2>
        </Reveal>
        <div className="space-y-8">
          {qs.map(([q, a], i) => (
            <Reveal key={q} delay={i * 0.08}>
              <div className="text-ink font-medium">{q}</div>
              <div className="text-ink2 mt-2 leading-relaxed">{a}</div>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  );
}

function Footer() {
  return (
    <footer className="hairline-bg-t bg-bg">
      <div className="mx-auto max-w-6xl px-6 py-10 flex items-center justify-between text-sm text-onbg3">
        <LogoLight size={18} />
        <div className="mono text-xs">© {new Date().getFullYear()} · built for engineering teams that care about their oncall</div>
      </div>
    </footer>
  );
}

function GhIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
      <path d="M12 .5C5.7.5.5 5.7.5 12c0 5.1 3.3 9.4 7.9 10.9.6.1.8-.3.8-.6v-2c-3.2.7-3.9-1.5-3.9-1.5-.5-1.3-1.3-1.7-1.3-1.7-1-.7.1-.7.1-.7 1.2.1 1.8 1.2 1.8 1.2 1.1 1.8 2.8 1.3 3.5 1 .1-.8.4-1.3.8-1.6-2.6-.3-5.4-1.3-5.4-5.8 0-1.3.5-2.3 1.2-3.2-.1-.3-.5-1.5.1-3.1 0 0 1-.3 3.3 1.2a11.4 11.4 0 0 1 6 0C17.6 4 18.5 4.3 18.5 4.3c.7 1.6.2 2.8.1 3.1.8.8 1.2 1.9 1.2 3.2 0 4.5-2.7 5.5-5.4 5.8.5.4.8 1.1.8 2.3v3.4c0 .3.2.7.8.6 4.6-1.5 7.9-5.8 7.9-10.9C23.5 5.7 18.3.5 12 .5z"/>
    </svg>
  );
}
