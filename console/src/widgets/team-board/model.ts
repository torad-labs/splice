// The two other views of a team, as pure functions of the payload: which bay a slot goes in, which
// row of the day an event lands on, and what a role cost. No React, no store, no clock, so the
// suite can hold each rule against a fixed payload.
//
// THE THREE PAYLOADS BELOW ARE NOT IN @entities/team, and that is deliberate rather than an
// oversight. They are what GET /api/teams/{id}/economics and the turn log will serve (V4-131), the
// entity slice is outside this row's fence, and CONTRACTS.md section 8 lets a consumer declare the
// payload it reads locally. Until the routes land every panel that reads one prints the honest
// empty naming V4-131, and only the dev fixture supplies them.
import type { TeamMemberRow, TeamMessage, TeamPayload, TeamSlot } from '@entities/team';

/** One turn of one member, as the turn log prints it. `live` is a turn still running: its
 *  duration is the time so far, and the timeline racks it on the `now` row. */
export interface TeamTurn {
  id: string;
  member: string;
  /** HH:MM the turn started. */
  time: string;
  duration: string;
  input: number;
  output: number;
  live: boolean;
}

/** One session's day, as the economics route sums it. `session` is the id the TURN LOG carries,
 *  which is longer than the one the board prints, so the join is on a prefix (see costPerRole). */
export interface TeamEconomicsSession {
  session: string;
  input: number;
  output: number;
  turns: number;
  oldestTurnId: string;
  /** HH:MM of that turn. */
  oldestTurnAt: string;
}

/** One point of the team's turn count over the last hour: HH:MM and the turns in flight then. */
export interface TeamHourPoint {
  at: string;
  turns: number;
}

/** What the by-role and timeline views read beyond the board payload. Null means the routes that
 *  serve it do not exist yet, and the panels say so. */
export interface TeamViewData {
  turns: TeamTurn[];
  economics: TeamEconomicsSession[];
  lastHour: TeamHourPoint[];
  /** HH:MM of the moment the views were read, printed as `now` on the rules. */
  now: string;
}

/** The session prefix the economics join matches on, named here because the panel prints it. */
export const JOIN_PREFIX = 8;

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
    ...board.members.map((member) => ({ at: secondsOf(member.created), member, message: null })),
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

/** A slot's name on the timeline's hand-offs: its role, numbered when the team declares the role
 *  more than once (`builder 1`, `builder 2`), in slot order. */
export function slotName(slots: readonly TeamSlot[], session: string): string {
  const slot = slots.find((s) => s.session === session);
  if (slot === undefined) return session;
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

export interface RoleCost {
  role: string;
  input: number;
  output: number;
  total: number;
  turns: number;
}

export interface CostTable {
  rows: RoleCost[];
  /** Sessions the economics route summed that match no member of this team on the prefix. */
  unattributed: RoleCost;
  total: RoleCost;
  oldest: { id: string; at: string } | null;
}

/**
 * Tokens per role for the day. The economics route keys each session by the id the turn log
 * carries, and the board knows a member by a shorter printed id, so the two are joined on their
 * first JOIN_PREFIX characters and the panel prints that it did. A session the join cannot place is
 * COUNTED on its own row rather than dropped, so the total is the route's total and not the
 * matched part of it.
 */
export function costPerRole(board: TeamPayload, sessions: readonly TeamEconomicsSession[]): CostTable {
  const blank = (role: string): RoleCost => ({ role, input: 0, output: 0, total: 0, turns: 0 });
  const add = (into: RoleCost, s: TeamEconomicsSession) => {
    into.input += s.input;
    into.output += s.output;
    into.total += s.input + s.output;
    into.turns += s.turns;
  };
  const byRole = new Map<string, RoleCost>(rolesOf(board.team.slots).map((role) => [role, blank(role)]));
  const unattributed = blank('unattributed');
  const total = blank('total');
  for (const s of sessions) {
    const member = board.members.find((m) => m.sessionId.slice(0, JOIN_PREFIX) === s.session.slice(0, JOIN_PREFIX));
    if (member === undefined) add(unattributed, s);
    else {
      if (!byRole.has(member.role)) byRole.set(member.role, blank(member.role));
      add(byRole.get(member.role) as RoleCost, s);
    }
    add(total, s);
  }
  const oldest = [...sessions].sort((a, b) => secondsOf(a.oldestTurnAt) - secondsOf(b.oldestTurnAt))[0];
  return {
    rows: [...byRole.values()],
    unattributed,
    total,
    oldest: oldest === undefined ? null : { id: oldest.oldestTurnId, at: oldest.oldestTurnAt },
  };
}

/** Turns per member for the day, on the same prefix join as the cost table so the two agree. */
export function turnsPerMember(board: TeamPayload, sessions: readonly TeamEconomicsSession[]): { member: string; turns: number }[] {
  return board.members.map((member) => ({
    member: member.name,
    turns: sessions
      .filter((s) => s.session.slice(0, JOIN_PREFIX) === member.sessionId.slice(0, JOIN_PREFIX))
      .reduce((n, s) => n + s.turns, 0),
  }));
}
