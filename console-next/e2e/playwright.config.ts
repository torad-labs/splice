// NEW: V4-444 — the built replacement console against the existing isolated daemon stack.
import { defineConfig, devices } from '@playwright/test';

const RENDERS = /renders against the live daemon$/;
const qualificationWidth = process.env['CONSOLE_E2E_WIDTH'];
if (qualificationWidth !== undefined && !['1440', '390'].includes(qualificationWidth)) throw new Error('CONSOLE_E2E_WIDTH must be 1440 or 390');

export default defineConfig({
  testDir: '.',
  globalSetup: './setup.ts',
  // Every journey shares one isolated daemon, config and credential store. Mutations must not race between files.
  workers: 1,
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  timeout: 60_000,
  reporter: [['list']],
  outputDir: '../build/e2e',
  use: {
    ...devices['Desktop Chrome'],
    viewport: { width: Number(qualificationWidth ?? 1536), height: 1024 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'renders', grep: RENDERS, fullyParallel: true },
    { name: 'journeys', grepInvert: RENDERS, dependencies: ['renders'] },
  ],
});
