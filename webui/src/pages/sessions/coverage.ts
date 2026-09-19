// This page's dispositions (CONTRACTS.md section 4). A page declaration
// overrides the baseline for the same name, so the four routes the sessions
// page reads are declared here rather than left to the baseline's guess about
// which row would cover them.
//
// /api/sessions is read-only: the console never writes the registry, and the
// daemon owns the files behind it. The other three are the V4-130 rows the
// library orchestrator is building; until they land the page prints the honest
// empty that names that row.
import type { Disposition } from '@shared/coverage';

export const dispositions: Disposition[] = [
  { kind: 'route', name: '/api/sessions', disposition: 'read-only' },
  { kind: 'route', name: '/api/sessions/{id}/repo', disposition: 'pending', where: 'V4-130' },
  { kind: 'route', name: '/api/sessions/{id}/transcript', disposition: 'pending', where: 'V4-130' },
  { kind: 'route', name: '/api/sessions/{id}/edges', disposition: 'pending', where: 'V4-130' },
  { kind: 'route', name: '/api/sessions/edges', disposition: 'pending', where: 'V4-130' },
];
