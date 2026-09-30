// The names this page owns in the replacement console, each with what the console does with it. The disposition says what a person
// can do here, not what the daemon does with the name. A name carries exactly one page disposition.
import type { Disposition } from '../../coverage/checks';
import type { PageJob } from '../../coverage/jobs';

export const dispositions: readonly Disposition[] = [
  { kind: 'route', name: '/api/projects', disposition: 'read-only' },
  { kind: 'route', name: '/api/projects/{id}', disposition: 'read-only' },
  { kind: 'route', name: '/api/projects/{id}/files', disposition: 'read-only' },
];

export const job: PageJob = {
  question: 'What runs in this repo, and what governs it?',
  leaves: 'The sessions and teams here, the rules and files that apply, and the repo\'s standing prompt and compaction rule.',
  actions: [
    { name: 'Edit the repo\'s standing prompt and compaction rule' },
  ],
};
