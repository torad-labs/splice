// The payload contracts of the performance entity. Every field below is named after the daemon
// code that serves it, never after a screen:
//   GET /api/perf/summary -> PerfSummaryPayload  (splice/control/api/PerfSummary.kt:65-80)
//   GET /api/perf/turns   -> PerfTurnsWire       (V4-127, PerfRoutes.kt), merged into TurnRow[]
// The numeric field names are the PerfKeys catalogue (core/perf/PerfKeys.kt), so a mark renamed
// there renames here.
import type { PendingRoute } from './budget';

/** {count, p50, p95, max} of one field over a row set — the ONE shape both perf routes print
 *  (PerfSummary.stats). Percentiles are nearest-rank on the sorted values, per the daemon. */
export interface PerfStats {
  count: number;
  p50: number;
  p95: number;
  max: number;
}

/** The three windows the summary route accepts. An unknown label is a 400, never a fallback. */
export const PERF_WINDOWS = ['1h', '24h', '7d'] as const;

export type PerfWindowLabel = (typeof PERF_WINDOWS)[number];

/**
 * GET /api/perf/summary?window=1h|24h|7d — one windowed summary per head.
 *
 * The optional block is present only when the window holds rows (count > 0). An empty window ships
 * `count: 0` and `empty: true` and no metrics at all, so a view must render "no rows" from `empty`
 * and never read a missing percentile as a zero-latency turn.
 *
 * `coverage` describes how much of the WINDOW the perf files can actually speak for: `clamped`
 * means they hold less than the window, `coverage_known: false` means a generation could not be
 * read so the true coverage is unknown (never silently treated as clamped), and `note` is the
 * daemon's own sentence for either case.
 */
export interface PerfSummaryHead {
  key: string;
  label: string;
  window: PerfWindowLabel;
  /** Turns in the window; the code-mode steps splice answered itself are `local_steps`, not counted here. */
  count: number;
  /** Steps splice answered itself in the window; absent from a daemon older than the field. */
  local_steps?: number;
  empty: boolean;
  coverage_known: boolean;
  clamped: boolean;
  /** ms of the window the files cover at all, already capped at the window length. */
  covers_ms: number;
  note?: string;
  read_error?: string;
  skipped_lines?: number;
  time_before_first_byte_ms?: PerfStats;
  time_streaming_ms?: PerfStats;
  total_ms?: PerfStats;
  /** Epoch ms of the head's newest turn in the store, whatever the window: null when it has never
   *  run one, absent from a daemon older than the field (PerfSummary.json). */
  last_ts?: number | null;
  /** Outcome tag -> turn count. A row whose outcome could not be parsed is filed under "?". */
  outcomes?: Record<string, number>;
  /** Failing requests over all requests, excluding successes, stopped endings and unattributed "?". */
  failure_share?: number;
  /** Per failing tag over all requests; stopped endings remain in outcomes, not failure_shares. */
  failure_shares?: Record<string, number>;
  unattributed?: number;
  retries?: number;
  refreshes?: number;
  /** null when the window holds no input tokens at all: no ratio exists, and 0 would be a claim. */
  cache_hit_ratio?: number | null;
  peak_inflight?: number;
  /** A LOWER BOUND on the async file-io writes the daemon dropped inside the window; a non-zero
   *  value means rows are missing from every figure above. */
  io_drops_in_window?: number;
}

export interface PerfSummaryPayload {
  window: PerfWindowLabel;
  heads: PerfSummaryHead[];
  /** Exact percentile of all recorded request timings in the window, not a percentile of command medians. */
  time_before_first_byte_ms?: PerfStats;
}

/** The stage marks, in pipeline order, ms since the request arrived (PerfKeys.markOrder). */
export const MARK_KEYS = [
  'recv',
  'parse',
  'build',
  'gate',
  'headers',
  'first_byte',
  'first_frame',
  'first_delta',
  'stream_end',
  'finish',
  'total',
] as const;

export type MarkKey = (typeof MARK_KEYS)[number];

/**
 * One turn as the page renders it: a row of GET /api/perf/turns?head=&n=&since= (PerfRoutes.rowJson)
 * with the head it came from stamped on, built by the turns wire merge. The route answers per head
 * (a head is required, ConsoleRoutesTest pins it), so the console reads every head and merges.
 *
 * Every numeric field is OPTIONAL and absent means the row does not carry it: a failed turn has no
 * `stream_end`, and a dialect that cannot defer tools reports no `tools_deferred`. Absent must
 * render as "not reported for this turn", never as 0, which is why none of them carry a default.
 */
