// The two other views of a team, as pure functions of the payload: which bay a slot goes in, which
// row of the day an event lands on, and what a role cost. No React, no store, no clock, so the
// suite can hold each rule against a fixed payload.
//
// WHAT THE VIEWS READ BEYOND THE BOARD (TeamViewData) is composed by pages/teams/board.ts: the
// team's turn log from the perf rows of its heads, the daemon's own economics tallies, and the turns
// in flight over the last hour worked out from that log. The economics are the daemon's numbers
// printed as they arrive: it joins every perf row to a slot on the 8-character session tag itself
// (TeamsEconomics.kt), so nothing here joins them a second time.
import type { TeamEconomicsPayload, TeamMemberRow, TeamMessage, TeamPayload, TeamSlot, TeamTally } from '@entities/team';

/** One turn of one member, as the turn log prints it. `live` is a turn still running: its
 *  duration is the time so far, and the timeline racks it on the `now` row. */
export interface TeamTurn {
  id: string;
  member: string;
  /** HH:MM the turn started. */
  time: string;
  duration: string;
  /** Null when the perf row carried no token counts (a torn or failed turn). */
  input: number | null;
  output: number | null;
  live: boolean;
}

/** One point of the team's turn count over the last hour: HH:MM and the turns in flight then. */
export interface TeamHourPoint {
  at: string;
  turns: number;
}

/** What the by-role and timeline views read beyond the board. `economics` is the daemon's answer
 *  or the reason it gave none. */
export interface TeamViewData {
  turns: TeamTurn[];
  economics: TeamEconomicsPayload | { error: string };
  lastHour: TeamHourPoint[];
  /** HH:MM of the moment the views were read, printed as `now` on the rules. */
  now: string;
}

/** The characters of a session id the daemon's perf rows keep, and so the width of every join it
 *  makes between a turn and a session (TurnDrive.SESSION_TAG_CHARS). Printed by the panels. */
export const SESSION_TAG_CHARS = 8;

// ---- the by-role board ---------------------------------------------------------------------

/** The team's declared roles in the order the slots declare them, lead first. A bay exists for
 *  every role the team DECLARES, bound or not: an open reviewer slot is a bay with nobody in it,
 *  which is the honest reading of the team and exactly what the comp draws. */
export function rolesOf(slots: readonly TeamSlot[]): string[] {
  const ordered = [...slots].sort((a, b) => Number(b.lead) - Number(a.lead));
  const roles: string[] = [];
  for (const slot of ordered) if (!roles.includes(slot.role)) roles.push(slot.role);
  return roles;
}

export interface RoleBay {
  role: string;
  members: TeamMemberRow[];
  /** A slot of this role with no session, whose head the empty bay names. */
  open: TeamSlot | null;
}

/** The members grouped into their role's bay. A member whose role no slot declares still gets a
 *  bay rather than vanishing: the board shows every session it was handed. */
export function groupByRole(board: TeamPayload): RoleBay[] {
  const roles = rolesOf(board.team.slots);
  for (const member of board.members) if (!roles.includes(member.role)) roles.push(member.role);
  return roles.map((role) => ({
    role,
    members: board.members.filter((member) => member.role === role),
    open: board.team.slots.find((slot) => slot.role === role && slot.session === null) ?? null,
  }));
}

/** HH:MM or HH:MM:SS as seconds of the day, so the two stamps the payload carries compare. */
export function secondsOf(stamp: string): number {
  const [h = '0', m = '0', s = '0'] = stamp.split(':');
  return Number(h) * 3600 + Number(m) * 60 + Number(s);
}

export type RoleEvent =
  | { kind: 'session'; member: TeamMemberRow; column: number; row: number }
  | { kind: 'message'; message: TeamMessage; from: number; to: number; row: number };

/**
 * The by-role board's rows: sessions at the moment they were created, hand-offs at the moment they
 * were sent, top to bottom in time. Sessions in DIFFERENT bays that follow each other share a row;
 * a hand-off always takes a row of its own, because it crosses the bays and would collide with
 * anything beside it. A hand-off runs from its sender's bay to its recipient's.
 */
