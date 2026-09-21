// This page's dispositions (CONTRACTS.md section 4). The baseline files these four
// under M2-03; a page declaration overrides it, so they are declared where the page
// that reads them lives.
//
// /api/logs/{head} is declared by the LOGS page, not here, even though this page's own baseline
// group names it: the wall allows exactly one page declaration per name.
//
// /api/heads and /api/heads/{head}/capture are deliberately NOT declared here: the
// page reads them (the in-flight bay and the request drawer), but each name has
// exactly one owner everywhere, and those two belong to the fleet page and the MCP
// and Doctor row. Two page declarations for one name fail the wall by design.
import type { Disposition } from '@shared/coverage';

export const dispositions: Disposition[] = [
  { kind: 'route', name: '/api/perf', disposition: 'read-only' },
  { kind: 'route', name: '/api/perf/summary', disposition: 'read-only' },
  { kind: 'route', name: '/api/perf/turns', disposition: 'pending', where: 'V4-127' },
];
