// This page's dispositions (CONTRACTS.md section 4). The baseline files /api/logs/{head} under
// M2-03; a page declaration overrides it, so it is declared where the page that reads it lives.
//
// /api/heads/{head}/capture is deliberately NOT declared here: the page opens the request drawer,
// which reads it, but each name has exactly one owner everywhere and that route belongs to the MCP
// and Doctor row. Two page declarations for one name fail the wall by design.
import type { Disposition } from '@shared/coverage';

export const dispositions: Disposition[] = [
  { kind: 'route', name: '/api/logs/{head}', disposition: 'read-only' },
];
