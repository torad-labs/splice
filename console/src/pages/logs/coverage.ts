// This page's dispositions (CONTRACTS.md section 4). The baseline files /api/logs/{head} under
// M2-03; a page declaration overrides it, so it is declared where the page that reads it lives.
//
// /api/heads/{head}/capture is deliberately NOT declared here: the page opens the request drawer,
// which reads and writes it, but each name has exactly one owner everywhere and that route belongs
// to the turns page (M4-04). Two page declarations for one name fail the wall by design.
import type { Disposition, PageJob } from '@shared/coverage';

export const dispositions: Disposition[] = [
  { kind: 'route', name: '/api/logs/{head}', disposition: 'read-only' },
  // The CLI verbs this page answers (V4-219: every CLI capability has a console answer; CommandParser.kt).
  { kind: 'verb', name: 'logs', disposition: 'read-only' },
];

/** What this page is for (V4-219, rendered into docs/design/JOBS.md). */
export const job: PageJob = {
  question: 'What did a head just write to its log?',
  leaves: 'The tail of a head\'s log, filtered by tag, level and text, and whether the log rotated.',
  actions: [
    { name: 'Pick a head and how many lines' },
    { name: 'Filter by tag, level or text' },
    { name: 'Turn a head\'s request capture on or off' },
  ],
};
