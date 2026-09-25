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
  { kind: 'verb', name: 'perf', disposition: 'read-only' },
  { kind: 'verb', name: 'wire', disposition: 'excluded', reason: 'request bodies carry the operator\'s prompts and code; the console switches capture and never prints a body' },
  { kind: 'verb', name: 'trace', disposition: 'excluded', reason: 'a trace carries the request and response bodies; the console switches capture and never prints a body' },
];

/** What this page is for (V4-219, rendered into docs/design/JOBS.md). */
export const job: PageJob = {
  question: 'How fast are the heads answering, and where does a turn\'s time go?',
  leaves: 'Every landed turn with its timing split, cache hit and tokens, and the turns in flight now.',
  actions: [
    { name: 'Open a turn\'s waterfall' },
    { name: 'Turn a head\'s request capture on or off' },
    { name: 'Start a stopped head' },
  ],
};
