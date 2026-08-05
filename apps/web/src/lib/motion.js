// Shared framer-motion variants and easings — one source of truth for
// timing so the whole app moves with the same character.

export const ease = [0.22, 1, 0.36, 1];      // out-expo-ish, our house curve

export const fadeUp = {
  hidden:  { opacity: 0, y: 12 },
  visible: { opacity: 1, y: 0, transition: { duration: 0.5, ease } }
};

export const stagger = (delayChildren = 0.05) => ({
  hidden:  {},
  visible: { transition: { staggerChildren: 0.06, delayChildren } }
});

export const pageTransition = {
  initial:  { opacity: 0, y: 6 },
  animate:  { opacity: 1, y: 0, transition: { duration: 0.32, ease } },
  exit:     { opacity: 0, y: -4, transition: { duration: 0.18, ease } }
};

export const hoverLift = {
  whileHover: { y: -2, transition: { duration: 0.22, ease } }
};
