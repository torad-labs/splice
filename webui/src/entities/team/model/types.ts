// The payload contract of the teams entity. Every field is named after what the
// daemon serves, never after a screen:
//   GET /api/teams          -> TeamsPayload    (PENDING V4-131)
//   GET /api/teams/{id}     -> TeamPayload     (PENDING V4-131)
//
// Typed from FEATURES.md section 6, which is where the daemon-side work is
// described. A team is a composition the operator owns: slots with roles, and
// the sessions currently bound to them. A session binds to a slot and unbinds
// when it ends, so the team survives every terminal restart (FEATURES 4.13).
import type { PendingRoute } from '@shared/api';

/** One role slot of a team. `role` is free text; `lead` is the flag that marks
 *  which slot is driving, and it is deliberately not derived from the role name
 *  (an operator may name two slots "lead" and mean only one). */
export interface TeamSlot {
  role: string;
  head: string;
  model?: string | null;
  account?: string | null;
  lead: boolean;
  /** The session currently bound to this slot, or null when the slot is open. */
  session: string | null;
}

/** A session bound to a slot, as the board prints it. Every figure is the
 *  daemon's own; a field its provider does not report arrives as null and is
 *  printed as the honest empty, never as zero. */
export interface TeamMemberRow {
  /** The session's printed name (the client's own label for it). */
  name: string;
  role: string;
  head: string;
  model: string | null;
  account: string | null;
  /** The plan window this session's head is riding, as printed text. */
  window: string | null;
  /** The time of its last turn, as printed text. */
  lastTurn: string | null;
  /** The slot's own word for what it is doing: driving, building, idle. */
  state: string;
  sessionId: string;
  created: string;
  uptime: string;
  turns: number;
  tokensIn: number;
  tokensOut: number;
  /** Estimated cost in USD, or null when no rate is known for the model. */
  costEst: number | null;
  contextLeftPct: number | null;
  scratchpadKb: number | null;
  workspace: string;
  branch: string;
  base: string;
  /** Lines added and removed, as printed text ("+412 -37"). */
  diff: string;
  checks: string;
}

/** One message edge of the team: who wrote to whom, when, and under which
 *  packet. The text is read from the transcript on demand and never stored
 *  (FEATURES 4.13 message edges). */
export interface TeamMessage {
  time: string;
  from: string;
  to: string;
  packet: string;
  text: string;
  /** The head the sender ran on, for the edge's colour on the board. */
  fromHead: string;
}

/** One sampled activity label. It is a SAMPLE: the client is asked for a label
 *  about every 30 seconds, so the feed says so rather than implying a log. */
export interface TeamActivity {
  time: string;
  member: string;
  activity: string;
  detail: string;
}

export interface TeamRow {
  id: string;
  name: string;
  goal: string;
  repo: string;
  /** Epoch millis, so a page formats them in its own zone. */
  created_epoch_millis: number;
  updated_epoch_millis: number;
  slots: TeamSlot[];
}

export interface TeamPayload {
  team: TeamRow;
  members: TeamMemberRow[];
  messages: TeamMessage[];
  activity: TeamActivity[];
  /** True when the sender's next turn is a cold cache after an instructions edit. */
  coldCacheHint?: boolean;
}

export interface TeamsPayload {
  teams: TeamRow[];
}

/** What a store holds: the payload, or the honest empty naming the work item. */
export type TeamsState = TeamsPayload | PendingRoute;
export type TeamState = TeamPayload | PendingRoute;

/** The v0.4.0 item that will serve the team routes (FEATURES.md section 6). */
export const PENDING_TEAMS = 'V4-131';
