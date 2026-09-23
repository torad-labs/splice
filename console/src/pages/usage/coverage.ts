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
  // The two panels this page mounts (features/budgets, features/alerts) read and write these, and
  // the alerts panel sends its test through the third. Moved here from the doctor's file, which
  // declared them for a page that never rendered either panel (M4-06).
  { kind: 'route', name: '/api/budgets', disposition: 'editable' },
  { kind: 'route', name: '/api/alerts', disposition: 'editable' },
  { kind: 'route', name: '/api/alerts/test', disposition: 'editable' },
];
