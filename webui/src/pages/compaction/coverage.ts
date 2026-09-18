// The names this page owns, taken over from the baseline the coverage wall (row M1-04) reads.
//
// `/api/compact` was `pending: M2-06` in the baseline; this file is the row honouring that. The
// disposition says what the CONSOLE does with the route: it reads the outcome totals and the event
// tail and never writes either.
//
// FEATURES.md 4.10 also lists "Effective instructions | route", and there is NO route for it in
// section 6 — no v0.4.0 row serves it, so nothing here can name a pending row for it. The page
// therefore does not claim it; the gap is recorded on the M2-06 ledger note.
import type { Disposition } from '@shared/coverage';

export const dispositions: readonly Disposition[] = [
  { kind: 'route', name: '/api/compact', disposition: 'read-only' },
];
