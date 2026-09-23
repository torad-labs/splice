// The teams entity's contract, in two halves.
//
// THE WIRE (V4-131, features/sessions/.../http/TeamsRoutes.kt and TeamsReads.kt). The read is SPLIT
// on purpose: there is no GET /api/teams/{id}. A team's board is composed from
//   GET /api/teams                       -> TeamsPayload       every team, archived included
//   GET /api/teams/{id}/chat?day=        -> TeamChatPayload    the day's messages, text read on demand
//   GET /api/teams/{id}/activity?day=    -> TeamActivityPayload the day's sampled activity labels
//   GET /api/teams/{id}/economics        -> TeamEconomicsPayload lifetime tallies per role and slot
// plus /api/sessions, whose rows carry the team they are bound to. The writes answer the saved team:
//   PUT  /api/teams                      create, under an Idempotency-Key header
//   PUT  /api/teams/{id}                 replace the composition (keeps a binding the body leaves out)
//   PUT  /api/teams/{id}/sessions        {bindings: {slot id: session | null}}, the only way to unbind
// Every field below is the daemon's own name and nullability (the serializer encodes defaults, so a
// nullable field arrives as null, never absent). tools/e2e/probes/console-wire-keys.ts checks the
// reads against a booted daemon.
//
// THE BOARD. TeamPayload and the rows under it are what the board widgets draw: one team composed
// from the reads above by pages/teams/board.ts. They are not a daemon payload and no route serves
// them whole; a figure no route reports is null and the board prints its absence.
import type { PendingRoute } from '@shared/api';

// ---- the wire ------------------------------------------------------------------------------

/** One role slot (TeamStore.kt TeamSlot). `lead` is a flag, never derived from the role's name. */
export interface TeamSlot {
  id: string;
  role: string;
  head: string;
  model: string | null;
  account: string | null;
  lead: boolean;
  /** Standing instructions appended to the bound session's system prompt. */
  instructions: string | null;
  /** The session bound to this slot, or null when the seat is open. */
  session: string | null;
  instructions_updated_epoch_millis: number | null;
  /** Every session ever bound here, oldest first: economics joins all of them. */
  sessions_history: string[];
}

/** One team (TeamStore.kt Team). Archiving is a flag; nothing is ever deleted. */
export interface TeamRow {
  id: string;
  name: string;
  goal: string;
  features: string[];
  /** The git root the team works in (the project id). */
  repo: string;
  archived: boolean;
  created_epoch_millis: number;
  updated_epoch_millis: number;
  slots: TeamSlot[];
  idempotency_key: string | null;
  create_fingerprint: string | null;
}

export interface TeamsPayload {
  teams: TeamRow[];
}

/** A team as the console writes it. The daemon mints the team id on create and stamps the times;
 *  every slot carries an id the console mints, because the daemon refuses a slot without one. */
export interface TeamWrite {
  name: string;
  goal: string;
  features: string[];
  repo: string;
  archived: boolean;
  slots: {
    id: string;
    role: string;
    head: string;
    /** Carried from the team as read: a replace writes the slot whole, so leaving these out would
     *  clear them (TeamRules.carried keeps only the binding and the history). */
    model: string | null;
    account: string | null;
    lead: boolean;
    instructions: string | null;
    session: string | null;
  }[];
}

/** One message of the day (TeamsEdges.kt message()). The text is read from the SENDER's transcript
 *  when asked for; when it could not be found `text` is null and `missing_reason` names the path
 *  read. `packet` has no wire source and is always null (`packet_note` says why). */
export interface TeamChatMessage {
  at: number;
  from: string;
  from_slot: string | null;
  from_head: string | null;
  to: string;
  to_slot: string | null;
  packet: null;
  text: string | null;
  text_source: string | null;
  missing_reason: string | null;
}

export interface TeamChatPayload {
  team_id: string;
  day_start_epoch_millis: number;
  packet_note: string;
  messages: TeamChatMessage[];
}

/** One sampled activity label (TeamsReads.kt activity()). */
export interface TeamActivityEntry {
  at: number;
  session: string;
  slot: string | null;
  head: string;
  label: string;
  detail: string | null;
}

