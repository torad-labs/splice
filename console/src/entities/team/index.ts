import type { PendingRoute } from '@shared/api';
import { teamPanelsStore, teamsStore } from './model/store';

export { bindSessions, createTeam, fetchTeamPanels, fetchTeams, replaceTeam } from './api';
export { PENDING_TEAMS } from './model/types';
export type {
  TeamActivity,
  TeamActivityEntry,
  TeamActivityPayload,
  TeamChatMessage,
  TeamChatPayload,
  TeamEconomicsPayload,
  TeamMemberRow,
  TeamMessage,
  TeamPanels,
  TeamPayload,
  TeamRoleTally,
  TeamRow,
  TeamSlot,
  TeamSlotTally,
  TeamTally,
  TeamsPayload,
  TeamsState,
  TeamWrite,
} from './model/types';
/** Whether a state is the honest empty rather than a payload. A type predicate,
 *  so a caller that has checked it can read the payload's own fields. */
export function isPending<T extends object>(state: T | PendingRoute | null): state is PendingRoute {
  return state !== null && 'pending' in state;
}
export const useTeams = teamsStore.use;
export const useTeamPanels = teamPanelsStore.use;
