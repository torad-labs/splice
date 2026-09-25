// The names this page owns, taken over from the baseline the coverage wall (row M1-04) reads.
//
// `/api/models` was `pending: M2-06` in the baseline and then `pending: V4-127` here. V4-127 serves
// it, and the page reads the catalog and never writes it (M4-06).
//
// `/api/add-model` is `splice add-model` over HTTP (V4-220): GET lists each OpenRouter head with the
// catalogue rows its roster does not reach yet, POST adds the picked ids and takes the restart.
import type { Disposition } from '@shared/coverage';

export const dispositions: readonly Disposition[] = [
  { kind: 'route', name: '/api/models', disposition: 'read-only' },
  { kind: 'route', name: '/api/add-model', disposition: 'editable' },
];
