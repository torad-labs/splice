// The names this page owns, taken over from the baseline the coverage wall (row M1-04) reads.
//
// The other six of this row's seven routes are declared in pages/doctor/coverage.ts, because a
// name may carry exactly ONE page disposition and the coverage wall fails on two. They live there
// rather than here for a reason that is not arbitrary: the doctor page is where the report, the
// upgrade status and the playground are shown, and the budget and alert features are its siblings
// in this row.
//
// `/mcp/{name}` is NOT declared anywhere as a page name: the baseline already excludes it with a
// reason ("client transport, not an operator surface").
import type { Disposition, PageJob } from '@shared/coverage';

export const dispositions: readonly Disposition[] = [
  // The shared MCP host's status. Read-only, and the only MCP route that exists: there is no
  // restart route, which the page prints as an honest empty rather than inventing a call.
  { kind: 'route', name: '/api/mcp', disposition: 'read-only' },
];

/** What this page is for (V4-219, rendered into docs/design/JOBS.md). */
export const job: PageJob = {
  question: 'Which MCP servers are running, and are they healthy and within limits?',
  leaves: 'Each server\'s state, sessions, restarts and last error, and the host limits in effect.',
  actions: [
    { name: 'Open a server' },
    { name: 'Edit the host limits in Settings' },
  ],
};
