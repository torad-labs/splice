// What this page does with the team routes (V4-131, served by TeamsRoutes.kt). The board is
// composed from the list and three per-team reads (board.ts); the composer writes through the
// create, the replace and the binding write, and the narrower writes the replace already covers
// are excluded with the write that does their work.
//
// The names are the denominator's own, normalized by the rule in CONTRACTS.md
// section 4 (method prefix stripped, query dropped, one item per slash-joined
// method).
import type { Disposition } from '@shared/coverage';

export const dispositions: Disposition[] = [
  // GET is the list the page opens a team from; PUT is the composer's create.
  { kind: 'route', name: '/api/teams', disposition: 'editable' },
  // PUT only: the composer's save of an existing team. There is no GET (TeamsRoutes.kt splits the read).
  { kind: 'route', name: '/api/teams/{id}', disposition: 'editable' },
  // The only way to open a seat: a replace keeps a binding its body leaves null.
  { kind: 'route', name: '/api/teams/{id}/sessions', disposition: 'editable' },
  { kind: 'route', name: '/api/teams/{id}/chat', disposition: 'read-only' },
  { kind: 'route', name: '/api/teams/{id}/activity', disposition: 'read-only' },
  { kind: 'route', name: '/api/teams/{id}/economics', disposition: 'read-only' },
  {
    kind: 'route',
    name: '/api/teams/{id}/edges',
    disposition: 'excluded',
    reason: 'the chat read serves the same edges one UTC day at a time with their text, and the board draws one day',
  },
  {
    kind: 'route',
    name: '/api/teams/{id}/archive',
    disposition: 'excluded',
    reason: "the composer's archived flag saves through PUT /api/teams/{id}, which writes the same flag",
  },
  {
    kind: 'route',
    name: '/api/teams/{id}/slots/{slot}/instructions',
    disposition: 'excluded',
    reason: "the composer writes every slot's instructions through PUT /api/teams/{id}",
  },
];
