// The payload contracts of the performance entity. Every field below is named after the daemon
// code that serves it, never after a screen:
//   GET /api/perf         -> PerfPayload         (splice/control/api/PerfPayloads.kt:58-76)
//   GET /api/perf/summary -> PerfSummaryPayload  (splice/control/api/PerfSummary.kt:65-80)
//   GET /api/perf/turns   -> PerfTurnsWire       (V4-127, PerfRoutes.kt), merged into TurnRow[]
// The numeric field names are the PerfKeys catalogue (core/perf/PerfKeys.kt), so a mark renamed
// there renames here.
import type { PendingRoute } from '@shared/api';

/** {count, p50, p95, max} of one field over a row set — the ONE shape both perf routes print
 *  (PerfSummary.stats). Percentiles are nearest-rank on the sorted values, per the daemon. */
export interface PerfStats {
  count: number;
  p50: number;
  p95: number;
  max: number;
}

/** GET /api/perf?tail=N — one row per head of per-field percentiles over its last N turns. */
export interface PerfHeadStages {
  key: string;
  label: string;
  /** Rows the head contributed; a head with no rows reports 0 and no stage fields. */
  count: number;
  /** Field name -> stats. Fields seen in the tail only: marks in pipeline order first, then the
   *  counters alphabetically. A field absent from a row contributes nothing, never a zero. */
  stages: Record<string, PerfStats>;
}

export interface PerfPayload {
  /** The daemon echoes the TAIL COUNT here, not a time window (PerfPayloads.perfJson puts
   *  `window` = tailN). Named as the wire names it; the entity never reads it as a duration. */
  window: number;
  heads: PerfHeadStages[];
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
  count: number;
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
  /** Outcome tag -> turn count. A row whose outcome could not be parsed is filed under "?". */
  outcomes?: Record<string, number>;
  /** Failing turns over all turns, excluding both "ok" and the unattributed "?" tag. */
  failure_share?: number;
  /** Per failing tag, so four upstream failures and one client abort read 0.20 and 0.05. */
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
 * with the head it came from stamped on, built by model/turns-wire.ts. The route answers per head
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
  /** Null for a legacy or torn row, as `model`. */
  compact: boolean | null;
  /** First 8 characters of the client's session id, or absent when the turn carried none. */
  session?: string;
  /** The account label, written only after an account switch. */
  account?: string;
  /** Whether the account switch left the prompt cache cold; written only beside `account`. */
  cache_cold?: boolean;
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
export type TurnRowWire = Omit<TurnRow, 'head' | 'session' | 'account' | 'cache_cold'> & {
  session: string | null;
  account: string | null;
  cache_cold: boolean | null;
};

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

/** The pending shape and the rule that detects it now live in @shared/api (hoisted from M2-D1's
 *  finding, so every entity resolves an unbuilt route the same way). Re-exported here for the
 *  callers that already address this slice. */
export type { PendingRoute };

/** One head's opt-in body capture: the toggle and, when it is on, the bodies of one turn.
 *
 *  PENDING V4-133 (`GET/PUT /api/heads/{head}/capture`, FEATURES.md 6 and 4.9). Capture is OFF by
 *  default and the console says so rather than showing an empty drawer that could be mistaken for
 *  a turn with no body. */
export interface CaptureState {
  head: string;
  enabled: boolean;
  /** The turn the bodies belong to (its `ts`), present only when a turn was asked for. */
  at?: number;
  /** The request body, redacted daemon-side, present only when capture is on and the turn was
   *  recorded. Never a partial body: an absent field means the daemon has none. */
  request?: string;
  response?: string;
}

export type CaptureSlice = CaptureState | PendingRoute;

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
  /** The head's own idle threshold, so a turn past it is visibly stalled rather than busy. */
  streamIdleMs: number;
}

/** What the turns store holds: the live set beside the landed rows. */
export interface TurnsState {
  inflight: InflightTurn[];
  landed: TurnRow[];
  /** Heads whose turns are missing from `landed`, each with why. Empty when every head was read: a
   *  head that failed is named rather than silently dropped from a list that would then look
   *  complete. */
  unread: UnreadHead[];
}
