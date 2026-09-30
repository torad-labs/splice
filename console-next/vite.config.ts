import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { viteSingleFile } from 'vite-plugin-singlefile';

// One inlined HTML: the daemon embeds dist/index.html and serves it at / with no frontend toolchain,
// the way it serves the console this one replaces. Fonts inline as data URIs.
export default defineConfig({
  plugins: [react(), viteSingleFile()],
  build: {
    assetsInlineLimit: 1024 * 1024,
    chunkSizeWarningLimit: 4096,
  },
  server: {
    // dev only: the built page is served same-origin by the daemon
    proxy: Object.fromEntries(
      ['/api', '/health'].map((path) => [path, `http://127.0.0.1:${process.env.SPLICE_CONTROL_PORT ?? '3096'}`]),
    ),
  },
  test: {
    environment: 'node',
    include: ['tests/**/*.test.ts'],
  },
});