export function roleRows(board: TeamPayload, bays: readonly RoleBay[]): RoleEvent[] {
  const columnOf = (session: string): number => {
    const member = board.members.find((m) => m.name === session);
    const role = member?.role ?? board.team.slots.find((s) => s.session === session)?.role;
    const at = bays.findIndex((bay) => bay.role === role);
    return at < 0 ? 0 : at;
  };
  const timed = [
    // A session whose start the registry does not report racks first rather than at a made-up time.
    ...board.members.map((member) => ({ at: member.created === null ? 0 : secondsOf(member.created), member, message: null })),
    ...board.messages.map((message) => ({ at: secondsOf(message.time), member: null, message })),
  ].sort((a, b) => a.at - b.at);

  const out: RoleEvent[] = [];
  let row = -1;
  let used = new Set<number>();
  let rowIsMessage = true;
  for (const event of timed) {
    if (event.member !== null) {
      const column = columnOf(event.member.name);
      if (rowIsMessage || used.has(column)) {
        row += 1;
        used = new Set();
        rowIsMessage = false;
      }
      used.add(column);
      out.push({ kind: 'session', member: event.member, column, row });
    } else if (event.message !== null) {
      row += 1;
      rowIsMessage = true;
      const a = columnOf(event.message.from);
      const b = columnOf(event.message.to);
      out.push({ kind: 'message', message: event.message, from: Math.min(a, b), to: Math.max(a, b), row });
    }
  }
  return out;
}

/** The rule along the foot of the bays: quarter hours from the one before the first hand-off up
 *  to `now`, each placed linearly between the two. */
export function timeRule(messages: readonly TeamMessage[], now: string): { label: string; at: number }[] {
  const end = secondsOf(now);
  const first = messages.length === 0 ? end : Math.min(...messages.map((m) => secondsOf(m.time)));
  const start = Math.floor(first / 900) * 900;
  const span = Math.max(end - start, 1);
  const marks: { label: string; at: number }[] = [];
  for (let t = start; t < end; t += 900) {
    const hh = String(Math.floor(t / 3600)).padStart(2, '0');
    const mm = String(Math.floor((t % 3600) / 60)).padStart(2, '0');
    marks.push({ label: `${hh}:${mm}`, at: ((t - start) / span) * 100 });
  }
  marks.push({ label: 'now', at: 100 });
  return marks;
}

/** The member the right column describes: the recipient of the newest hand-off, which is the
 *  session the team is waiting on. With no hand-off, the lead. */
export function focusMember(board: TeamPayload): TeamMemberRow | null {
  const newest = [...board.messages].sort((a, b) => secondsOf(b.time) - secondsOf(a.time))[0];
  const target = newest === undefined ? undefined : board.members.find((m) => m.name === newest.to);
  return target ?? board.members.find((m) => m.role === 'lead') ?? board.members[0] ?? null;
}

/** The newest hand-off a member RECEIVED, as its HH:MM, or null when none reached it. */
export function lastReceived(board: TeamPayload, member: string): string | null {
  const got = board.messages.filter((m) => m.to === member).sort((a, b) => secondsOf(b.time) - secondsOf(a.time));
  return got[0]?.time ?? null;
}

// ---- the timeline --------------------------------------------------------------------------

/** A member's slot name on the timeline's hand-offs: its role, numbered when the team declares the
 *  role more than once (`builder 1`, `builder 2`), in slot order. `name` is a member's printed name
 *  or, for a party the board does not rack, the name the message carried, printed as it is. */
export function slotName(board: TeamPayload, name: string): string {
  const member = board.members.find((m) => m.name === name);
  const slot = board.team.slots.find((s) => (member === undefined ? s.session === name : s.id === member.slot));
  return slot === undefined ? name : roleName(board.team.slots, slot);
}

/** One slot's role, numbered among the slots that share it. */
export function roleName(slots: readonly TeamSlot[], slot: TeamSlot): string {
  const same = slots.filter((s) => s.role === slot.role);
  return same.length > 1 ? `${slot.role} ${same.indexOf(slot) + 1}` : slot.role;
}

export type TimelineCell =
  | { kind: 'turn'; turn: TeamTurn }
  | { kind: 'activity'; member: string; activity: string; time: string };

export type TimelineRow =
  | { kind: 'bucket'; label: string; cells: TimelineCell[][] }
  | { kind: 'message'; label: string; message: TeamMessage; from: number; to: number };

const BUCKET = 300;

/**
 * The day as rows: one per five minutes, a hand-off on a row of its own at its own minute, and a
 * `now` row of the turns still running. A member's cell in a five-minute row holds the turns that
 * started in it; when there were none, the LATEST activity sample, because the feed is a sample
 * taken every 30 s and the newest one is what the member was doing at the end of the row. Times
 * round to the nearest five minutes, which is how the comp racks 13:33 and 13:34 on its 13:35 row.
 */
