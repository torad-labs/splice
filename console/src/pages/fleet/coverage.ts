// The names this page owns, taken over from the baseline the coverage wall (row M1-04) reads. Each
// is the route's normalized path, exactly as `denominator.ts` parses it out of FEATURES.md:
// method prefix stripped, no query, no glob.
//
// `/api/daemon/restart` is the odd one: it is in this row's baseline group because a lifecycle row
// owns it (FEATURES.md 4.2 "Daemon restart drains turns first"). It was pending V4-74 while the route
// did not exist; it is served now (ControlServer.kt:368) and the head detail writes through it with
// the shared draining-restart control (M4-02). The doctor's upgrade section mounts the same control,
// and a name carries exactly one page disposition, so it is disposed here only.
//
// The fleet also READS /api/accounts for the head detail's pool and /api/auth for the strips; both
// are disposed once, by the accounts page that owns them.
import type { Disposition, PageJob } from '@shared/coverage';

export const dispositions: readonly Disposition[] = [
  // The shell's own reads: the rule bar's server/version and the unauthenticated health probe the
  // topology-stale flag rides on. This page reads both; it writes neither.
  { kind: 'route', name: '/health', disposition: 'read-only' },
  { kind: 'route', name: '/api/status', disposition: 'read-only' },
  // The fleet itself.
  { kind: 'route', name: '/api/heads', disposition: 'read-only' },
  { kind: 'route', name: '/api/heads/{head}/start', disposition: 'editable' },
  { kind: 'route', name: '/api/heads/{head}/stop', disposition: 'editable' },
  { kind: 'route', name: '/api/heads/{head}/restart', disposition: 'editable' },
  // The draining restart, written through from the head detail (features/daemon-restart).
  { kind: 'route', name: '/api/daemon/restart', disposition: 'editable' },
  { kind: 'route', name: '/api/usage', disposition: 'read-only' },
  // The CLI verbs this page answers (V4-219: every CLI capability has a console answer; CommandParser.kt).
  // V4-220: each names its job action and the route that action calls (checks.ts, verbProblems).
  { kind: 'verb', name: 'status', disposition: 'read-only', via: '/api/status' },
  { kind: 'verb', name: 'restart', disposition: 'editable', action: 'Restart the daemon', via: '/api/daemon/restart' },
  { kind: 'verb', name: 'add', disposition: 'pending', where: 'V4-220', action: 'Add a backend', via: '/api/add' },
  // upgrade is the doctor page's: its version strip reads /api/upgrade, and the upgrade form sits there.
  // Adding a backend, `splice add` over HTTP (V4-220 item 3): a new head joins this page's fleet.
  { kind: 'route', name: '/api/add/profiles', disposition: 'read-only' },
  { kind: 'route', name: '/api/add', disposition: 'editable' },
  { kind: 'route', name: '/api/add/{id}', disposition: 'editable' },
  { kind: 'route', name: '/api/add/{id}/login', disposition: 'editable' },
  { kind: 'route', name: '/api/add/{id}/verify', disposition: 'editable' },
  { kind: 'route', name: '/api/add/{id}/save', disposition: 'editable' },
];

/** What this page is for (V4-219, rendered into docs/design/JOBS.md). */
export const job: PageJob = {
  question: 'Is every head up, and which account will each use next?',
  leaves: 'Each head\'s health, pinned model, turns in flight and account pool, with the next target marked.',
  actions: [
    { name: 'Start, stop or restart a head' },
    { name: 'Restart the daemon' },
    { name: 'Add a backend', row: 'V4-220' },
  ],
};
