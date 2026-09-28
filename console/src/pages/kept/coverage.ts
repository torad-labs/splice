import type { Disposition, PageJob } from '@shared/coverage';

/** Each splice-owned durable store is read and deleted here, never silently elsewhere. */
export const dispositions: readonly Disposition[] = [
  { kind: 'route', name: '/api/kept/edges', disposition: 'editable' },
  { kind: 'route', name: '/api/kept/labels', disposition: 'editable' },
  { kind: 'route', name: '/api/kept/turns', disposition: 'editable' },
  { kind: 'route', name: '/api/heads/{head}/trace/kept', disposition: 'editable' },
];

export const job: PageJob = {
  question: 'What does splice keep about me, and how do I stop it?',
  leaves: 'Each store, its contents, where it lives, how long it stays, and its controls.',
  actions: [
    { name: 'Stop keeping new activity and message edges' },
    { name: 'Delete stored message edges, activity labels, turn statistics and per-head trace' },
    { name: 'Open per-head recording controls' },
  ],
};
