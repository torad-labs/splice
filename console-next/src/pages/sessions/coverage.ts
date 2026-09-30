// The names this page owns in the replacement console, each with what the console does with it. The disposition says what a person
// can do here, not what the daemon does with the name. A name carries exactly one page disposition.
import type { Disposition } from '../../coverage/checks';
import type { PageJob } from '../../coverage/jobs';

export const dispositions: readonly Disposition[] = [
  { kind: 'route', name: '/api/sessions', disposition: 'read-only' },
  { kind: 'route', name: '/api/sessions/edges', disposition: 'read-only' },
  { kind: 'route', name: '/api/sessions/history', disposition: 'read-only' },
  { kind: 'verb', name: 'sessions', disposition: 'read-only', via: '/api/sessions' },
];

export const job: PageJob = {
  question: 'What is running, and where?',
  leaves: 'Every session grouped by state, plan, repo or team, and which ones need me.',
  actions: [
    { name: 'Search and group sessions' },
    { name: 'Reorder the cards' },
    { name: 'Open a session' },
    { name: 'Create or edit a team' },
    { name: 'Open a repo\'s page' },
    { name: 'Stop a session\'s turn' },
  ],
};
