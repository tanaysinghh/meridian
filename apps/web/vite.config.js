import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => ({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:4000', changeOrigin: true, rewrite: p => p.replace(/^\/api/, '') }
    }
  },
  // Strip debug console calls from the production bundle. .error / .warn kept.
  esbuild: mode === 'production'
    ? { drop: ['debugger'], pure: ['console.log', 'console.debug'] }
    : undefined,
  build: {
    sourcemap: mode !== 'production'
  }
}));
