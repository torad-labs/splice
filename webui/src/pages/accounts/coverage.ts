// The names this page owns, taken over from the baseline the coverage wall (row M1-04) reads.
// Each one is the route's normalized path, exactly as `denominator.ts` parses it out of
// FEATURES.md: method prefix stripped, no query, no glob.
//
// All seven were `pending: M2-04` in the baseline; this file is the row honouring that. The
// disposition says what the console does with the route, not what the daemon does with it.
import type { Disposition } from '@shared/coverage';

export const dispositions: readonly Disposition[] = [
  // Read per head: kind, whether a credential is present, the masked account id, last refresh,
  // the credential path and any refresh latch. The console shows it and never writes it.
  { kind: 'route', name: '/api/auth', disposition: 'read-only' },
  { kind: 'route', name: '/api/auth/{head}/refresh', disposition: 'editable' },
  // Start a login and poll it. Editable for the start, read-only for the poll: polling a login
  // you already started changes nothing.
  { kind: 'route', name: '/api/auth/{head}/login', disposition: 'editable' },
  { kind: 'route', name: '/api/auth/{head}/login/{id}', disposition: 'read-only' },
  // The manual pin. Editable, with the honest caveat printed on the strip: selection happens
  // between turns, so this pins what comes next and moves nothing mid-turn.
  { kind: 'route', name: '/api/auth/{head}/switch', disposition: 'editable' },
  // DELETE and PATCH on one pooled account: remove and relabel.
  { kind: 'route', name: '/api/auth/{kind}/accounts/{label}', disposition: 'editable' },
  // The pooled view. Read-only: the pool is the daemon's, and the page marks what the selector
  // will take rather than steering it.
  { kind: 'route', name: '/api/accounts', disposition: 'read-only' },
];