export interface TurnRow {
  /** Which head's file the row came from. The rows live one file per head
   *  (`<head>-perf.jsonl`) and the route aggregates them, so the route adds this field; it is not
   *  in the file itself (PerfStats.record writes ts/model/outcome/compact/session/account/
   *  cache_cold plus the numeric snapshot). */
  head: string;
  /** Epoch ms the turn was recorded. */
  ts: number;
  /** Null for a legacy or torn row that never carried it: the daemon reports the absence rather
   *  than filling in a value the file does not contain (PerfRoutes.rowJson). */
  model: string | null;
  /** The daemon's outcome tag; "?" is its own tag for a row whose outcome would not parse. */
  outcome: string;
  /** Present only for a proven refused connection to a loopback runtime, never for an ordinary reset. */
  refused_runtime_port?: number;
  /** Null for a legacy or torn row, as `model`. */
  compact: boolean | null;
  /** First 8 characters of the client's session id, or absent when the turn carried none. */
  session?: string;
  /** The account label, written only after an account switch. */
  account?: string;
  /** Whether the account switch left the prompt cache cold; written only beside `account`. */
  cache_cold?: boolean;
  /** The trace turn that recorded this turn's exact request and answer when capture was on. */
  turn?: string;
  /** The full client session id, for locating its own local transcript. The short `session` tag
   *  remains the visible column and filter; neither id is a request body or credential. */
  session_id?: string;
  /** The id splice emitted in the client-facing response. The transcript joins on this id,
   *  not the upstream's id or a timestamp shared by concurrent turns. */
  response_message_id?: string;
  /** The daemon's USD figure at this head's model card, null when it cannot price the turn. */
  cost_usd?: number | null;
  recv?: number;
  parse?: number;
  build?: number;
  gate?: number;
  headers?: number;
  first_byte?: number;
  first_frame?: number;
  first_delta?: number;
  stream_end?: number;
  finish?: number;
  total?: number;
  auth_ms?: number;
  backoff_ms?: number;
  refresh_ms?: number;
  write_ms?: number;
  usage_ms?: number;
  attempts?: number;
  /** 1 when code mode served this step with no upstream attempt (PerfKeys.LOCAL_STEP): a step of a turn, not a turn. Absent on a turn. */
  local_step?: number;
  retries?: number;
  refreshes?: number;
  post_send_retries?: number;
  reanchors?: number;
  stall_ms?: number;
  req_bytes?: number;
  upstream_req_bytes?: number;
  sse_bytes_in?: number;
  bytes_out?: number;
  events_in?: number;
  frames_out?: number;
  content_frames_out?: number;
  frames_skipped?: number;
  in_tokens?: number;
  cached_tokens?: number;
  cache_write_tokens?: number;
  out_tokens?: number;
  inflight?: number;
  async_io_drops?: number;
  tools_eager?: number;
  tools_deferred?: number;
  search_rounds?: number;
}

/** One row exactly as PerfRoutes.rowJson writes it: the numeric bag, then the named facts, each of
 *  which is null (never absent) when the row did not carry it. */
export type TurnRowWire = Omit<TurnRow, 'head' | 'session' | 'account' | 'cache_cold' | 'turn' | 'session_id' | 'response_message_id'> & {
  session: string | null;
  account: string | null;
  cache_cold: boolean | null;
  turn: string | null;
  session_id: string | null;
  response_message_id: string | null;
};

/** Full filtered-window numbers, computed before any displayed-row clamp. */
export interface TurnUsageStats {
  requests: number;
  input_tokens: number | null;
  cached_tokens: number | null;
  output_tokens: number | null;
  cost_usd: number | null;
  cache_share: number | null;
  unpriced_requests: number;
  /** The same requests by cause, summing to unpriced_requests. A daemon older than the split omits them. */
  unpriced_uncounted_requests?: number;
  unpriced_plan_requests?: number;
  unpriced_undeclared_requests?: number;
  missing_input_requests: number;
  missing_output_requests: number;
  missing_cache_requests: number;
  /** Replies these requests cut off with a new message while they streamed: billed upstream, their tokens never
   *  reported. A daemon older than the count omits it. */
  cut_source_rounds?: number;
}

export interface TurnUsageGroup extends TurnUsageStats { key: string | null }
export interface TurnSessionUsage extends TurnUsageGroup {
  last_model: string | null;
  last_model_ts_epoch_ms: number | null;
}

export interface TurnUsageWire {
  sessions?: TurnSessionUsage[];
  totals: TurnUsageStats;
  models: TurnUsageGroup[];
  accounts: TurnUsageGroup[];
  days: TurnUsageGroup[];
}

/** One head's block. A head the daemon cannot read is listed with `error` in place of its rows, so
 *  the window fields and `rows` are absent on that branch (PerfRoutes.turns). */
