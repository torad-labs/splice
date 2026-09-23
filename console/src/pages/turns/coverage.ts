// This page's dispositions (CONTRACTS.md section 4). The baseline files these four
// under M2-03; a page declaration overrides it, so they are declared where the page
// that reads them lives.
//
// /api/logs/{head} is declared by the LOGS page, not here, even though this page's own baseline
// group names it: the wall allows exactly one page declaration per name.
//
// /api/heads/{head}/capture is declared HERE and nowhere else: this page's request drawer carries
// the per-head capture switch, which reads the route and writes it (M4-04), so it is editable. The
// logs page opens the same drawer, and each name still has exactly one owner. /api/heads belongs
// to the fleet page, although this page reads it for the in-flight bay.
import type { Disposition } from '@shared/coverage';

export const dispositions: Disposition[] = [
  { kind: 'route', name: '/api/perf', disposition: 'read-only' },
  { kind: 'route', name: '/api/perf/summary', disposition: 'read-only' },
  { kind: 'route', name: '/api/perf/turns', disposition: 'read-only' },
  { kind: 'route', name: '/api/heads/{head}/capture', disposition: 'editable' },
];