export interface TeamActivityPayload {
  team_id: string;
  day_start_epoch_millis: number;
  /** Says the labels are samples, about one per 30 s while a session works. */
  sample_interval_note: string;
  /** How many label queries went upstream: the difference between "nothing sampled" and "the
   *  client stopped asking" (FEATURES 4.13). */
  upstream_label_queries: number;
  entries: TeamActivityEntry[];
}

/** One lifetime tally (TeamsEconomics.kt PerfTally.json). `cost_usd` is null when any turn in it
 *  had no rate card: a partial sum would be a confident wrong number. */
export interface TeamTally {
  turns: number;
  tokens: { input: number; cache_read: number; cache_write: number; output: number };
  cost_usd: number | null;
  unpriced_turns: number;
  last_turn_at_epoch_millis: number | null;
}

export interface TeamRoleTally extends TeamTally {
  role: string;
}

export interface TeamSlotTally extends TeamTally {
  slot: string;
  /** "pass" or "fail" from the slot's newest tallied outcome; null before its first turn. */
  checks: string | null;
  checks_source: string;
}

export interface TeamEconomicsPayload {
  team_id: string;
  heads_read: string[];
  /** Turns on the team's heads with no session tag: counted, never dropped. */
  unattributed_turns: number;
  /** The oldest turn the perf files still hold: a lifetime total reaches only this far back. */
  oldest_turn_epoch_millis: number | null;
  roles: TeamRoleTally[];
  slots: TeamSlotTally[];
}

/** The three day-scoped and lifetime reads of the opened team, each its own outcome: one panel
 *  failing does not blank the others. */
export interface TeamPanels {
  teamId: string;
  chat: TeamChatPayload | { error: string };
  activity: TeamActivityPayload | { error: string };
  economics: TeamEconomicsPayload | { error: string };
}

// ---- the board -----------------------------------------------------------------------------

/** A session bound to a slot, as the board prints it. A figure no route reports is null. */
export interface TeamMemberRow {
  /** The slot the session is bound to: the key of the daemon's per-slot tallies. */
  slot: string;
  /** The session's printed name (the client's own label), or its id when it has none. */
  name: string;
  role: string;
  head: string;
  model: string | null;
  account: string | null;
  /** The plan window this session's head is riding, as printed text. */
  window: string | null;
  /** The time of its last turn, as printed text. */
  lastTurn: string | null;
  /** The session's own status word, its availability when it is not live, or `unlisted` when the
   *  registry does not list it. */
  state: string;
  sessionId: string;
  /** HH:MM:SS the session started, when the registry reports it. */
  created: string | null;
  uptime: string | null;
  turns: number | null;
  tokensIn: number | null;
  tokensOut: number | null;
  /** Lifetime cost in USD, or null when a turn had no rate card. */
  costEst: number | null;
  contextLeftPct: number | null;
  scratchpadKb: number | null;
  workspace: string | null;
  branch: string | null;
  base: string | null;
  /** Lines added and removed, as printed text ("+412 -37"). */
  diff: string | null;
  checks: string | null;
}

/** One message of the team as the board prints it. */
export interface TeamMessage {
  time: string;
  from: string;
  to: string;
  packet: string;
  text: string;
  /** The head the sender ran on, for the edge's colour on the board. */
  fromHead: string;
}

/** One sampled activity label. It is a SAMPLE, and the feed says so rather than implying a log. */
export interface TeamActivity {
  time: string;
  member: string;
  activity: string;
  detail: string;
}

/** One team composed for the board (pages/teams/board.ts). */
export interface TeamPayload {
  team: TeamRow;
  members: TeamMemberRow[];
  messages: TeamMessage[];
  activity: TeamActivity[];
  /** True when the sender's next turn is a cold cache after an instructions edit. */
  coldCacheHint?: boolean;
}

/** What a store holds: the payload, or the honest empty naming the work item. */
export type TeamsState = TeamsPayload | PendingRoute;

/** The v0.4.0 item that serves the team routes, for a daemon older than it (FEATURES.md section 6). */
export const PENDING_TEAMS = 'V4-131';