export interface PerfTurnsHeadWire {
  key: string;
  label: string;
  count?: number;
  returned?: number;
  /** Whether the newest-n clamp cut rows from this head's window. */
  truncated?: boolean;
  /** The oldest timestamp a valid row holds at all; null when the source cannot say. */
  oldest_held_ts?: number | null;
  read_error?: string;
  skipped_lines?: number;
  error?: string;
  rows?: TurnRowWire[];
  /** Absent on an older daemon; never replaced with sums over its clamped rows. */
  usage?: TurnUsageWire;
}

/** What GET /api/perf/turns narrows a head's window by, before its newest-n clamp (TurnsFilter.kt). `outcome` is a tag, or
 *  `failed` for every row that ended anywhere but ok, the unattributed `?` excluded. `unattributed` asks for the rows that
 *  carry no model or no account. `compact` true asks for compactions, false for every request that was not one. `local`
 *  false leaves out the steps splice answered itself. An absent field asks nothing. */
export interface PerfTurnsFilter {
  outcome?: string;
  model?: string;
  account?: string;
  session?: string;
  unattributed?: 'model' | 'account';
  compact?: boolean;
  local?: boolean;
}

/** GET /api/perf/turns?head=&n=&since= exactly as the daemon writes it. The type
 *  tools/e2e/probes/console-wire-keys.ts checks against a live daemon. */
export interface PerfTurnsWire {
  since: number;
  n: number;
  heads: PerfTurnsHeadWire[];
}

/** A head whose turns could not be read, and the daemon's reason in its own words. */
export interface UnreadHead {
  head: string;
  reason: string;
}

/** A head whose window held more rows than the route serves (its `truncated` flag): `returned` are
 *  in hand, the newest of `count`, or of a count the daemon did not write (null). */
export interface TruncatedHead {
  head: string;
  count: number | null;
  returned: number;
}

/** The pending shape lives with the budget types (types/budget.ts); re-exported here for the callers that
 *  already address this slice. */
export type { PendingRoute };

/**
 * GET and PUT /api/heads/{head}/capture, exactly as CaptureRoutes.captureJson writes BOTH answers
 * (features/turns/src/main/kotlin/splice/head/wire/CaptureRoutes.kt:130-139): one head's
 * trace SETTINGS, never a body. No route serves a captured body; the daemon's own pointer for
 * reading one is the CLI, `splice trace <head>` (DoctorTraceChecks.kt:47).
 *
 * GET reports the head's EFFECTIVE settings, what the daemon records right now. PUT writes
 * `[heads.<key>.overrides]` in splice.toml and answers with what it WROTE. The two differ after a
 * write because `restart_required` is true on every answer today: the head's trace store is built
 * once, at head assembly (HeadTraceStores.forHead), from the config the daemon booted with.
 */
export interface CaptureWire {
  /** The head's key, as the daemon resolved the name in the path. */
  head: string;
  enabled: boolean;
  retention_days: number;
  /** The per-record body cap, in characters. */
  max_body_chars: number;
  restart_required: boolean;
}

/** One traced turn as GET /api/heads/{head}/trace lists it (V4-239, TraceRoute.summary): the line
 *  `splice trace` prints, as fields. An open turn has no record closing it yet: `outcome` is null and
 *  its rounds and attempts are the attempts on disk. */
export interface TracedTurnWire {
  id: string;
  /** Epoch milliseconds of the turn's first record. */
  ts: number;
  session: string | null;
  model: string;
  compact: boolean;
  open: boolean;
  outcome: string | null;
  /** Human failure text, present when the daemon recorded a failed turn's cause. */
  failure_sentence: string | null;
  rounds: number | null;
  attempts: number | null;
  total_ms: number | null;
}

/** The daemon retained the reference and metadata but could not read or store its body. */
export interface UnavailableTraceBody {
  unavailable: true;
  reason?: string;
  truncated?: boolean;
}

export type TraceBody = string | UnavailableTraceBody;

/** A request or response side of a traced record, with exact bodies or explicit unavailable markers. */
export interface TraceSide {
  method?: string;
  path?: string;
  status?: number | string;
  stream?: boolean;
  encoding?: string;
  headers?: Record<string, string>;
  body?: TraceBody;
  /** A response's text, where a request carries `body`. */
  text?: TraceBody;
  truncated?: boolean;
}

/** One record of a traced turn: an upstream attempt, or the turn record that closes it. */
export interface TraceRecord {
  kind: 'attempt' | 'turn';
  turn: string;
  ts: number;
  attempt?: number;
  round?: number;
  transport?: string;
  url?: string;
  durationMs?: number;
  failure?: string;
  request?: TraceSide;
  response?: TraceSide;
  client?: TraceSide;
  answer?: TraceSide;
  outcome?: string;
  failure_sentence?: string;
}

