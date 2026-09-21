// The names this page owns, taken over from the baseline the coverage wall (row M1-04) reads. Each
// is the route's normalized path, exactly as `denominator.ts` parses it out of FEATURES.md:
// method prefix stripped, no query, no glob.
//
// `/api/daemon/restart` is the odd one: it is in this row's baseline group because a lifecycle row
// owns it (FEATURES.md 4.2 "Daemon restart drains turns first"), but it is still V4-74, so the page
// renders an honest empty for it rather than a control that would 404.
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
  // Still a row: the page prints the honest empty that names it.
  { kind: 'route', name: '/api/daemon/restart', disposition: 'pending', where: 'V4-74' },
  { kind: 'route', name: '/api/usage', disposition: 'read-only' },
];
