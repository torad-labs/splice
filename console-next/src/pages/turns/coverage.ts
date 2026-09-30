// The names this page owns in the replacement console, each with what the console does with it. The disposition says what a person
// can do here, not what the daemon does with the name. A name carries exactly one page disposition.
import type { Disposition } from '../../coverage/checks';
import type { PageJob } from '../../coverage/jobs';

export const dispositions: readonly Disposition[] = [
  { kind: 'route', name: '/api/perf', disposition: 'excluded', reason: '/api/perf/summary serves the same percentiles per window, and the page reads that' },
  { kind: 'route', name: '/api/perf/summary', disposition: 'read-only' },
  { kind: 'route', name: '/api/perf/turns', disposition: 'read-only' },
  { kind: 'route', name: '/api/heads/{head}/capture', disposition: 'editable' },
  { kind: 'route', name: '/api/heads/{head}/conversation', disposition: 'read-only' },
  { kind: 'route', name: '/api/heads/{head}/trace', disposition: 'read-only' },
  { kind: 'route', name: '/api/heads/{head}/wire', disposition: 'read-only' },
  { kind: 'verb', name: 'perf', disposition: 'read-only', via: '/api/perf/summary' },
  { kind: 'verb', name: 'trace', disposition: 'read-only', action: 'Read a plan’s request and answer for a turn', via: '/api/heads/{head}/trace' },
  { kind: 'verb', name: 'wire', disposition: 'read-only', action: 'Read what a plan sent upstream', via: '/api/heads/{head}/wire' },
];

export const job: PageJob = {
  question: 'How are my turns going, and what happened in one?',
  leaves: 'Finished turns with their outcome, time and cost, and for one turn its conversation, its request and answer, and what was sent to the plan.',
  actions: [
    { name: 'Filter and read finished turns' },
    { name: 'Read a plan’s request and answer for a turn' },
    { name: 'Read what a plan sent upstream' },
    { name: 'Turn capture on for a plan' },
  ],
};
