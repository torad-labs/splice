// The console's end-to-end leg: every page, in a real browser, against a live daemon on loopback
// (FEATURES.md 7.5). e2e/stack.ts boots the daemon; e2e/console.spec.ts is the suite.
//
// TWO PROJECTS, IN ORDER. `renders` opens every page and holds it for the full settle (two ticks of the
// slowest poll); each is read-only and its faults are its own page's (watch() listens per page), so on
// CI they run in parallel. `journeys` is everything else, the writes among them (a team save, a
// capture, the refused restarts), and runs only once every render has finished, in file order, one
// worker, so no write lands while a page is being judged. The suite ran as one worker until
// 2026-09-25, when its 14 serial settles were 158 s of the gate's 20 minutes; the control plane has
// no concurrency cap for parallel reads to trip (app/control). This box keeps one worker: its
// browsers share the machine with the gate's JVMs.
import { defineConfig, devices } from '@playwright/test';

const RENDERS = /renders against the live daemon$/;

export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  workers: process.env.CI ? 4 : 1,
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
  projects: [
    { name: 'renders', grep: RENDERS, fullyParallel: true },
    { name: 'journeys', grepInvert: RENDERS, dependencies: ['renders'] },
  ],
});
