// The composer's pure half: a team as the operator is writing it, the rules it must meet before
// PUT /api/teams would take it, and the edits the form makes. No React, so the suite holds each
// rule without a DOM.
//
// ARCHIVE NEVER DELETES. A team is the operator's own setup and outlives every session bound to
// it (FEATURES 4.13), so archiving is a FLAG on the team: its slots, their instructions and their
// bindings all stay, and the list keeps printing it below the live teams. There is no delete here,
// and no function in this file returns a team with fewer slots than it was given except
// removeSlot, which edits a draft the operator is still writing.
import type { TeamRow } from '@entities/team';

export interface DraftSlot {
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

export const BLANK_SLOT: DraftSlot = { role: '', head: '', lead: false, instructions: '', session: null };

export function blankDraft(): TeamDraft {
  return { name: '', goal: '', features: [], repo: '', slots: [{ ...BLANK_SLOT, lead: true }], archived: false };
}

/** A draft of an existing team. The team payload carries no feature list or instructions yet
 *  (V4-131 adds them), so those start empty rather than invented. */
export function draftOf(team: TeamRow): TeamDraft {
  return {
    name: team.name,
    goal: team.goal,
    features: [],
    repo: team.repo,
    slots: team.slots.map((slot) => ({ role: slot.role, head: slot.head, lead: slot.lead, instructions: '', session: slot.session })),
    archived: false,
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

export function editSlot(draft: TeamDraft, index: number, change: Partial<Omit<DraftSlot, 'lead' | 'session'>>): TeamDraft {
  return withSlot(draft, index, change);
}

export function addSlot(draft: TeamDraft): TeamDraft {
  return { ...draft, slots: [...draft.slots, { ...BLANK_SLOT }] };
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
