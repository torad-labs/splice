import { defineConfig } from 'vitest/config';
import type { Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import { viteSingleFile } from 'vite-plugin-singlefile';
import { fingerprint } from './src/lib/stale-page.ts';

export const pageFingerprintPlugin: Plugin = {
  name: 'splice-page-fingerprint',
  enforce: 'post',
  generateBundle: {
    order: 'post',
    async handler(_options, bundle) {
      const html = bundle['index.html'];
      if (html?.type !== 'asset' || typeof html.source !== 'string' || !html.source.includes('</head>')) {
        throw new Error('the inlined console document is missing');
      }
      if (/<meta name="splice-page-fingerprint" content="[a-f0-9]{64}">/.test(html.source)) {
        throw new Error('the inlined console document already contains a fingerprint tag');
      }
      if (html.source.indexOf('</head>') !== html.source.lastIndexOf('</head>')) {
        throw new Error('the inlined console document contains more than one head close');
      }
      const identity = await fingerprint(html.source);
      html.source = html.source.replace('</head>', `<meta name="splice-page-fingerprint" content="${identity}"></head>`);
    },
  },
};

// One inlined HTML: the daemon embeds dist/index.html and serves it at / with no frontend toolchain,
// the way it serves the console this one replaces. Fonts inline as data URIs.
export default defineConfig({
  plugins: [react(), viteSingleFile(), pageFingerprintPlugin],
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
    include: ['tests/**/*.test.{ts,tsx}'],
  },
});
