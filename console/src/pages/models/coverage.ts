// The names this page owns, taken over from the baseline the coverage wall (row M1-04) reads.
//
// `/api/models` was `pending: M2-06` in the baseline and then `pending: V4-127` here. V4-127 serves
// it, and the page reads the catalog and never writes it (M4-06).
//
// `/api/add-model` is `splice add-model` over HTTP (V4-220): GET lists each OpenRouter head with the
// catalogue rows its roster does not reach yet, POST adds the picked ids and takes the restart.
import type { Disposition, PageJob } from '@shared/coverage';

export const dispositions: readonly Disposition[] = [
  { kind: 'route', name: '/api/models', disposition: 'read-only' },
  { kind: 'route', name: '/api/add-model', disposition: 'editable' },
  // The CLI verbs this page answers (V4-219: every CLI capability has a console answer; CommandParser.kt).
  { kind: 'verb', name: 'add-model', disposition: 'pending', where: 'V4-220' },
  { kind: 'verb', name: 'models', disposition: 'pending', where: 'V4-219' },
];

/** What this page is for (V4-219, rendered into docs/design/JOBS.md). */
export const job: PageJob = {
  question: 'Which models can each head run, with what windows and prices?',
  leaves: 'Each head\'s declared models, their context windows and where each came from, and their rates.',
  actions: [
    { name: 'Open a model' },
    { name: 'Add a model', row: 'V4-220' },
    { name: 'Compare the declared models with what each provider publishes', row: 'V4-219' },
  ],
};
