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
import type { Disposition } from '@shared/coverage';

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
];
