import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In dev, proxy /api to the running API so the browser stays same-origin
// (no CORS configuration needed on the server). In production the built assets
// are served by nginx, which proxies /api to the API container the same way.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.VITE_API_TARGET || 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: { outDir: 'dist' },
});
