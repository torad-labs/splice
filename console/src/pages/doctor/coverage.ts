// The names this page owns, taken over from the baseline the coverage wall (row M1-04) reads. A
// name carries exactly ONE page disposition and the wall fails on two, so the row's routes split
// between this file and pages/mcp/coverage.ts: `/api/mcp` is declared there, the other three here.
import type { Disposition } from '@shared/coverage';

export const dispositions: readonly Disposition[] = [
  // The report, read and never written (V4-127 serves it).
  { kind: 'route', name: '/api/doctor', disposition: 'read-only' },
  // The upgrade: GET is the status the version strip reads (V4-127); POST starts `splice upgrade` or
  // its rollback out of process (V4-220 item 4), and /api/upgrade/run is that run's progress.
  { kind: 'route', name: '/api/upgrade', disposition: 'editable' },
  { kind: 'route', name: '/api/upgrade/run', disposition: 'read-only' },
  // /api/heads/{head}/capture moved to pages/turns/coverage.ts (M4-04): the turns page's request
  // drawer carries the switch that reads and writes it, and a name has exactly one page owner.
  // Budgets and alerts moved to pages/usage/coverage.ts (M4-06): the usage page is the one that
  // mounts the two panels that read and write them, and V4-133 serves all three routes now.
  // One prompt through one head. Editable, and never recorded. The disposition was written before
  // anything called the route; the playground's send has been one POST since M4-03
  // (entities/playground), so the claim now has a caller.
  { kind: 'route', name: '/api/playground', disposition: 'editable' },
  // V4-220 item 4: the Fix button for a row whose `fix_id` names a fix the daemon runs itself
  // (install_all). Declared with the daemon commit that serves it; the answer is doctor re-run.
  { kind: 'route', name: '/api/doctor/fix/{id}', disposition: 'editable' },
];
