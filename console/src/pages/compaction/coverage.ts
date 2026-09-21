// The names this page owns, taken over from the baseline the coverage wall (row M1-04) reads.
//
// `/api/compact` was `pending: M2-06` in the baseline; this file is the row honouring that. The
// disposition says what the CONSOLE does with the route: it reads the outcome totals and the event
// tail and never writes either.
//
// FEATURES.md 4.10 also lists "Effective instructions | route": section 6 serves it with
// GET /api/compaction/instructions (V4-136, decided 2026-09-18), pending until that daemon row
// lands; the page names the row and claims 4.10 once the route answers.
import type { Disposition } from '@shared/coverage';

export const dispositions: readonly Disposition[] = [
  { kind: 'route', name: '/api/compact', disposition: 'read-only' },
  { kind: 'route', name: '/api/compaction/instructions', disposition: 'pending', where: 'V4-136' },
];
