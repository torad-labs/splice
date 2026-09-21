// The teams entity's HTTP segment: two reads, no rendering. `pendingOf` carries
// the "route not built yet" mapping (CONTRACTS.md section 8), so nothing here
// re-implements it.
import { pendingOf, request } from '@shared/api';
import { teamStore, teamsStore } from '../model/store';
import { PENDING_TEAMS } from '../model/types';
import type { TeamPayload, TeamsPayload } from '../model/types';

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

export async function fetchTeams(): Promise<void> {
  teamsStore.startLoading();
  try {
    teamsStore.setData(await request<TeamsPayload>('/api/teams'));
  } catch (err) {
    const pending = pendingOf(err, PENDING_TEAMS);
    if (pending !== null) {
      teamsStore.setData(pending);
      return;
    }
    teamsStore.setError(messageOf(err));
  }
}

export async function fetchTeam(id: string): Promise<void> {
  teamStore.startLoading();
  try {
    teamStore.setData(await request<TeamPayload>(`/api/teams/${encodeURIComponent(id)}`));
  } catch (err) {
    const pending = pendingOf(err, PENDING_TEAMS);
    if (pending !== null) {
      teamStore.setData(pending);
      return;
    }
    teamStore.setError(messageOf(err));
  }
}
