// This page's dispositions (CONTRACTS.md section 4). A page declaration
// overrides the baseline for the same name, so the routes the sessions page
// reads are declared here rather than left to the baseline's guess about which
// row would cover them.
//
// /api/sessions is read-only: the console never writes the registry, and the
// daemon owns the files behind it. The two edge routes are served (V4-130) and
// read-only (M4-05): the board reads every session's edges in one request for
// its peer column, and the opened session's own edges for its hand-offs bay.
// The other two are still named for the V4-130 rows that were building them.
import type { Disposition } from '@shared/coverage';

export const dispositions: Disposition[] = [
  { kind: 'route', name: '/api/sessions', disposition: 'read-only' },
  { kind: 'route', name: '/api/sessions/{id}/repo', disposition: 'pending', where: 'V4-130' },
  { kind: 'route', name: '/api/sessions/{id}/transcript', disposition: 'pending', where: 'V4-130' },
  { kind: 'route', name: '/api/sessions/{id}/edges', disposition: 'read-only' },
  { kind: 'route', name: '/api/sessions/edges', disposition: 'read-only' },
];
