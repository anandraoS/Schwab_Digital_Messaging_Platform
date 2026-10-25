/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// The API port is read from the environment so the console can talk to a backend started on any port
// (`API_PORT=18480 npm run dev`). Default matches `scripts/run-local.sh` (18080).
const apiPort = Number(process.env.API_PORT ?? 18080);
const target = `http://127.0.0.1:${apiPort}`;
const proxy = {
  '/api': { target, changeOrigin: true },
  '/dev': { target, changeOrigin: true },
  '/actuator': { target, changeOrigin: true },
};

export default defineConfig({
  plugins: [react()],
  server: { port: 15200, strictPort: true, host: '127.0.0.1', proxy },
  preview: { port: 15200, strictPort: true, host: '127.0.0.1', proxy },
  build: { sourcemap: false, chunkSizeWarningLimit: 600 },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    restoreMocks: true,
    // lazy route chunks are transformed on first use; allow for a loaded machine
    testTimeout: 20_000,
    hookTimeout: 20_000,
  },
});
