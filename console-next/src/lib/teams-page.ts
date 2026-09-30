// The Teams pages' arithmetic: a team as the operator is writing it, the rules it must meet before PUT /api/teams
// takes it, the edits the form makes, and what a team's board reads as. Pure: a new seat's id is passed in.
//
// ARCHIVE NEVER DELETES. A team outlives every session bound to it, so archiving is a flag: its seats, their
// instructions and their bindings stay. Nothing here returns a team with fewer seats than it was given except
// removeSeat, which edits a draft the operator is still writing.
import { M } from './words-teams';
import type { SessionRow } from '../types/sessions';
import type { TeamDay, TeamRow, TeamSlot, TeamWrite } from '../types/teams';

const C = M.compose;

export interface DraftSeat {
  id: string;
  /** Carried from the team as read and not edited here: a replace writes the slot whole. */
  model: string | null;
  account: string | null;
  role: string;
  head: string;
  lead: boolean;
  instructions: string;
  session: string | null;
}

export interface TeamDraft {
  name: string;
  goal: string;
  /** One per line in the form. */
  features: string;
  repo: string;
  seats: DraftSeat[];
  archived: boolean;
}

export const blankSeat = (id: string): DraftSeat => ({ id, model: null, account: null, role: '', head: '', lead: false, instructions: '', session: null });
export const blankDraft = (seatId: string): TeamDraft => ({ name: '', goal: '', features: '', repo: '', seats: [{ ...blankSeat(seatId), lead: true }], archived: false });

export const draftOf = (team: TeamRow): TeamDraft => ({
  name: team.name,
  goal: team.goal,
  features: team.features.join('\n'),
  repo: team.repo,
  seats: team.slots.map((slot) => ({ id: slot.id, model: slot.model, account: slot.account, role: slot.role, head: slot.head, lead: slot.lead, instructions: slot.instructions ?? '', session: slot.session })),
  archived: team.archived,
});

/** What stops the draft being saved, one line each, in the order the form shows its fields. Empty means valid. */
export function validateDraft(draft: TeamDraft): string[] {
  const problems: string[] = [];
  if (draft.name.trim() === '') problems.push(C.noName);
  if (draft.repo.trim() === '') problems.push(C.noRepo);
  if (draft.seats.length === 0) problems.push(C.noSeat);
  else if (draft.seats.filter((seat) => seat.lead).length !== 1) problems.push(C.oneLead);
  draft.seats.forEach((seat, index) => {
    if (seat.role.trim() === '') problems.push(`${C.seat(index + 1)} ${C.noRole}`);
    if (seat.head.trim() === '') problems.push(`${C.seat(index + 1)} ${C.noPlan}`);
  });
  const bound = draft.seats.flatMap((seat) => (seat.session === null ? [] : [seat.session]));
  const twice = bound.find((session, index) => bound.indexOf(session) !== index);
  if (twice !== undefined) problems.push(`${twice} ${C.onTwo}`);
  return problems;
}

export const editSeat = (draft: TeamDraft, index: number, change: Partial<DraftSeat>): TeamDraft => ({
  ...draft,
  seats: draft.seats.map((seat, at) => (at === index ? { ...seat, ...change } : seat)),
});
/** The flag is exclusive: the team has one driver. */
export const setLead = (draft: TeamDraft, index: number): TeamDraft => ({ ...draft, seats: draft.seats.map((seat, at) => ({ ...seat, lead: at === index })) });
export const addSeat = (draft: TeamDraft, id: string): TeamDraft => ({ ...draft, seats: [...draft.seats, blankSeat(id)] });
export const removeSeat = (draft: TeamDraft, index: number): TeamDraft => ({ ...draft, seats: draft.seats.filter((_, at) => at !== index) });

export const featuresOf = (text: string): string[] => text.split('\n').map((line) => line.trim()).filter((line) => line !== '');

/** The body a save sends. Blank instructions are none, so clearing the field clears them on the daemon. */
export function writeOf(draft: TeamDraft): TeamWrite {
  return {
    name: draft.name.trim(),
    goal: draft.goal.trim(),
    features: featuresOf(draft.features),
    repo: draft.repo.trim(),
    archived: draft.archived,
    slots: draft.seats.map((seat) => ({
      id: seat.id,
      role: seat.role.trim(),
      head: seat.head.trim(),
      model: seat.model,
      account: seat.account,
      lead: seat.lead,
      instructions: seat.instructions.trim() === '' ? null : seat.instructions,
      session: seat.session,
    })),
  };
}

/** The Idempotency-Key for a create of `body`: a retry of the SAME body reuses it, a changed body gets a new one
 *  (the daemon refuses a key reused for a different body). */
export function keyFor(previous: { body: string; key: string } | null, body: string, mint: () => string): { body: string; key: string } {
  return previous !== null && previous.body === body ? previous : { body, key: mint() };
}

// ── the board ───────────────────────────────────────────────────────────────────────────────────

/** A team's seats, the lead first and the rest in the order they were composed. */
export const seatsOf = (team: TeamRow): TeamSlot[] => [...team.slots].sort((a, b) => Number(b.lead) - Number(a.lead));

/** The session in a seat, from the live registry. */
export const sessionIn = (slot: TeamSlot, rows: readonly SessionRow[]): SessionRow | null =>
  slot.session === null ? null : (rows.find((row) => row.session_id === slot.session) ?? null);

/** The local day `back` days before today, [from, to) in epoch ms. */
export function dayOf(now: number, back: number): TeamDay {
  const midnight = new Date(now);
  midnight.setHours(0, 0, 0, 0);
  const from = new Date(midnight.getFullYear(), midnight.getMonth(), midnight.getDate() - back).getTime();
  const to = new Date(midnight.getFullYear(), midnight.getMonth(), midnight.getDate() - back + 1).getTime();
  return { from, to };
}

/** `Today`, `Yesterday`, or the date. */
export function dayWords(now: number, back: number): string {
  if (back === 0) return M.today;
  if (back === 1) return 'Yesterday';
  return new Date(dayOf(now, back).from).toLocaleDateString([], { month: 'short', day: 'numeric' });
}

export function teamLede(team: TeamRow, working: number): string {
  const open = team.slots.filter((slot) => slot.session === null).length;
  const goal = team.goal.trim() === '' ? M.noGoal : team.goal.trim();
  return `${goal} ${M.ledeSeats(working, team.slots.length, open)}`;
}
