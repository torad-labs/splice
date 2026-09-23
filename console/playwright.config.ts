// The console's end-to-end leg: every page, in a real browser, against a live daemon on loopback
// (FEATURES.md 7.5). e2e/stack.ts boots the daemon; e2e/console.spec.ts is the suite. One worker,
// because every test reads the same daemon and a page's polls must not race another page's.
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  workers: 1,
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  timeout: 60_000,
  reporter: [['list']],
  outputDir: './e2e/.results',
  use: {
    ...devices['Desktop Chrome'],
    // The size the operator's captures and the comps are measured at.
    viewport: { width: 1536, height: 1024 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
});
