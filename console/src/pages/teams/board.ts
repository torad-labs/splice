// One team's board, composed from the reads the daemon serves. There is no GET /api/teams/{id}
// (TeamsRoutes.kt splits the read on purpose), so the board is the team row from the list, the
// registry rows of the sessions its slots hold, the day's chat and activity, the lifetime
// economics, and the day's perf rows of the team's sessions. Pure: no store and no clock (the
// caller passes `now`), so the suite holds every join against fixed payloads.
//
// EVERY STAMP IS UTC, because the daemon's day is (TeamsReads.kt reads `?day=` as a UTC date) and
// the board's own stamps already are (widgets/team-board/parts.tsx).
import type { SessionRow } from '@entities/session';
import type { TurnRow } from '@entities/perf';
import type {
  TeamActivity,
  TeamActivityPayload,
  TeamChatPayload,
  TeamEconomicsPayload,
  TeamMemberRow,
  TeamMessage,
  TeamPanels,
  TeamPayload,
  TeamRow,
} from '@entities/team';
import { SESSION_TAG_CHARS, tokensIn } from '@widgets/team-board';
import type { TeamHourPoint, TeamTurn, TeamViewData } from '@widgets/team-board';

const DAY_MS = 86_400_000;
const MINUTE_MS = 60_000;

/** The member state printed for a bound session the registry does not list. */
export const UNLISTED = 'unlisted';

const iso = (epochMs: number): string => new Date(epochMs).toISOString();
export const hhmm = (epochMs: number): string => iso(epochMs).slice(11, 16);
export const hhmmss = (epochMs: number): string => iso(epochMs).slice(11, 19);

/** A duration the way the board prints one: `4h 49m` past the hour, `2m 25s` under it. */
export function span(ms: number): string {
  const seconds = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = String(seconds % 60).padStart(2, '0');
  return h > 0 ? `${h}h ${m}m` : `${m}m ${s}s`;
}

/** Midnight UTC of the day `epochMs` falls in: the start of the day the chat and activity read. */
export function dayStartOf(epochMs: number): number {
  return Math.floor(epochMs / DAY_MS) * DAY_MS;
}

/** A panel's payload when it answered for THIS team, else null (still reading, or the store holds
 *  the team that was open before). */
function answered<T extends object>(panels: TeamPanels | null, team: TeamRow, pick: (p: TeamPanels) => T | { error: string }): T | null {
  if (panels === null || panels.teamId !== team.id) return null;
  const value = pick(panels);
  return 'error' in value ? null : value;
}

/**
 * One member per BOUND slot, in slot order. The registry supplies the name, the state, the start
 * and the working directory; the daemon's per-slot tally supplies the turns, tokens, cost and
 * checks, but ONLY when the slot has held no session but this one: a slot's tally sums every
 * session it ever held, and printing that on a later session's strip would be another session's
 * work under this one's name.
 */
export function membersOf(team: TeamRow, sessions: readonly SessionRow[], economics: TeamEconomicsPayload | null, now: number): TeamMemberRow[] {
  return team.slots.flatMap((slot): TeamMemberRow[] => {
    const session = slot.session;
    if (session === null) return [];
    const row = sessions.find((s) => s.session_id === session);
    const own = slot.sessions_history.every((held) => held === session);
    const tally = own ? (economics?.slots.find((t) => t.slot === slot.id) ?? null) : null;
    const started = row?.started_at ?? null;
    const live = row?.availability === 'live';
    return [{
      slot: slot.id,
      name: row?.name ?? session,
      role: slot.role,
      head: slot.head,
      model: slot.model,
      account: slot.account,
      window: null,
      lastTurn: tally?.last_turn_at_epoch_millis == null ? null : hhmm(tally.last_turn_at_epoch_millis),
      state: row === undefined ? UNLISTED : live ? (row.status ?? 'live') : row.availability,
      sessionId: session,
      created: started === null ? null : hhmmss(started),
      uptime: started !== null && live ? span(now - started) : null,
      turns: tally?.turns ?? null,
      tokensIn: tally === null ? null : tokensIn(tally),
      tokensOut: tally?.tokens.output ?? null,
      costEst: tally?.cost_usd ?? null,
      contextLeftPct: null,
      scratchpadKb: null,
      workspace: row?.cwd ?? null,
      branch: null,
      base: null,
      diff: null,
      checks: tally?.checks ?? null,
    }];
  });
}

