// NEW: V4-444 — the built replacement console against the existing isolated daemon stack.
import { defineConfig, devices } from '@playwright/test';

const RENDERS = /renders against the live daemon$/;

export default defineConfig({
  testDir: '.',
  globalSetup: './setup.ts',
  // Every journey shares one isolated daemon, config and credential store. Mutations must not race between files.
  workers: 1,
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  timeout: 60_000,
  // Initial daemon reads use the stack's landing bound. Explicit remount/poll timing assertions stay tighter.
  expect: { timeout: 20_000 },
  reporter: [['list']],
  outputDir: '../build/e2e',
  use: {
    ...devices['Desktop Chrome'],
    viewport: { width: 1536, height: 1024 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'renders', grep: RENDERS, fullyParallel: true },
    { name: 'journeys', grepInvert: RENDERS, dependencies: ['renders'] },
  ],
});
