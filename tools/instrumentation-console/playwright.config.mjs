import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: '.', testMatch: 'console.spec.mjs', fullyParallel: false, workers: 1,
  use: { baseURL: 'http://127.0.0.1:4319', viewport: { width: 1440, height: 900 }, permissions: [] },
  webServer: { command: 'SPLICE_CONSOLE_FIXTURE_PORT=4319 node tools/instrumentation-console/preview.mjs', url: 'http://127.0.0.1:4319/preview.html', reuseExistingServer: false },
});
