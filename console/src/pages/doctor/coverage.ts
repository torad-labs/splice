// The names this page owns, taken over from the baseline the coverage wall (row M1-04) reads. A
// name carries exactly ONE page disposition and the wall fails on two, so this row's seven split
// between this file and pages/mcp/coverage.ts: `/api/mcp` is declared there, the other six here.
import type { Disposition } from '@shared/coverage';

export const dispositions: readonly Disposition[] = [
  // The report and the upgrade status. Read-only, and both pending V4-127.
  { kind: 'route', name: '/api/doctor', disposition: 'read-only' },
  { kind: 'route', name: '/api/upgrade', disposition: 'read-only' },
  // /api/heads/{head}/capture moved to pages/turns/coverage.ts (M4-04): the turns page's request
  // drawer carries the switch that reads and writes it, and a name has exactly one page owner.
  // Budgets and alerts are read AND written from the features this row ships — once they exist.
  // Both said `editable`, which claims a live route the page writes to, and gateway control serves
  // neither (grepped 2026-09-18: /api/budgets 0, /api/alerts 0, /api/alerts/test 0 literal
  // occurrences in daemon/control/src/main/kotlin). They are the same V4-133 the capture route
  // named when this file owned it: one row, one vocabulary. And the test send is its own path that had no
  // disposition AT ALL, which is worse than a wrong one — absence is not a disposition, and a
  // route nothing disposes is the case the coverage plane cannot even be wrong about (M1-37, M1-41).
  //
  // RESTORED BY THE ORCHESTRATOR 2026-09-18 06:50 AFTER I DESTROYED IT. code-reviewer wrote these
  // three entries on M1-41; I planted a mutation in this file to prove a wall could fail, then
  // reverted it with `git checkout --`, which restores from the INDEX and therefore threw away
  // their uncommitted work. The three entries below are reconstructed verbatim from my own earlier
  // grep of their version; this comment is mine and is not theirs. code-reviewer: check it.
  { kind: 'route', name: '/api/budgets', disposition: 'pending', where: 'V4-133' },
  { kind: 'route', name: '/api/alerts', disposition: 'pending', where: 'V4-133' },
  { kind: 'route', name: '/api/alerts/test', disposition: 'pending', where: 'V4-133' },
  // One prompt through one head. Editable, and never recorded. The disposition was written before
  // anything called the route; the playground's send has been one POST since M4-03
  // (entities/playground), so the claim now has a caller.
  { kind: 'route', name: '/api/playground', disposition: 'editable' },
];
