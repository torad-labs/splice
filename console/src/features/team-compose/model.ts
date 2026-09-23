// The composer's pure half: a team as the operator is writing it, the rules it must meet before
// PUT /api/teams takes it, the edits the form makes, and the writes a save sends. No React and no
// randomness (a new slot's id is passed in), so the suite holds each rule without a DOM.
//
// ARCHIVE NEVER DELETES. A team is the operator's own setup and outlives every session bound to
// it (FEATURES 4.13), so archiving is a FLAG on the team: its slots, their instructions and their
// bindings all stay, and the list keeps printing it below the live teams. There is no delete here,
// and no function in this file returns a team with fewer slots than it was given except
// removeSlot, which edits a draft the operator is still writing.
import type { TeamRow, TeamWrite } from '@entities/team';

export interface DraftSlot {
  /** The slot's id: the daemon refuses a slot without one, keys its tallies and bindings on it, and
   *  carries a binding across a replace only for a slot that keeps its id. */
  id: string;
  /** Carried from the team as read and not edited here: a replace writes the slot whole. */
  model: string | null;
  account: string | null;
  role: string;
  head: string;
  lead: boolean;
  /** The role's standing instructions, sent to the session bound to this slot. */
  instructions: string;
  session: string | null;
}

export interface TeamDraft {
  name: string;
  goal: string;
  /** The features the team is building, one per line in the form. */
  features: string[];
  repo: string;
  slots: DraftSlot[];
  archived: boolean;
}

/** A slot with nothing written in it yet, under the id it will be saved with. */
export function blankSlot(id: string): DraftSlot {
  return { id, model: null, account: null, role: '', head: '', lead: false, instructions: '', session: null };
}

export function blankDraft(slotId: string): TeamDraft {
  return { name: '', goal: '', features: [], repo: '', slots: [{ ...blankSlot(slotId), lead: true }], archived: false };
}

/** A draft of an existing team, every field as the daemon holds it. */
export function draftOf(team: TeamRow): TeamDraft {
  return {
    name: team.name,
    goal: team.goal,
    features: [...team.features],
    repo: team.repo,
    slots: team.slots.map((slot) => ({
      id: slot.id,
      model: slot.model,
      account: slot.account,
      role: slot.role,
      head: slot.head,
      lead: slot.lead,
      instructions: slot.instructions ?? '',
      session: slot.session,
    })),
    archived: team.archived,
  };
}

/** What stops the draft being saved, in the order the form shows its fields. Empty means valid. */
export function validateDraft(draft: TeamDraft): string[] {
  const problems: string[] = [];
  if (draft.name.trim() === '') problems.push('the team needs a name');
  if (draft.repo.trim() === '') problems.push('the team needs a repo');
  if (draft.slots.length === 0) problems.push('the team needs a slot');
  const leads = draft.slots.filter((slot) => slot.lead).length;
  if (draft.slots.length > 0 && leads !== 1) problems.push(`one slot must lead, ${leads} do`);
  draft.slots.forEach((slot, index) => {
    if (slot.role.trim() === '') problems.push(`slot ${index + 1} needs a role`);
    if (slot.head.trim() === '') problems.push(`slot ${index + 1} needs a head`);
  });
  const bound = draft.slots.map((slot) => slot.session).filter((session): session is string => session !== null);
  const twice = bound.find((session, index) => bound.indexOf(session) !== index);
  if (twice !== undefined) problems.push(`${twice} is bound to two slots`);
  return problems;
}

const withSlot = (draft: TeamDraft, index: number, change: Partial<DraftSlot>): TeamDraft => ({
  ...draft,
  slots: draft.slots.map((slot, at) => (at === index ? { ...slot, ...change } : slot)),
});

/** Make one slot the lead. The flag is exclusive: the team has one driver. */
export function setLead(draft: TeamDraft, index: number): TeamDraft {
  return { ...draft, slots: draft.slots.map((slot, at) => ({ ...slot, lead: at === index })) };
}

export function editSlot(draft: TeamDraft, index: number, change: Partial<Omit<DraftSlot, 'id' | 'model' | 'account' | 'lead' | 'session'>>): TeamDraft {
  return withSlot(draft, index, change);
}

export function addSlot(draft: TeamDraft, id: string): TeamDraft {
  return { ...draft, slots: [...draft.slots, blankSlot(id)] };
}

export function removeSlot(draft: TeamDraft, index: number): TeamDraft {
  return { ...draft, slots: draft.slots.filter((_, at) => at !== index) };
}

/** Bind a session to a slot. The slot keeps its role, head and instructions: binding is who is
 *  in the seat, not what the seat is. */
export function bindSession(draft: TeamDraft, index: number, session: string): TeamDraft {
  const name = session.trim();
  return withSlot(draft, index, { session: name === '' ? null : name });
}

/** Unbind a slot. The slot stays: an open seat is part of the team. */
export function unbindSession(draft: TeamDraft, index: number): TeamDraft {
  return withSlot(draft, index, { session: null });
}

/** Archive or restore. Only the flag moves. */
export function setArchived(draft: TeamDraft, archived: boolean): TeamDraft {
  return { ...draft, archived };
}

/** The features field: one per line, blanks dropped. */
export function featuresOf(text: string): string[] {
  return text.split('\n').map((line) => line.trim()).filter((line) => line !== '');
}

/** The body a save sends. Blank instructions are no instructions, so clearing the field clears
 *  them on the daemon rather than saving an empty prompt. */
export function writeOf(draft: TeamDraft): TeamWrite {
  return {
    name: draft.name.trim(),
    goal: draft.goal.trim(),
    features: draft.features,
    repo: draft.repo.trim(),
    archived: draft.archived,
    slots: draft.slots.map((slot) => ({
      id: slot.id,
      role: slot.role.trim(),
      head: slot.head.trim(),
      model: slot.model,
      account: slot.account,
      lead: slot.lead,
      instructions: slot.instructions.trim() === '' ? null : slot.instructions,
      session: slot.session,
    })),
  };
}

/** The slots a save must UNBIND after the replace: bound on the daemon, open in the draft. A replace
 *  keeps a binding its body leaves null (TeamRules.carried), so opening a seat is its own write. */
export function unbindsOf(team: TeamRow, draft: TeamDraft): Record<string, null> {
  const open = new Set(draft.slots.filter((slot) => slot.session === null).map((slot) => slot.id));
  return Object.fromEntries(team.slots.filter((slot) => slot.session !== null && open.has(slot.id)).map((slot) => [slot.id, null]));
}

/** The Idempotency-Key for a create of `body`. A retry of the SAME body reuses the key, so a create
 *  whose answer was lost comes back as the team it made rather than a second one; a changed body
 *  gets a new key, because the daemon refuses a key reused for a different body (409). */
export function keyFor(previous: { body: string; key: string } | null, body: string, mint: () => string): { body: string; key: string } {
  return previous !== null && previous.body === body ? previous : { body, key: mint() };
}