/** GET /api/heads/{head}/trace?turn=ID: that turn's summary and its records as written. */
export interface TraceTurnWire {
  head: string;
  turn: TracedTurnWire;
  /** What the turn cost in USD, priced by the daemon at the head's card for the turn's model (V4-345,
   *  TraceRoute.turnJson): null for a model with no card, never $0; absent for a turn still open. */
  cost_usd?: number | null;
  records: TraceRecord[];
}

/** A kept turn's read: its records, or the daemon's sentence that its trace no longer holds it (a
 *  400), which the request detail prints as a state and never as a failure. */
export type KeptTurn = { read: TraceTurnWire } | { gone: string };

/** A redacted message Claude Code wrote locally, not the raw request splice sent upstream. */
export interface ConversationMessageWire {
  index: number;
  role: 'user' | 'assistant' | 'system' | 'tool';
  text: string;
  ts?: number;
  tool?: string;
  tool_use_id?: string;
  result?: boolean;
  /** True on the client-facing reply this perf row names, false on earlier context. */
  selected?: boolean;
}

/** GET /api/heads/{head}/conversation: the context through one client-facing response id, or why
 *  it cannot be shown. The `off` branch proves the daemon read no private file for that request. */
export type TranscriptConversationWire =
  | { state: 'found'; session_id: string; response_message_id: string; messages: ConversationMessageWire[]; earlier: number }
  | { state: 'missing' | 'off' | 'unavailable'; reason: string };

/** One upstream request body a head sent, as its wire tap kept it (WireTap.json). */
export interface WireRecordWire {
  ts: number;
  session?: string;
  model: string;
  compact: boolean;
  body: string;
}

/** GET /api/heads/{head}/wire (V4-239): what `splice wire` prints, the tap's last `keep` bodies. */
export interface WireTapWire {
  key: string;
  keep: number;
  records: WireRecordWire[];
}

/** A wire read: the tap's bodies, or the daemon's sentence saying the tap is off (a 409), which is a
 *  state the drawer prints and never an empty list. */
export type WireRead = { tap: WireTapWire } | { off: string };

/** One head's capture as the console holds it: what runs, what this console last wrote, and the
 *  daemon's own words when it refused a write. Built by the capture read, never by hand. */
export interface CaptureState {
  /** The GET, re-read after every write: what the daemon records now. */
  running: CaptureWire;
  /** The last PUT's answer for this head in this console session, or null before one: what
   *  splice.toml says, which runs only after the restart the answer asks for. */
  written: CaptureWire | null;
  /** The daemon's sentence refusing the last write, verbatim, or null. */
  refused: string | null;
  /** A write is in flight; the switch ignores a second press until it lands. */
  writing: boolean;
}

/** One in-flight turn, read off a head's gate snapshot (GateLive on GET /api/heads, FEATURES 2.4).
 *  camelCase because it is NOT a wire row: the derivation builds it from the payload. */
export interface InflightTurn {
  head: string;
  label: string;
  compact: boolean;
  /** "connect" before the upstream answered, "streaming" after. */
  phase: string;
  ageMs: number;
  idleMs: number;
}

/** What the turns store holds: the live set beside the landed rows. */
export interface TurnsState {
  inflight: InflightTurn[];
  landed: TurnRow[];
  /** Heads whose turns are missing from `landed`, each with why. Empty when every head was read: a
   *  head that failed is named rather than silently dropped from a list that would then look
   *  complete. */
  unread: UnreadHead[];
  /** Heads the route clamped: their earliest rows in the asked window are missing from `landed`. A
   *  tail read's window is the daemon's default, the last 24 hours, so there too it is a gap. */
  truncated: TruncatedHead[];
  /** The rows the read's window and filters match, summed over every head that answered: the daemon's own `count`, taken
   *  before its newest-n clamp, so it is the whole window however few rows came back. Null when an answering head did not
   *  say its count. A partial head may still supply known counts, with its gap named in `unread`. */
  matched: number | null;
  /** The same count per head that said one. */
  matchedBy: Record<string, number>;
  /** Full-window aggregates from each answering head, not its displayed-row slice. */
  usageBy?: Record<string, TurnUsageWire>;
  /** Commands whose current history request has not settled, never a failed or partial reading. */
  pendingHeads?: string[];
  /** The window every head was asked, epoch ms, until exclusive: a rolling read is pinned to the one instant it ran at, and
   *  null `until` is a span left open to the daemon's now. Absent on a tail read, which asks the daemon's default window. A link
   *  that carries `matched` carries this range, never a clock read again later. */
  window?: { since: number; until: number | null };
  /** The instant from which `landed` holds every turn: the window the daemon read from, or later
   *  where a cap cut earlier ones (a clamped head's oldest row, or the oldest row a tail read's fleet
   *  cut kept). Before it the list is short, so an hour there with no rows is unread, not idle
   *  (V4-290, V4-300). Absent on a board no read fed (a fixture), which holds every turn it has. */
  completeFrom?: number;
}
