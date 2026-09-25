// A team's views as pure functions of its board: which seat is whose, the runs the seats are shown
// in, the clock the day's turns are drawn on, and what each role cost. No React, no store and no
// clock (the caller passes `now`), so the suite can hold each rule against a fixed payload.
//
// WHAT THE VIEWS READ BEYOND THE BOARD (TeamViewData) is composed by pages/teams/board.ts: the
// team's turn log from the perf rows of its heads, the daemon's own economics tallies, and the turns
// in flight over the last hour worked out from that log. The economics are the daemon's numbers
// printed as they arrive: it joins every perf row to a slot on the 8-character session tag itself
// (TeamsEconomics.kt), so nothing here joins them a second time.
import type { TeamEconomicsPayload, TeamMemberRow, TeamPayload, TeamSlot, TeamTally } from '@entities/team';

/** One turn of one member. `live` is a turn still running: its length is the time so far. */
export interface TeamTurn {
  id: string;
  member: string;
  /** Epoch ms the turn started. */
  start: number;
  ms: number;
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

/** What the views read beyond the board. `economics` is the daemon's answer or the reason it gave
 *  none. */
export interface TeamViewData {
  turns: TeamTurn[];
  economics: TeamEconomicsPayload | { error: string };
  lastHour: TeamHourPoint[];
  /** The team's turns in flight now, read off the heads' gates; null until the heads read answered,
   *  which the stat prints as unknown rather than as none. */
  inFlight: number | null;
  /** Epoch ms the views were read at: the right end of the day's timeline. */
  now: number;
}

/** The characters of a session id the daemon's perf rows keep, and so the width of every join it
 *  makes between a turn and a session (TurnDrive.SESSION_TAG_CHARS). */
export const SESSION_TAG_CHARS = 8;

/** HH:MM of an epoch in UTC, the clock of the daemon's day (TeamsReads.kt reads `?day=` as UTC). */
export function utcClock(epochMs: number): string {
  return new Date(epochMs).toISOString().slice(11, 16);
}

// ---- the seats -----------------------------------------------------------------------------

/** A slot and the member bound to it, or null when the seat is open. */
export interface Seat {
  slot: TeamSlot;
  member: TeamMemberRow | null;
}

/** Every slot the team DECLARES, in slot order, with its member: an open seat is part of the team,
 *  so it is a row with nobody in it rather than a gap. */
export function seatsOf(board: TeamPayload): Seat[] {
  return board.team.slots.map((slot) => ({ slot, member: board.members.find((member) => member.slot === slot.id) ?? null }));
}

/** The team's declared roles in the order the slots declare them, the lead's first. The lead is the
 *  slot's flag, never a role's name: compose lets a role be any text. */
export function rolesOf(slots: readonly TeamSlot[]): string[] {
  const ordered = [...slots].sort((a, b) => Number(b.lead) - Number(a.lead));
  const roles: string[] = [];
  for (const slot of ordered) if (!roles.includes(slot.role)) roles.push(slot.role);
  return roles;
}

export interface SeatGroup {
  key: string;
  seats: Seat[];
}

/** The seats in runs: by the head each slot runs on, in the order the slots first name it, or by
 *  role, in the order the team declares its roles. */
export function seatGroups(board: TeamPayload, by: 'head' | 'role'): SeatGroup[] {
  const seats = seatsOf(board);
  const keyOf = (seat: Seat): string => (by === 'role' ? seat.slot.role : seat.slot.head);
  const keys = by === 'role' ? rolesOf(board.team.slots) : [...new Set(seats.map(keyOf))];
  return keys.map((key) => ({ key, seats: seats.filter((seat) => keyOf(seat) === key) }));
}

/** HH:MM or HH:MM:SS as seconds of the day, so the two stamps the payload carries compare. */
function secondsOf(stamp: string): number {
  const [h = '0', m = '0', s = '0'] = stamp.split(':');
  return Number(h) * 3600 + Number(m) * 60 + Number(s);
}

/** The newest hand-off a member RECEIVED, as its HH:MM, or null when none reached it. */
export function lastReceived(board: TeamPayload, member: string): string | null {
  const got = board.messages.filter((m) => m.to === member).sort((a, b) => secondsOf(b.time) - secondsOf(a.time));
  return got[0]?.time ?? null;
}

/** One slot's role, numbered among the slots that share it (`builder 1`, `builder 2`). */
export function roleName(slots: readonly TeamSlot[], slot: TeamSlot): string {
  const same = slots.filter((s) => s.role === slot.role);
  return same.length > 1 ? `${slot.role} ${same.indexOf(slot) + 1}` : slot.role;
}

/** A party of a hand-off as its seat: the member's numbered role, or, for a party the team does not
 *  seat, the name the message carried, printed as it is. */
export function slotName(board: TeamPayload, name: string): string {
  const member = board.members.find((m) => m.name === name);
  const slot = board.team.slots.find((s) => (member === undefined ? s.session === name : s.id === member.slot));
  return slot === undefined ? name : roleName(board.team.slots, slot);
}

// ---- the day's timeline --------------------------------------------------------------------

export interface DayAxis {
  from: number;
  to: number;
  ticks: { at: number; label: string }[];
}

/** The steps a tick can take, smallest first. */
const STEPS_MS = [5, 15, 30, 60, 120, 180, 360].map((minutes) => minutes * 60_000);
const MAX_TICKS = 8;
const HOUR_MS = 3_600_000;

/** The clock the day is drawn on: from the tick before the first thing that happened (a turn, a
 *  hand-off), and never less than the last hour, to now, on the smallest step that keeps the ticks
 *  to eight. */
export function dayAxis(starts: readonly number[], now: number): DayAxis {
  const first = Math.min(now - HOUR_MS, ...starts);
  const step = STEPS_MS.find((ms) => (now - first) / ms <= MAX_TICKS) ?? STEPS_MS[STEPS_MS.length - 1];
  const from = Math.floor(first / step) * step;
  const ticks: DayAxis['ticks'] = [];
  for (let at = from; at <= now; at += step) ticks.push({ at, label: utcClock(at) });
  return { from, to: now, ticks };
}

/** Each member's turns of the day, in member order, oldest first. */
export function lanesOf(board: TeamPayload, turns: readonly TeamTurn[]): { member: TeamMemberRow; turns: TeamTurn[] }[] {
  return board.members.map((member) => ({
    member,
    turns: turns.filter((turn) => turn.member === member.name).sort((a, b) => a.start - b.start),
  }));
}

// ---- the economics -------------------------------------------------------------------------

/** Every token a turn sent in: fresh input plus what it read from and wrote to the prompt cache,
 *  which is what the model was handed. The tally's `input` is the miss bucket alone
 *  (TeamsEconomics.kt), so the three add up to the rows' `in_tokens`. */
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
   *  them in no role, so the page prints the count rather than dropping them. */
  unattributed: number;
  /** Epoch ms of the oldest turn the perf files still hold, which is how far back "lifetime"
   *  reaches. */
  oldest: number | null;
}

/** The daemon's per-role tallies as the page prints them, with their total. */
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
      role: '',
      input: sum.input + row.input,
      output: sum.output + row.output,
      cost: sum.cost === null || row.cost === null ? null : sum.cost + row.cost,
      turns: sum.turns + row.turns,
    }),
    { role: '', input: 0, output: 0, cost: 0, turns: 0 },
  );
  return { rows, total, unattributed: economics.unattributed_turns, oldest: economics.oldest_turn_epoch_millis };
}

/** Turns per slot, named by the member sitting in it or, for an open seat, by its role: the daemon
 *  tallies a slot across every session it ever held, so an open seat can have turns. */
export function turnsPerSlot(board: TeamPayload, economics: TeamEconomicsPayload): { slot: string; name: string; open: boolean; turns: number }[] {
  return seatsOf(board).map(({ slot, member }) => ({
    slot: slot.id,
    name: member?.name ?? roleName(board.team.slots, slot),
    open: member === null,
    turns: economics.slots.find((tally) => tally.slot === slot.id)?.turns ?? 0,
  }));
}
