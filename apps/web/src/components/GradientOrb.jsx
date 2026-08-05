// Animated blurred orbs behind the landing hero — amber, coral, and rose
// on the obsidian bg. Softly drift and re-blend to keep the surface alive
// without competing with copy.

export default function GradientOrbs() {
  return (
    <div className="absolute inset-0 overflow-hidden pointer-events-none">
      <div
        className="absolute animate-orb-drift"
        style={{
          top: '-8%', left: '-6%',
          width: 640, height: 640,
          background: 'radial-gradient(circle, rgba(245,181,68,0.42) 0%, rgba(245,181,68,0) 60%)',
          filter: 'blur(20px)'
        }}
      />
      <div
        className="absolute animate-orb-drift-2"
        style={{
          top: '18%', right: '-10%',
          width: 560, height: 560,
          background: 'radial-gradient(circle, rgba(255,138,90,0.32) 0%, rgba(255,138,90,0) 65%)',
          filter: 'blur(30px)'
        }}
      />
      <div
        className="absolute animate-orb-drift"
        style={{
          bottom: '-18%', left: '32%',
          width: 720, height: 720,
          background: 'radial-gradient(circle, rgba(229,64,107,0.24) 0%, rgba(229,64,107,0) 65%)',
          filter: 'blur(40px)',
          animationDelay: '4s'
        }}
      />
    </div>
  );
}
