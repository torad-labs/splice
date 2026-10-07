// Teams as the daemon writes them. There is no GET /api/teams/{id}: a team's board is composed from the
// teams list, the day's chat and activity (`from`/`to` bound the viewer's local day in epoch ms), and lifetime
// economics, plus /api/sessions, whose rows carry the team they are bound to. Every field is the daemon's own name
// and nullability (a nullable field arrives as null, never absent).

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
 *  when asked for; when it could not be found `text` is null and `missing_reason` gives a
 *  path-free explanation. `packet` has no wire source and is always null (`packet_note` says why). */
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
  state?: 'on' | 'off' | 'deleted' | 'not_kept' | 'partially_kept';
  reason?: string;
  oldest_kept_epoch_millis?: number;
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
  state?: 'on' | 'off' | 'deleted' | 'not_kept' | 'partially_kept';
  reason?: string;
  oldest_kept_epoch_millis?: number;
  day_start_epoch_millis: number;
  /** Says the labels are samples, about one per 30 s while a session works. */
  sample_interval_note: string;
  /** How many label queries went upstream: the difference between "nothing sampled" and "the
   *  client stopped asking" (FEATURES 4.13). */
  upstream_label_queries: number;
  entries: TeamActivityEntry[];
}

/** One lifetime tally (TeamsEconomics.kt PerfTally.json). Dollars retain the priced portion;
 *  `cost_usd` is null when there are turns and none could be priced. */
export interface TeamTally {
  turns: number;
  tokens: { input: number; cache_read: number; cache_write: number; output: number };
  cost_usd: number | null;
  unpriced_turns: number;
  /** Turns with incomplete or missing usage reports; totals retain reported amounts. Absent on older daemons. */
  unreported_usage_turns?: number;
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

/** The viewer's local day, epoch ms: [from, to). */
export interface TeamDay {
  from: number;
  to: number;
}
