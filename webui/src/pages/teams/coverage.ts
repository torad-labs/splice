// What this page does with the team routes. Every one of them is daemon work
// still to come (FEATURES.md section 6, item V4-131), so the page reads none of
// them today: it renders the comp's board from its dev fixture and the honest
// empty naming V4-131 against the live daemon.
//
// The names are the denominator's own, normalized by the rule in CONTRACTS.md
// section 4 (method prefix stripped, query dropped, one item per slash-joined
// method).
import type { Disposition } from '@shared/coverage';

export const dispositions: Disposition[] = [
  { kind: 'route', name: '/api/teams', disposition: 'pending', where: 'V4-131' },
  // One team's board, fetched at entities/team/api/index.ts:30, and undisposed until now — the
  // 37 fields of TeamPayload all rest on it (M1-37, M1-41).
  { kind: 'route', name: '/api/teams/{id}', disposition: 'pending', where: 'V4-131' },
  { kind: 'route', name: '/api/teams/{id}/sessions', disposition: 'pending', where: 'V4-131' },
  { kind: 'route', name: '/api/teams/{id}/edges', disposition: 'pending', where: 'V4-131' },
  { kind: 'route', name: '/api/teams/{id}/chat', disposition: 'pending', where: 'V4-131' },
  { kind: 'route', name: '/api/teams/{id}/activity', disposition: 'pending', where: 'V4-131' },
  { kind: 'route', name: '/api/teams/{id}/economics', disposition: 'pending', where: 'V4-131' },
  { kind: 'route', name: '/api/teams/{id}/archive', disposition: 'pending', where: 'V4-131' },
  { kind: 'route', name: '/api/teams/{id}/slots/{slot}/instructions', disposition: 'pending', where: 'V4-131' },
];