export function timelineRows(board: TeamPayload, data: TeamViewData): TimelineRow[] {
  const members = board.members.map((m) => m.name);
  const bucketOf = (stamp: string) => Math.round(secondsOf(stamp) / BUCKET) * BUCKET;
  const label = (t: number) => `${String(Math.floor(t / 3600)).padStart(2, '0')}:${String(Math.floor((t % 3600) / 60)).padStart(2, '0')}`;

  const buckets = new Set<number>();
  for (const turn of data.turns) if (!turn.live) buckets.add(bucketOf(turn.time));
  for (const entry of board.activity) buckets.add(bucketOf(entry.time));

  const rows: { at: number; row: TimelineRow }[] = [...buckets].map((at) => ({
    at,
    row: {
      kind: 'bucket' as const,
      label: label(at),
      cells: members.map((name) => {
        const turns = data.turns.filter((t) => !t.live && t.member === name && bucketOf(t.time) === at);
        if (turns.length > 0) return turns.map((turn) => ({ kind: 'turn' as const, turn }));
        const samples = board.activity
          .filter((a) => a.member === name && bucketOf(a.time) === at)
          .sort((a, b) => secondsOf(b.time) - secondsOf(a.time));
        const latest = samples[0];
        return latest === undefined ? [] : [{ kind: 'activity' as const, member: name, activity: latest.activity, time: latest.time.slice(0, 5) }];
      }),
    },
  }));

  const column = (session: string) => Math.max(0, members.indexOf(session));
  for (const message of board.messages) {
    const a = column(message.from);
    const b = column(message.to);
    // A hand-off sorts AFTER the five-minute row its minute rounds into when it is later than
    // that row's mark, and before it otherwise, so 13:41 sits between 13:40 and 13:45.
    rows.push({ at: secondsOf(message.time) + 0.5, row: { kind: 'message', label: message.time, message, from: Math.min(a, b), to: Math.max(a, b) } });
  }
  rows.sort((x, y) => x.at - y.at);

  const live = members.map((name) => data.turns.filter((t) => t.live && t.member === name).map((turn) => ({ kind: 'turn' as const, turn })));
  if (live.some((cells) => cells.length > 0)) rows.push({ at: Infinity, row: { kind: 'bucket', label: 'now', cells: live } });
  return rows.map((r) => r.row);
}

// ---- the economics panels ------------------------------------------------------------------

/** Every token a turn sent in: fresh input plus what it read from and wrote to the prompt cache,
 *  which is what the model was handed. */
export function tokensIn(tally: TeamTally): number {
  return tally.tokens.input + tally.tokens.cache_read + tally.tokens.cache_write;
}

export interface RoleCost {
  role: string;
  input: number;
  output: number;
  /** USD, or null when a turn in the row had no rate card: a partial sum would be a wrong number. */
  cost: number | null;
  turns: number;
}

export interface CostTable {
  rows: RoleCost[];
  total: RoleCost;
  /** Turns on the team's heads that carried no session tag: the daemon counts them and can place
   *  them in no role, so the table prints the count rather than dropping them. */
  unattributed: number;
  /** Epoch ms of the oldest turn the perf files still hold, which is how far back "lifetime"
   *  reaches. */
  oldest: number | null;
}

/** The daemon's per-role tallies as the table prints them, with their total. */
export function costTable(economics: TeamEconomicsPayload): CostTable {
  const rows = economics.roles.map((tally) => ({
    role: tally.role,
    input: tokensIn(tally),
    output: tally.tokens.output,
    cost: tally.cost_usd,
    turns: tally.turns,
  }));
  const total = rows.reduce<RoleCost>(
    (sum, row) => ({
      role: 'total',
      input: sum.input + row.input,
      output: sum.output + row.output,
      cost: sum.cost === null || row.cost === null ? null : sum.cost + row.cost,
      turns: sum.turns + row.turns,
    }),
    { role: 'total', input: 0, output: 0, cost: 0, turns: 0 },
  );
  return { rows, total, unattributed: economics.unattributed_turns, oldest: economics.oldest_turn_epoch_millis };
}

/** Turns per slot, named by the session sitting in it or, for an open seat, by its role: the
 *  daemon tallies a slot across every session it ever held, so an open seat can have turns. */
export function turnsPerSlot(board: TeamPayload, economics: TeamEconomicsPayload): { slot: string; name: string; turns: number }[] {
  return board.team.slots.map((slot) => ({
    slot: slot.id,
    name: board.members.find((member) => member.slot === slot.id)?.name ?? `${roleName(board.team.slots, slot)} (open)`,
    turns: economics.slots.find((tally) => tally.slot === slot.id)?.turns ?? 0,
  }));
}
