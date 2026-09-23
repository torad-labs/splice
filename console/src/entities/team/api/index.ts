// The teams entity's HTTP segment: the list, the opened team's three panels, and the writes. No
// rendering. `request` carries the management key, the 401 lockout and the error envelope, and
// `pendingOf` maps a daemon older than V4-131 to the honest empty (CONTRACTS.md section 8).
//
// There is no GET /api/teams/{id} (TeamsRoutes.kt: the read is split on purpose), so the opened
// team comes from the list and its panels from the three reads below.
import { pendingOf, request } from '@shared/api';
import { teamPanelsStore, teamsStore } from '../model/store';
import { PENDING_TEAMS } from '../model/types';
import type {
  TeamActivityPayload,
  TeamChatPayload,
  TeamEconomicsPayload,
  TeamRow,
  TeamsPayload,
  TeamWrite,
} from '../model/types';

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

const teamPath = (id: string): string => `/api/teams/${encodeURIComponent(id)}`;

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

/** A read whose failure is kept as its own outcome, so one panel's error does not blank the rest. */
function settled<T>(read: Promise<T>): Promise<T | { error: string }> {
  return read.catch((err: unknown) => ({ error: messageOf(err) }));
}

/** The opened team's chat and activity for `day` (a UTC date, YYYY-MM-DD; today when omitted) and
 *  its lifetime economics, read in parallel. Each path is written out at its request<T>() call, which
 *  is where tools/e2e/probes/console-wire-keys.ts finds a read and the type it is checked against. */
export async function fetchTeamPanels(id: string, day?: string): Promise<void> {
  teamPanelsStore.startLoading();
  const query = day === undefined ? '' : `?day=${encodeURIComponent(day)}`;
  const [chat, activity, economics] = await Promise.all([
    settled(request<TeamChatPayload>(`/api/teams/${encodeURIComponent(id)}/chat${query}`)),
    settled(request<TeamActivityPayload>(`/api/teams/${encodeURIComponent(id)}/activity${query}`)),
    settled(request<TeamEconomicsPayload>(`/api/teams/${encodeURIComponent(id)}/economics`)),
  ]);
  teamPanelsStore.setData({ teamId: id, chat, activity, economics });
}

/** Creates a team. `key` is the Idempotency-Key: a retried create with the same key answers the
 *  team it already made rather than making a second one. */
export function createTeam(team: TeamWrite, key: string): Promise<TeamRow> {
  return request<TeamRow>('/api/teams', {
    method: 'PUT',
    headers: { 'Idempotency-Key': key },
    body: JSON.stringify(team),
  });
}

/** Replaces a team's composition. A binding the body leaves null is KEPT by the daemon
 *  (TeamRules.carried), so an unbind goes through bindSessions. */
export function replaceTeam(id: string, team: TeamWrite): Promise<TeamRow> {
  return request<TeamRow>(teamPath(id), { method: 'PUT', body: JSON.stringify(team) });
}

/** Binds or unbinds slots: slot id to session id, or to null to open the seat. */
export function bindSessions(id: string, bindings: Record<string, string | null>): Promise<TeamRow> {
  return request<TeamRow>(`${teamPath(id)}/sessions`, { method: 'PUT', body: JSON.stringify({ bindings }) });
}
