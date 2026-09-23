// This page's dispositions (CONTRACTS.md section 4). A page declaration
// overrides the baseline for the same name, so the routes the sessions page
// reads are declared here rather than left to the baseline's guess about which
// row would cover them.
//
// /api/sessions is read-only: the console never writes the registry, and the
// daemon owns the files behind it. The two edge routes are served (V4-130) and
// read-only (M4-05): the board reads every session's edges in one request for
// its peer column, and the opened session's own edges for its hand-offs bay.
// The transcript is served and read-only too; the per-session repo route was never built,
// because the repo became a field on /api/sessions (M4-06).
import type { Disposition } from '@shared/coverage';

export const dispositions: Disposition[] = [
  { kind: 'route', name: '/api/sessions', disposition: 'read-only' },
  // Never a route: FEATURES.md section 6 left the shape open and the 2026-09-18 decision made the repo
  // a FIELD on /api/sessions (entities/session SessionRow.repo), so nothing will ever serve this.
  {
    kind: 'route',
    name: '/api/sessions/{id}/repo',
    disposition: 'excluded',
    reason: 'the repo is a field on /api/sessions (FEATURES.md 6, decided 2026-09-18); this route is never served',
  },
  { kind: 'route', name: '/api/sessions/{id}/transcript', disposition: 'read-only' },
  { kind: 'route', name: '/api/sessions/{id}/edges', disposition: 'read-only' },
  { kind: 'route', name: '/api/sessions/edges', disposition: 'read-only' },
];
