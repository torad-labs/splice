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
import type { Disposition, PageJob } from '@shared/coverage';

export const dispositions: Disposition[] = [
  { kind: 'route', name: '/api/perf', disposition: 'read-only' },
  { kind: 'route', name: '/api/perf/summary', disposition: 'read-only' },
  { kind: 'route', name: '/api/perf/turns', disposition: 'read-only' },
  { kind: 'route', name: '/api/heads/{head}/capture', disposition: 'editable' },
  // The CLI verbs this page answers (V4-219: every CLI capability has a console answer; CommandParser.kt).
  { kind: 'verb', name: 'perf', disposition: 'read-only', via: '/api/perf/summary' },
  // splice-lead, 2026-09-25 (V4-239): the console reads what `splice wire` and `splice trace` print,
  // for the heads whose operator turned capture on; this supersedes their earlier exclusion.
  { kind: 'verb', name: 'wire', disposition: 'pending', where: 'V4-239', action: 'Read a head\'s captured request bodies' },
  { kind: 'verb', name: 'trace', disposition: 'pending', where: 'V4-239', action: 'Read a head\'s request and response trace' },
];

/** What this page is for (V4-219, rendered into docs/design/JOBS.md). */
export const job: PageJob = {
  question: 'How fast are the heads answering, and where does a turn\'s time go?',
  leaves: 'Every landed turn with its timing split, cache hit and tokens, and the turns in flight now.',
  actions: [
    { name: 'Open a turn\'s waterfall' },
    { name: 'Turn a head\'s request capture on or off' },
    { name: 'Start a stopped head' },
    { name: 'Read a head\'s captured request bodies', row: 'V4-239' },
    { name: 'Read a head\'s request and response trace', row: 'V4-239' },
  ],
};
