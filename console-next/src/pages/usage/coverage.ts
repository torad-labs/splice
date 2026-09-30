// The names this page owns in the replacement console, each with what the console does with it. The disposition says what a person
// can do here, not what the daemon does with the name. A name carries exactly one page disposition.
import type { Disposition } from '../../coverage/checks';
import type { PageJob } from '../../coverage/jobs';

export const dispositions: readonly Disposition[] = [
  { kind: 'route', name: '/api/alerts', disposition: 'editable' },
  { kind: 'route', name: '/api/alerts/test', disposition: 'editable' },
  { kind: 'route', name: '/api/budgets', disposition: 'editable' },
  { kind: 'route', name: '/api/economics', disposition: 'read-only' },
];

export const job: PageJob = {
  question: 'What have I used, and what will it cost?',
  leaves: 'Each plan\'s windows and pace, what the day and week cost, and the budgets and alerts that guard it.',
  actions: [
    { name: 'Choose the window' },
    { name: 'Set a daily budget for a plan' },
    { name: 'Set alerts and test them' },
  ],
};
