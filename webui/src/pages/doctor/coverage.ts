// The names this page owns, taken over from the baseline the coverage wall (row M1-04) reads. A
// name carries exactly ONE page disposition and the wall fails on two, so this row's seven split
// between this file and pages/mcp/coverage.ts: `/api/mcp` is declared there, the other six here.
import type { Disposition } from '@shared/coverage';

export const dispositions: readonly Disposition[] = [
  // The report and the upgrade status. Read-only, and both pending V4-127.
  { kind: 'route', name: '/api/doctor', disposition: 'read-only' },
  { kind: 'route', name: '/api/upgrade', disposition: 'read-only' },
  // Still a row. Its title names GET/PUT /api/heads/{head}/capture, but this row builds no control
  // for it, so it stays pending and the page prints the honest empty that names V4-133.
  { kind: 'route', name: '/api/heads/{head}/capture', disposition: 'pending', where: 'V4-133' },
  // Budgets and alerts are read AND written from the features this row ships.
  { kind: 'route', name: '/api/budgets', disposition: 'editable' },
  { kind: 'route', name: '/api/alerts', disposition: 'editable' },
  // One prompt through one head. Editable, and never recorded.
  { kind: 'route', name: '/api/playground', disposition: 'editable' },
];
