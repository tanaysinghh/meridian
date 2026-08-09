/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx,ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // === Meridian palette — v2 ===
        // Anchored on the brand blue #035BD6. Dark editorial dashboard
        // (Linear/Vercel/Raycast family). Blue is the ONLY accent — used
        // deliberately, not decoratively.
        bg:      '#08090b', // page background
        bg2:     '#0f1013', // elevated (sidebar active, section variety)
        bg3:     '#16181d', // higher elevated (modals, popovers)

        // "panel" now means a dark card, not the old cream.
        panel:   '#0d0e11', // default card surface
        panel2:  '#14161b', // hover / secondary card

        // Borders — subtle on the near-black; second tier for stronger seams
        line:    '#1e2027',
        line2:   '#2a2d36',

        // Text tokens. `ink*` and `onbg*` both point at the same values
        // so legacy component code keeps working after the dark-flip.
        ink:     '#f5f6f8',
        ink2:    '#9ba1ad',
        ink3:    '#5c626e',
        onbg:    '#f5f6f8',
        onbg2:   '#9ba1ad',
        onbg3:   '#5c626e',

        // Risk tiers — tuned for legibility on dark panels
        tier: {
          low:      '#10b981',
          medium:   '#eab308',
          high:     '#f97316',
          critical: '#ef4444'
        },

        // === The anchor ===
        accent:   '#035BD6', // brand blue
        accent2:  '#1d6ee8', // hover / brighter
        accent3:  '#0a4bab'  // pressed / deeper
      },
      fontFamily: {
        sans:   ['"IBM Plex Sans"', 'ui-sans-serif', 'system-ui'],
        mono:   ['"IBM Plex Mono"', 'ui-monospace', 'monospace'],
        serif:  ['"Instrument Serif"', 'Georgia', 'serif']
      },
      borderRadius: { DEFAULT: '0', sm: '0', md: '0', lg: '0', xl: '0', full: '0' },
      boxShadow: {
        soft:   '0 0 0 1px #1e2027',
        lift:   '0 12px 32px -12px rgba(0, 0, 0, 0.60), 0 0 0 1px #1e2027',
        pop:    '0 24px 56px -20px rgba(0, 0, 0, 0.75), 0 0 0 1px #2a2d36',
        marine: '0 8px 24px -12px rgba(0, 0, 0, 0.55), 0 0 0 1px #2a2d36',
        // per-tier glows — subtle rings for pills
        'glow-low':      '0 0 0 1px rgba(16,185,129,0.35)',
        'glow-medium':   '0 0 0 1px rgba(234,179,8,0.35)',
        'glow-high':     '0 0 0 1px rgba(249,115,22,0.35)',
        'glow-critical': '0 0 0 1px rgba(239,68,68,0.35)',
        // blue accent glow — reserved for the one CTA per view
        'accent-glow':   '0 0 0 1px rgba(3,91,214,0.55), 0 8px 28px -8px rgba(3,91,214,0.55)'
      },
      backgroundImage: {
        'grad-night':    'linear-gradient(180deg, #08090b 0%, #0b0d11 100%)',
        'grad-panel':    'linear-gradient(180deg, #0d0e11 0%, #0a0b0e 100%)',
        // Kept for back-compat but repointed to the brand blue — no more amber.
        'grad-accent':   'linear-gradient(90deg, #035BD6 0%, #1d6ee8 100%)',
        'grad-signature':'linear-gradient(135deg, #035BD6 0%, #1d6ee8 100%)',
        // tier gradients — kept muted; single-hue rather than rainbow
        'grad-critical': 'linear-gradient(90deg, #ef4444 0%, #b91c1c 100%)',
        'grad-high':     'linear-gradient(90deg, #f97316 0%, #c2410c 100%)',
        'grad-medium':   'linear-gradient(90deg, #eab308 0%, #a16207 100%)',
        'grad-low':      'linear-gradient(90deg, #10b981 0%, #047857 100%)'
      },
      keyframes: {
        shimmer: {
          '0%':   { backgroundPosition: '-400px 0' },
          '100%': { backgroundPosition: '400px 0' }
        },
        pulseGlow: {
          '0%, 100%': { opacity: 0.6 },
          '50%':      { opacity: 1 }
        }
      },
      animation: {
        shimmer:   'shimmer 1.4s linear infinite',
        pulseGlow: 'pulseGlow 2.4s ease-in-out infinite'
      }
    }
  },
  plugins: []
};
