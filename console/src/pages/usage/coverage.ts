// The names this page owns, taken over from the baseline the coverage wall (row M1-04) reads.
//
// `/api/economics` was `pending: M2-06` in the baseline; this file is the row honouring that. The
// disposition says what the CONSOLE does with the route: it reads the rollup and never writes it.
//
// `/api/models` is deliberately NOT here. The page reads the catalog for the rate cards, but the
// MODELS page is the one that owns the name — the coverage wall fails when two pages declare the
// same name, and a route owned by two pages is a route nobody owns.
import type { Disposition } from '@shared/coverage';

export const dispositions: readonly Disposition[] = [
  { kind: 'route', name: '/api/economics', disposition: 'read-only' },
];
