// The names this page owns in the replacement console, each with what the console does with it. The disposition says what a person
// can do here, not what the daemon does with the name. A name carries exactly one page disposition.
// A session's own page: its transcript, its edges and the resume recipe for another plan.
import type { Disposition } from '../../coverage/checks';
import type { PageJob } from '../../coverage/jobs';

export const dispositions: readonly Disposition[] = [
  { kind: 'route', name: '/api/sessions/{id}/edges', disposition: 'read-only' },
  { kind: 'route', name: '/api/sessions/{id}/transcript', disposition: 'read-only' },
  { kind: 'route', name: '/api/sessions/{id}/resume', disposition: 'read-only' },
];

export const job: PageJob = {
  question: 'What is this session doing, and who has it handed work to?',
  leaves: 'Its conversation and tool calls, its hand-offs to and from other sessions, and the command that resumes it.',
  actions: [
    { name: 'Read the conversation' },
    { name: 'Filter to hand-offs' },
    { name: 'Copy the resume command for a plan' },
    { name: 'Stop the turn' },
  ],
};