/**
 * The day's messages as the board prints them. The sender is a session id, printed as its member's
 * name when a slot holds it now; the recipient is an address or a name the daemon resolved to a
 * slot, printed as that slot's member. A party the board does not rack keeps the daemon's own
 * string. The daemon has no packet for a message (`packet_note` says why), and a text it could not
 * read prints the reason it gave.
 */
export function messagesOf(members: readonly TeamMemberRow[], chat: TeamChatPayload | null): TeamMessage[] {
  if (chat === null) return [];
  return chat.messages.map((message) => ({
    time: hhmm(message.at),
    from: members.find((m) => m.sessionId === message.from)?.name ?? message.from,
    to: (message.to_slot === null ? undefined : members.find((m) => m.slot === message.to_slot)?.name) ?? message.to,
    packet: 'n/r',
    text: message.text ?? `text not read: ${message.missing_reason ?? 'no reason given'}`,
    fromHead: message.from_head ?? 'n/r',
  }));
}

/** The day's activity samples, each under its member's name. */
export function activityOf(members: readonly TeamMemberRow[], activity: TeamActivityPayload | null): TeamActivity[] {
  if (activity === null) return [];
  return activity.entries.map((entry) => ({
    time: hhmmss(entry.at),
    member: members.find((m) => m.sessionId === entry.session)?.name ?? entry.session,
    activity: entry.label,
    detail: entry.detail ?? '',
  }));
}

/** The board of one team. */
export function boardOf(team: TeamRow, sessions: readonly SessionRow[], panels: TeamPanels | null, now: number): TeamPayload {
  const members = membersOf(team, sessions, answered(panels, team, (p) => p.economics), now);
  return {
    team,
    members,
    messages: messagesOf(members, answered(panels, team, (p) => p.chat)),
    activity: activityOf(members, answered(panels, team, (p) => p.activity)),
  };
}

/** Where a perf row's turn began: it is stamped when the turn ENDS (PerfStats.record), and `total`
 *  is the turn's own length. */
const startOf = (row: TurnRow): number => row.ts - (row.total ?? 0);

/** The member a perf row belongs to, on the session tag the row carries (its first
 *  SESSION_TAG_CHARS characters, TurnDrive), or undefined for a row of no member. */
const memberOf = (members: readonly TeamMemberRow[], row: TurnRow): TeamMemberRow | undefined =>
  row.session === undefined ? undefined : members.find((m) => m.sessionId.slice(0, SESSION_TAG_CHARS) === row.session);

/** The day's landed turns of the team's members, oldest first. */
export function turnsOf(members: readonly TeamMemberRow[], rows: readonly TurnRow[], dayStart: number): TeamTurn[] {
  return rows
    .filter((row) => row.ts >= dayStart && memberOf(members, row) !== undefined)
    .sort((a, b) => startOf(a) - startOf(b))
    .map((row) => {
      const input = row.in_tokens === undefined ? null : row.in_tokens + (row.cached_tokens ?? 0) + (row.cache_write_tokens ?? 0);
      return {
        id: hhmmss(startOf(row)),
        member: memberOf(members, row)?.name ?? '',
        time: hhmm(startOf(row)),
        duration: span(row.total ?? 0),
        input,
        output: row.out_tokens ?? null,
        live: false,
      };
    });
}

/** The team's turns in flight at each minute of the last hour: a turn counts from its start to its
 *  end, both included. */
export function lastHourOf(members: readonly TeamMemberRow[], rows: readonly TurnRow[], now: number): TeamHourPoint[] {
  const team = rows.filter((row) => memberOf(members, row) !== undefined);
  const end = Math.floor(now / MINUTE_MS) * MINUTE_MS;
  return Array.from({ length: 61 }, (_, index) => {
    const at = end - (60 - index) * MINUTE_MS;
    return { at: hhmm(at), turns: team.filter((row) => startOf(row) <= at && at <= row.ts).length };
  });
}

/** What the by-role and timeline views read beyond the board, once this team's panels answered;
 *  null until then, which the views print as reading. */
export function viewDataOf(board: TeamPayload, rows: readonly TurnRow[], panels: TeamPanels | null, now: number): TeamViewData | null {
  if (panels === null || panels.teamId !== board.team.id) return null;
  return {
    turns: turnsOf(board.members, rows, dayStartOf(now)),
    economics: panels.economics,
    lastHour: lastHourOf(board.members, rows, now),
    now: hhmm(now),
  };
}
