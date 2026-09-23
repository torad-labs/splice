// The names this page owns, taken over from the baseline the coverage wall (row M1-04) reads.
//
// `/api/compact` was `pending: M2-06` in the baseline; this file is the row honouring that. The
// disposition says what the CONSOLE does with the route: it reads the outcome totals and the event
// tail and never writes either.
//
// FEATURES.md 4.10 also lists "Effective instructions | route": section 6 serves it with
// GET /api/compaction/instructions (V4-136, decided 2026-09-18). The page reads it for every head
// and prints the rules in effect (M4-05), and never writes: the rules live in splice.toml.
import type { Disposition } from '@shared/coverage';

export const dispositions: readonly Disposition[] = [
  { kind: 'route', name: '/api/compact', disposition: 'read-only' },
  { kind: 'route', name: '/api/compaction/instructions', disposition: 'read-only' },
];
