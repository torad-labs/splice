// The names this page owns, taken over from the baseline the coverage wall (row M1-04) reads.
//
// `/api/models` was `pending: M2-06` in the baseline. The route does not exist yet, so the
// disposition is `pending` and `where` names the v0.4.0 row that will serve it (CONTRACTS.md
// section 4: in a page's own coverage.ts, `where` names the V4 row, not the page row).
//
// The page renders the honest empty naming that row, never a catalog it made up.
import type { Disposition } from '@shared/coverage';

export const dispositions: readonly Disposition[] = [
  { kind: 'route', name: '/api/models', disposition: 'pending', where: 'V4-127' },
];
