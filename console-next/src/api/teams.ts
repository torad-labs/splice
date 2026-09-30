// What the Teams board reads beyond the list (`useTeams`, queries.ts) and the writes to a team.
//
// There is no GET /api/teams/{id}: the read is split on purpose. The opened team comes from the list, and its
// panels from the three reads below (the day's chat, the day's activity, lifetime economics), one hook each so
// one panel's failure does not blank the rest. `from`/`to` bound the viewer's LOCAL day in epoch ms, [from, to).
//
// Every write answers the saved team. Archiving is a flag on a replace and a slot's standing instructions are a
// field of its slot, so both are a replace of the whole composition (`teamWriteOf`): a replace writes each slot
// whole, and a binding the body leaves null is KEPT by the daemon (TeamRules.carried), so an unbind is its own write.
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from './client';
import { keys, read } from './queries';
import type {
  TeamActivityPayload,
  TeamChatPayload,
  TeamDay,
  TeamEconomicsPayload,
  TeamRow,
  TeamWrite,
} from '../types/teams';

/** How often the open team's panels are re-read while the day is today. */
export const TEAM_PANELS_POLL_MS = 10_000;

export const teamPanelsKey = ['team-panels'] as const;

const seg = encodeURIComponent;

export const teamPath = (id: string): string => `/api/teams/${seg(id)}`;
export const teamSessionsPath = (id: string): string => `${teamPath(id)}/sessions`;
export const teamEconomicsPath = (id: string): string => `${teamPath(id)}/economics`;
const dayQuery = (day: TeamDay): string => `?from=${day.from}&to=${day.to}`;
export const teamChatPath = (id: string, day: TeamDay): string => `${teamPath(id)}/chat${dayQuery(day)}`;
export const teamActivityPath = (id: string, day: TeamDay): string => `${teamPath(id)}/activity${dayQuery(day)}`;

// ── reads ────────────────────────────────────────────────────────────────────────────────────────

/** The day's messages between the team's sessions, text read on demand from the sender's transcript. `live` is
 *  today: it follows the poll. An older day is read once. */
export const useTeamChat = (id: string | null, day: TeamDay, live: boolean) =>
  useQuery(
    read<TeamChatPayload>([...teamPanelsKey, 'chat'], teamChatPath(id ?? '', day), {
      enabled: id !== null,
      refetchInterval: live ? TEAM_PANELS_POLL_MS : false,
    }),
  );

/** The day's sampled activity labels (about one per 30 s while a session works). */
export const useTeamActivity = (id: string | null, day: TeamDay, live: boolean) =>
  useQuery(
    read<TeamActivityPayload>([...teamPanelsKey, 'activity'], teamActivityPath(id ?? '', day), {
      enabled: id !== null,
      refetchInterval: live ? TEAM_PANELS_POLL_MS : false,
    }),
  );

/** Lifetime tallies per role and slot. */
export const useTeamEconomics = (id: string | null) =>
  useQuery(
    read<TeamEconomicsPayload>([...teamPanelsKey, 'economics'], teamEconomicsPath(id ?? ''), {
      enabled: id !== null,
      refetchInterval: TEAM_PANELS_POLL_MS,
    }),
  );

// ── writes ───────────────────────────────────────────────────────────────────────────────────────

/** PUT /api/teams under an Idempotency-Key: a retried create with the same key answers the team it already made. */
export const createTeam = (team: TeamWrite, key: string): Promise<TeamRow> =>
  request<TeamRow>('/api/teams', { method: 'PUT', headers: { 'Idempotency-Key': key }, body: JSON.stringify(team) });

/** PUT /api/teams/{id}: replace the composition. */
export const replaceTeam = (id: string, team: TeamWrite): Promise<TeamRow> =>
  request<TeamRow>(teamPath(id), { method: 'PUT', body: JSON.stringify(team) });

/** PUT /api/teams/{id}/sessions: slot id to session id, or to null to open the seat. The only way to unbind. */
export const bindSessions = (id: string, bindings: Record<string, string | null>): Promise<TeamRow> =>
  request<TeamRow>(teamSessionsPath(id), { method: 'PUT', body: JSON.stringify({ bindings }) });

/** The slots a save must UNBIND after the replace: bound on the daemon, open in the body. */
export function unbindsOf(team: TeamRow, body: TeamWrite): Record<string, null> {
  const open = new Set(body.slots.filter((slot) => slot.session === null).map((slot) => slot.id));
  return Object.fromEntries(team.slots.filter((slot) => slot.session !== null && open.has(slot.id)).map((slot) => [slot.id, null]));
}

/** Creates the team under `key` when `team` is null, else replaces `team` and unbinds every seat the body opened.
 *  Answers the team as the daemon holds it after the last write. */
export async function saveTeam(team: TeamRow | null, body: TeamWrite, key: string): Promise<TeamRow> {
  if (team === null) return createTeam(body, key);
  const replaced = await replaceTeam(team.id, body);
  const unbinds = unbindsOf(team, body);
  return Object.keys(unbinds).length === 0 ? replaced : bindSessions(team.id, unbinds);
}

/** A team as read, as the body of a replace: every field carried, so a write that changes one thing clears nothing. */
export const teamWriteOf = (team: TeamRow): TeamWrite => ({
  name: team.name,
  goal: team.goal,
  features: team.features,
  repo: team.repo,
  archived: team.archived,
  slots: team.slots.map((slot) => ({
    id: slot.id,
    role: slot.role,
    head: slot.head,
    model: slot.model,
    account: slot.account,
    lead: slot.lead,
    instructions: slot.instructions,
    session: slot.session,
  })),
});

/** A write, then everything it may have changed is read again: the list, the sessions whose rows carry the team,
 *  and the panels. */
function useTeamWrite<Vars>(run: (vars: Vars) => Promise<TeamRow>) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: run,
    onSettled: () => Promise.all([keys.teams, keys.sessions, teamPanelsKey].map((key) => client.invalidateQueries({ queryKey: [...key] }))),
  });
}

/** Create (`team: null`) or replace a team from a composed body. `key` is a create's Idempotency-Key: mint one per
 *  body and reuse it for a retry of the same body (a changed body needs a new key, the daemon answers 409 otherwise). */
export const useSaveTeam = () =>
  useTeamWrite(({ team, body, key }: { team: TeamRow | null; body: TeamWrite; key: string }) => saveTeam(team, body, key));

/** Archive or restore: only the flag moves. Nothing is ever deleted. */
export const useArchiveTeam = () =>
  useTeamWrite(({ team, archived }: { team: TeamRow; archived: boolean }) => replaceTeam(team.id, { ...teamWriteOf(team), archived }));
