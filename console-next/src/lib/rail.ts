// The team rail of a session's page: who this session works with, and what rode between them. A session bound
// to a team shows the team's seats (the lead first); one that is not shows the sessions it exchanged
// hand-offs with, which is the team it actually has.
import type { HandedEdge, SessionRow } from '../types/sessions';
import type { TeamRow } from '../types/teams';
import { peerLabel, sessionKey, sessionLabel, stateOf } from './sessions';
import type { SessionState } from './sessions';

export interface Seat {
  /** The session's key when the registry lists it, else a stable key of the edge's own. */
  key: string;
  label: string;
  head: string | null;
  /** Null when the registry does not list the session: an unlisted seat has no state to claim. */
  state: SessionState | null;
  here: boolean;
  /** The seat's role in its team, when there is one. */
  role: string | null;
  lead: boolean;
}

export interface Ride {
  seat: string;
  direction: 'in' | 'out';
  at: number;
  /** What was handed over, or null when the sender's transcript no longer holds it. */
  text: string | null;
}

export interface Rail {
  team: string | null;
  seats: Seat[];
  rides: Ride[];
}

const seatOf = (row: SessionRow, here: boolean, now: number, slot?: { role: string; lead: boolean }): Seat => ({
  key: sessionKey(row), label: sessionLabel(row), head: row.head, state: stateOf(row, now), here, role: slot?.role ?? null, lead: slot?.lead ?? false,
});

/** The peer on the other end of one edge, as a registry row when one answers to it. */
function peerRow(rows: readonly SessionRow[], edge: HandedEdge): SessionRow | undefined {
  return edge.direction === 'out' ? rows.find((row) => row.address === edge.to) : rows.find((row) => row.session_id === edge.from);
}

export function railOf(here: SessionRow, rows: readonly SessionRow[], edges: readonly HandedEdge[], teams: readonly TeamRow[], now: number): Rail {
  const team = here.team === undefined || here.team === null ? undefined : teams.find((candidate) => candidate.id === here.team);
  const seats: Seat[] = [];
  const seen = new Set<string>();
  const add = (seat: Seat): void => {
    if (seen.has(seat.key)) return;
    seen.add(seat.key);
    seats.push(seat);
  };

  if (team !== undefined) {
    const ordered = [...team.slots].sort((a, b) => Number(b.lead) - Number(a.lead));
    for (const slot of ordered) {
      const row = slot.session === null ? undefined : rows.find((candidate) => candidate.session_id === slot.session);
      if (row !== undefined) add(seatOf(row, row.session_id === here.session_id, now, slot));
      else if (slot.session !== null) add({ key: slot.session, label: slot.role, head: slot.head, state: null, here: false, role: slot.role, lead: slot.lead });
    }
  }
  add(seatOf(here, true, now));

  const rides: Ride[] = [];
  for (const edge of [...edges].sort((a, b) => a.at - b.at)) {
    const row = peerRow(rows, edge);
    const key = row === undefined ? (edge.direction === 'out' ? edge.to : edge.from) : sessionKey(row);
    if (row !== undefined) add(seatOf(row, false, now));
    else add({ key, label: peerLabel(rows, edge), head: null, state: null, here: false, role: null, lead: false });
    rides.push({ seat: key, direction: edge.direction, at: edge.at, text: edge.text });
  }
  return { team: team?.name ?? null, seats, rides };
}
