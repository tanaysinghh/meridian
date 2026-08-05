/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx,ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // === Meridian palette ===
        // Obsidian-violet base + warm ivory panels + electric amber accent.
        // Warm/cool tension is the whole point.
        bg:      '#0f0c17',   // near-black with violet undertone
        bg2:     '#17131f',   // elevated: nav active, section variety
        bg3:     '#211a2d',   // secondary elevated
        panel:   '#f8f4ec',   // warm ivory cream cards
        panel2:  '#efe9dc',   // hover / secondary
        line:    '#d8cebb',   // subtle warm line on panels
        line2:   '#2a2135',   // line on dark bg
        // Text on ivory panels
        ink:     '#1a1424',
        ink2:    '#4a4152',
        ink3:    '#86798e',
        // Text on obsidian bg
        onbg:    '#f4efe4',
        onbg2:   '#a89dae',
        onbg3:   '#6c6178',
        // Risk tiers — warm temperature scale
        tier: {
          low:      '#2fa77e',   // soft mint
          medium:   '#e88b1a',   // amber
          high:     '#e6552a',   // coral
          critical: '#d92e58'    // hot rose
        },
        accent:   '#f5b544',   // electric amber — signature
        accent2:  '#ff8a5a'    // warm coral — used in gradients
      },
      fontFamily: {
        sans:   ['"IBM Plex Sans"', 'ui-sans-serif', 'system-ui'],
        mono:   ['"IBM Plex Mono"', 'ui-monospace', 'monospace'],
        serif:  ['"Instrument Serif"', 'Georgia', 'serif']
      },
      borderRadius: { DEFAULT: '0', sm: '0', md: '0', lg: '0', xl: '0' },
      boxShadow: {
        soft:   '0 1px 2px rgba(10, 6, 20, 0.08), 0 0 0 1px #d8cebb',
        lift:   '0 12px 32px -12px rgba(10, 6, 20, 0.22), 0 3px 8px rgba(10, 6, 20, 0.10), 0 0 0 1px #d8cebb',
        pop:    '0 24px 56px -20px rgba(10, 6, 20, 0.38), 0 6px 12px rgba(10, 6, 20, 0.14), 0 0 0 1px #c9bda5',
        // ambient panel glow on obsidian
        marine: '0 8px 24px -12px rgba(0, 0, 0, 0.55), 0 0 0 1px #2a2135',
        // per-tier glows for pills — tuned for cream panel bg
        'glow-low':      '0 0 0 1px #2fa77e40, 0 4px 12px -2px #2fa77e35',
        'glow-medium':   '0 0 0 1px #e88b1a40, 0 4px 12px -2px #e88b1a35',
        'glow-high':     '0 0 0 1px #e6552a40, 0 4px 12px -2px #e6552a35',
        'glow-critical': '0 0 0 1px #d92e5840, 0 4px 12px -2px #d92e5835',
        // accent CTA glow
        'accent-glow':   '0 0 0 1px #f5b54455, 0 8px 28px -8px #f5b54470'
      },
      backgroundImage: {
        'grad-night':    'linear-gradient(135deg, #0f0c17 0%, #17131f 55%, #221a30 100%)',
        'grad-panel':    'linear-gradient(180deg, #ffffff 0%, #f8f4ec 100%)',
        'grad-accent':   'linear-gradient(135deg, #f5b544 0%, #ff8a5a 100%)',
        'grad-signature':'linear-gradient(135deg, #f5b544 0%, #ff8a5a 45%, #e5406b 100%)',
        'grad-critical': 'linear-gradient(135deg, #d92e58 0%, #ff6b8a 100%)',
        'grad-high':     'linear-gradient(135deg, #e6552a 0%, #ff8f6a 100%)',
        'grad-medium':   'linear-gradient(135deg, #e88b1a 0%, #ffc061 100%)',
        'grad-low':      'linear-gradient(135deg, #2fa77e 0%, #6ad9b0 100%)'
      },
      keyframes: {
        shimmer: {
          '0%':   { backgroundPosition: '-400px 0' },
          '100%': { backgroundPosition: '400px 0' }
        },
        'orb-drift': {
          '0%, 100%': { transform: 'translate(0, 0) scale(1)' },
          '33%':      { transform: 'translate(30px, -20px) scale(1.06)' },
          '66%':      { transform: 'translate(-20px, 20px) scale(0.96)' }
        },
        'orb-drift-2': {
          '0%, 100%': { transform: 'translate(0, 0) scale(1)' },
          '50%':      { transform: 'translate(-40px, 30px) scale(1.1)' }
        },
        pulseGlow: {
          '0%, 100%': { opacity: 0.6 },
          '50%':      { opacity: 1 }
        }
      },
      animation: {
        shimmer:       'shimmer 1.4s linear infinite',
        'orb-drift':   'orb-drift 18s ease-in-out infinite',
        'orb-drift-2': 'orb-drift-2 22s ease-in-out infinite',
        pulseGlow:     'pulseGlow 2.4s ease-in-out infinite'
      }
    }
  },
  plugins: []
};
