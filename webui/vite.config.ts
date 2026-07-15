import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { viteSingleFile } from 'vite-plugin-singlefile';
import { fileURLToPath } from 'node:url';

// Single inlined HTML — the control server serves dist/index.html at / (and
// /dashboard) with no frontend toolchain. Fonts (Plex Mono woff2) inline as
// data URIs via the assetsInlineLimit; serif faces fall back to system serif
// to keep it small.
export default defineConfig({
  plugins: [react(), viteSingleFile()],
  resolve: {
    alias: {
      '@app': fileURLToPath(new URL('./src/app', import.meta.url)),
      '@pages': fileURLToPath(new URL('./src/pages', import.meta.url)),
      '@widgets': fileURLToPath(new URL('./src/widgets', import.meta.url)),
      '@features': fileURLToPath(new URL('./src/features', import.meta.url)),
      '@entities': fileURLToPath(new URL('./src/entities', import.meta.url)),
      '@shared': fileURLToPath(new URL('./src/shared', import.meta.url)),
    },
  },
  build: {
    assetsInlineLimit: 1024 * 1024,
    chunkSizeWarningLimit: 4096,
  },
  server: {
    // dev-mode only: the built artifact is served same-origin by the control server
    proxy: { '/api': `http://127.0.0.1:${process.env.SPLICE_CONTROL_PORT ?? '3096'}` },
  },
  test: {
    environment: 'node',
    include: ['tests/**/*.test.ts'],
  },
});
