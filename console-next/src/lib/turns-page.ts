// The arithmetic and words of the Requests pages: one plan row per summary head, one line per finished request, and a
// request's recorded phases and unrecorded tail. Pure over the daemon's payloads; the pages only draw what this returns.
import { ABSENT, fmtDurationS, fmtShare, fmtUsd } from './format';
import { MARK_KEYS } from '../types/perf';
import type { ModelColour } from './model';
import { spanText } from './sessions';
import { U } from './words-usage';
import { LEGACY_RESTART_SENTENCE, OUTCOME_WORD, P, STAGE_PHRASE, T } from './words-turns';
import type { RequestsRange, RequestsView } from './requests-view';
import type { TopologyState } from '../types/topology';
import type { InflightTurn, PerfStats, PerfSummaryHead, PerfWindowLabel, TurnRow } from '../types/perf';

export const WINDOW_MS: Record<PerfWindowLabel, number> = { '1h': 3_600_000, '24h': 86_400_000, '7d': 604_800_000 };

/** The plan a head key belongs to, for its colour: unknown heads wear the neutral. */
export type ColourOf = (head: string) => ModelColour;

// ── outcomes ────────────────────────────────────────────────────────────────────────────────────

export type OutcomeTone = 'work' | 'stuck' | 'idle';
export interface OutcomeRead {
  word: string;
  tone: OutcomeTone;
  failed: boolean;
}

/** Recorded stopped endings, shared by request rows and their missing-reason explanation. */
export const isStopped = (outcome: string): boolean =>
  outcome === 'client_abort' || outcome === 'error:stopped';

/** Endings the client received clean: an answer, or an empty message the model closed itself, which the daemon ends clean. */
export const isClean = (outcome: string): boolean => outcome === 'ok' || outcome === 'empty_message';

/** What a turn's outcome tag reads as. A tag this console does not know is still a failure, and says so plainly. */
export function outcomeOf(outcome: string, refusedRuntimePort?: number, cause?: string | null): OutcomeRead {
  if (outcome === 'ok') return { word: 'Done', tone: 'work', failed: false };
  if (isClean(outcome)) return { word: OUTCOME_WORD[outcome] ?? 'Done', tone: 'work', failed: false };
  if (outcome === '?') return { word: 'Unknown', tone: 'idle', failed: false };
  const refused = outcome === 'error:conn-reset' && Number.isInteger(refusedRuntimePort)
    && refusedRuntimePort !== undefined && refusedRuntimePort > 0 && refusedRuntimePort <= 65535;
  const refusal = cause === 'CONTENT_FILTERED' ? 'Request refused' : cause === 'MODEL_REFUSED' ? 'Model declined' : null;
  const word = refusal ?? (refused ? T.runtimeRefused(refusedRuntimePort) : (OUTCOME_WORD[outcome] ?? 'Failed'));
  const quiet = isStopped(outcome);
  return { word, tone: quiet ? 'idle' : 'stuck', failed: !quiet };
}

// ── the plan rows ───────────────────────────────────────────────────────────────────────────────

export interface PlanRow {
  key: string;
  label: string;
  colour: ModelColour;
  turns: number;
  failed: number;
  /** ms to the first byte, typical and slowest twentieth; null when the window holds no row that reports it. */
  firstP50: number | null;
  firstP95: number | null;
  /** 0..1, null when the window holds no input at all. */
  cache: number | null;
}

/** Failures use the row's classification, excluding successes, unknown attribution and stopped endings. */
export function failedCount(head: PerfSummaryHead): number {
  return Object.entries(head.outcomes ?? {}).reduce((n, [tag, count]) => n + (outcomeOf(tag).failed ? count : 0), 0);
}

export function planRows(heads: readonly PerfSummaryHead[], colourOf: ColourOf): PlanRow[] {
  return heads
    .filter((head) => !head.empty && head.count > 0)
    .map((head) => ({
      key: head.key,
      label: head.label,
      colour: colourOf(head.key),
      turns: head.count,
      failed: failedCount(head),
      firstP50: head.time_before_first_byte_ms?.p50 ?? null,
      firstP95: head.time_before_first_byte_ms?.p95 ?? null,
      cache: head.cache_hit_ratio ?? null,
    }))
    .sort((left, right) => right.turns - left.turns || left.label.localeCompare(right.label));
}

/** A span as the Turns pages print it: `12 ms`, `1.4 s`, `1m 16s`. One rule for the bars, the list and the sentences. */
export const secondsText = (ms: number): string => (ms < 1000 ? `${Math.round(ms)} ms` : ms < 60_000 ? `${(ms / 1000).toFixed(1)} s` : fmtDurationS(ms / 1000));

/** The widest slowest-twentieth of the rows, the scale every plan's bar shares. */
export const barMax = (rows: readonly PlanRow[]): number => Math.max(1, ...rows.map((row) => row.firstP95 ?? row.firstP50 ?? 0));

const WORDS = ['One', 'Two', 'Three', 'Four', 'Five', 'Six', 'Seven', 'Eight', 'Nine', 'Ten'];

export function turnsLede(rows: readonly PlanRow[], window: PerfWindowLabel, first: PerfStats | undefined = undefined): string {
  const turns = rows.reduce((n, row) => n + row.turns, 0);
  if (turns === 0) return `No model has answered a request in ${T.windowSpoken[window]}.`;
  const failed = rows.reduce((n, row) => n + row.failed, 0);
  const count = `${turns.toLocaleString('en-US')} ${turns === 1 ? 'request' : 'requests'} in ${T.windowSpoken[window]}`;
  const fails = failed === 0 ? 'None failed' : `${failed <= WORDS.length ? WORDS[failed - 1] : failed.toLocaleString('en-US')} failed`;
  // Only the daemon's pooled request distribution can report a fleet percentile.
  const middle = first?.p50;
  return middle === undefined ? `${count}. ${fails}.` : `${count}. ${fails}, and ${T.firstResponseLede(secondsText(middle))}.`;
}

const SPAN_DAY: Intl.DateTimeFormatOptions = { weekday: 'long', month: 'long', day: 'numeric' };
const SPAN_INSTANT: Intl.DateTimeFormatOptions = { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' };

/** A fixed span as the lede says it, in the viewer's zone. */
function spanSaid(range: Extract<RequestsRange, { kind: 'span' }>): string {
  if (range.day !== null) return T.onDay(new Date(range.since).toLocaleDateString([], SPAN_DAY));
  const from = new Date(range.since).toLocaleString([], SPAN_INSTANT);
  return range.until === null ? T.sinceTime(from) : T.between(from, new Date(range.until).toLocaleString([], SPAN_INSTANT));
}

/** What the page says first. A window is the summary's sentence and a span is the daemon's count of it; either says it is
 *  reading while its read is in flight, never that the window is empty before the answer came back. `plans` is null and
 *  `matched` undefined while their reads are in flight. */
export function pageLede(view: RequestsView, plans: readonly PlanRow[] | null, matched: number | null | undefined, first: PerfStats | undefined = undefined): string {
  if (view.range.kind === 'last') return plans === null ? T.reading : turnsLede(plans, view.range.window, first);
  return matched === undefined ? T.reading : T.spanLede(matched, spanSaid(view.range));
}

// ── the finished turns ──────────────────────────────────────────────────────────────────────────

export interface TurnLine {
  /** The perf request owner carried by the detail link, absent on legacy rows. */
  requestId?: string;
  key: string;
  head: string;
  plan: string;
  colour: ModelColour;
  ts: number;
  title: string;
  model: string | null;
  /** The account the request went out on, when the head draws from a pool. */
  account: string | null;
  /** The first 8 of the session id, the daemon's session filter. */
  session: string | null;
  outcome: OutcomeRead;
  /** What else the row says happened, each only when it did: a compaction, a cache hit, a retry, a switch of account. */
  tags: string[];
  compact: boolean;
  tookMs: number | null;
  inTokens: number | null;
  outTokens: number | null;
  cost: string;
}

/** Prefer the daemon's request identity. Without one, all recorded row facts distinguish concurrent
 * endings without depending on snapshot order or the property's order on the wire. */
export const turnKey = (row: TurnRow): string =>
  `${row.head}/${row.response_message_id ? `response/${row.response_message_id}` : row.turn ? `trace/${row.turn}` : `facts/${JSON.stringify(Object.entries(row).sort(([left], [right]) => left.localeCompare(right)))}`}`;

/** The name a session goes by, looked up from its full id. */
export type TitleOf = (sessionId: string | undefined, short: string | undefined) => string | null;

export function lineOf(row: TurnRow, planLabel: (head: string) => string, colourOf: ColourOf, titleOf: TitleOf): TurnLine {
  const outcome = outcomeOf(row.outcome, row.refused_runtime_port, row.cause);
  return {
    key: turnKey(row),
    ...(row.turn_id === undefined ? {} : { requestId: row.turn_id }),
    head: row.head,
    plan: planLabel(row.head),
    colour: colourOf(row.head),
    ts: row.ts,
    title: titleOf(row.session_id, row.session) ?? planLabel(row.head),
    model: row.model?.trim() || null,
    account: row.account ?? null,
    session: row.session ?? null,
    outcome,
    tags: [
      row.compact === true ? T.compacted : null,
      (row.cached_tokens ?? 0) > 0 ? T.cacheHit : null,
      (row.retries ?? 0) > 0 ? T.retried : null,
      // The daemon writes cache_cold true exactly when this request switched accounts (AccountSelection.cacheCold).
      row.cache_cold === true ? T.switchedAccount : null,
    ].flatMap((tag) => (tag === null ? [] : [tag])),
    compact: row.compact === true,
    tookMs: row.total ?? null,
    inTokens: row.in_tokens ?? null,
    outTokens: row.out_tokens ?? null,
    cost: typeof row.cost_usd === 'number' ? fmtUsd(row.cost_usd) : ABSENT,
  };
}

/** A snapshot keeps every daemon row, including indistinguishable duplicate records. Only those
 * duplicates need an occurrence suffix; different id-less facts retain their keys across polls. */
export function linesOf(rows: readonly TurnRow[], planLabel: (head: string) => string, colourOf: ColourOf, titleOf: TitleOf): TurnLine[] {
  const occurrences = new Map<string, number>();
  return rows.map(row => {
    const line = lineOf(row, planLabel, colourOf, titleOf);
    const occurrence = occurrences.get(line.key) ?? 0;
    occurrences.set(line.key, occurrence + 1);
    return occurrence === 0 ? line : { ...line, key: `${line.key}/occurrence/${occurrence}` };
  });
}

/** Newest first, the order the page lists them in. */
export const newestFirst = (lines: readonly TurnLine[]): TurnLine[] => [...lines].sort((a, b) => b.ts - a.ts);

export const tookText = (ms: number | null): string => (ms === null ? ABSENT : secondsText(ms));

// ── the running turns ───────────────────────────────────────────────────────────────────────────

export interface RunningLine {
  /** The exact live registry id the gate slot carries, absent on legacy or unlisted slots. */
  turnId?: string;
  key: string;
  head: string;
  plan: string;
  colour: ModelColour;
  label: string;
  /** What the card is called: the session's name, or the model when no session is known: never the gate's short id. */
  title: string;
  /** The model, when the title is the session's name. */
  model: string | null;
  /** Long silence worth explaining, not a daemon failure signal. */
  longQuiet: boolean;
  age: string;
  quiet: string | null;
  phase: 'connect' | 'streaming';
}

/** The gate labels a live turn `<first 8 of the session id> <model>`. The id is a code: the card says the session's name where one is
 *  known, and the model alone where not. A label of any other shape is printed as it is. */
const GATE_LABEL = /^([0-9a-f]{8}) (.+)$/;
function liveTitle(label: string, nameOf: (prefix: string) => string | null): { title: string; model: string | null } {
  const coded = GATE_LABEL.exec(label);
  if (coded?.[1] === undefined || coded[2] === undefined) return { title: label, model: null };
  const name = nameOf(coded[1]);
  return name === null ? { title: coded[2], model: null } : { title: name, model: coded[2] };
}

/** Bring long-quiet turns forward for inspection, not as a failure or a required intervention. */
const LONG_QUIET_MS = 5 * 60_000;
export function runningOf(turns: readonly InflightTurn[], planLabel: (head: string) => string, colourOf: ColourOf, nameOf: (prefix: string) => string | null = () => null): RunningLine[] {
  return turns
    .map((turn, index) => ({
      key: turn.turnId === undefined ? `${turn.head}/${index}` : `${turn.head}/live/${turn.turnId}`,
      ...(turn.turnId === undefined ? {} : { turnId: turn.turnId }),
      head: turn.head,
      plan: planLabel(turn.head),
      colour: colourOf(turn.head),
      label: turn.label,
      ...liveTitle(turn.label, nameOf),
      longQuiet: turn.idleMs > LONG_QUIET_MS,
      age: spanText(turn.ageMs),
      quiet: turn.idleMs >= 30_000 ? spanText(turn.idleMs) : null,
      phase: turn.phase === 'streaming' ? ('streaming' as const) : ('connect' as const),
    }))
    .sort((left, right) => Number(right.longQuiet) - Number(left.longQuiet));
}

// ── one turn's stages ───────────────────────────────────────────────────────────────────────────

export type StageKey = 'prepare' | 'queue' | 'lease' | 'provider' | 'stream' | 'wait';
export const STAGE_ORDER: readonly StageKey[] = ['prepare', 'queue', 'lease', 'provider', 'stream', 'wait'];
export interface StageBar {
  key: StageKey;
  ms: number;
}
export interface TurnTiming {
  stages: StageBar[];
  additive: boolean;
}

const measured = (value: number | null | undefined): value is number => typeof value === 'number' && Number.isFinite(value) && value >= 0;

/** Local counters measure distinct spans. Transport pairs describe only the latest attempt, not the
 *  gap after preparation. Legacy marks alone cannot identify preparation, admission or provider wait.
 *  Stack only a bounded single-attempt breakdown; every uncovered millisecond stays unattributed. */
export function stagesOf(row: TurnRow): TurnTiming {
  const stages: StageBar[] = [];
  const total = measured(row.total) ? row.total : null;
  const local = [['prepare', row.prep_ms], ['queue', row.admit_wait_ms], ['lease', row.lease_wait_ms]] as const;
  for (const [key, ms] of local) {
    if (measured(ms)) stages.push({ key, ms });
  }
  const localMs = stages.reduce((n, stage) => n + stage.ms, 0);
  const transports = [
    [row.arrival_to_ws_send_accepted_ms, row.ws_send_accepted_to_first_fragment_ms],
    [row.arrival_to_upstream_write_ms, row.upstream_write_to_first_byte_ms],
  ] as const;
  const observed = transports.filter(pair => pair.some(value => value != null));
  const pair = observed.length === 1 ? observed[0] : undefined;
  const send = pair?.[0];
  const responseWait = pair?.[1];
  const response = !servedLocally(row) && measured(send) && measured(responseWait);
  if (response) stages.push({ key: 'provider', ms: responseWait });

  // Arrival-relative transport marks and legacy marks have different origins. This conservative
  // ordering check never moves a legacy mark forward to manufacture a non-overlapping interval.
  const firstDelta = row.first_delta;
  const streamEnd = row.stream_end;
  const stream = response && row.attempts === 1 && (row.ws_refused_too_large ?? 0) === 0
    && measured(firstDelta) && measured(streamEnd)
    && firstDelta >= send + responseWait && streamEnd >= firstDelta
    && total !== null && streamEnd <= total
    && (!measured(row.first_byte) || row.first_byte >= send && row.first_byte <= firstDelta)
    && (!measured(row.finish) || row.finish >= streamEnd);
  if (stream) stages.push({ key: 'stream', ms: streamEnd - firstDelta });

  const sum = stages.reduce((n, stage) => n + stage.ms, 0);
  const additive = stream && total !== null && sum <= total
    && local.every(([, ms]) => measured(ms))
    && localMs <= send
    && MARK_KEYS.every(key => row[key] === undefined || measured(row[key]) && row[key] <= total)
    && (row.first_frame === undefined || measured(row.first_frame)
      && row.first_frame <= streamEnd
      && (row.finish === undefined || measured(row.finish) && row.first_frame <= row.finish));
  // These measured spans are disjoint even when earlier attempts are missing. Their remainder is
  // not a measured phase and, without a comparable complete row, is never presented as a stack.
  if (total !== null && sum <= total && (!response || localMs <= send && send + responseWait <= total)) {
    stages.push({ key: 'wait', ms: total - sum });
  }
  return { stages, additive };
}

/** The sentence under a turn's title: how long it took and where most of it went. */
/** A record splice answered itself: in Codex code mode the model has already written its script, and each queued tool step is
 *  served to the client as a synthesized tool call with no send upstream (CodexCodeModeMachine.kt:130). It is a step of a turn,
 *  not a turn the model took, and the daemon marks it at the source (`local_step` = 1, PerfKeys.LOCAL_STEP). A row without the
 *  field is a turn. */
export const servedLocally = (row: TurnRow): boolean => row.local_step === 1;

/** The steps splice answered itself across every plan in the window, as the summary counts them. */
export const localStepsOf = (heads: readonly PerfSummaryHead[]): number => heads.reduce((n, head) => n + (head.local_steps ?? 0), 0);

export function turnLede(row: TurnRow, timing: TurnTiming = stagesOf(row)): string {
  const outcome = outcomeOf(row.outcome, row.refused_runtime_port, row.cause);
  if (servedLocally(row)) return T.servedLocallyLede;
  const total = measured(row.total) ? row.total : null;
  const took = total !== null && total > 0 ? secondsText(total) : null;
  const longest = [...timing.stages].sort((left, right) => right.ms - left.ms)[0];
  if (outcome.failed) return took === null ? `${outcome.word}.` : `${outcome.word} after ${took}.`;
  const empty = row.outcome === 'empty_message' ? `${T.emptyAnswerLede} ${(row.reasoning_tokens ?? 0) > 0 ? `${T.emptyAnswerThinking} ` : ''}` : '';
  if (took === null) return `${empty}${outcome.word}. ${timing.stages.length === 0 ? P.noTiming : P.totalNotReported}`;
  return !timing.additive || total === null || longest === undefined || longest.ms < 1000 || longest.ms <= total / 2 ? `${empty}Took ${took}.` : `${empty}Took ${took}. Most of it, ${secondsText(longest.ms)}, was ${STAGE_PHRASE[longest.key]}.`;
}

/** Translate only known splice failure sentences; raw request and answer bodies stay untouched. */
export function spokenFailure(sentence: string): string {
  if (sentence === LEGACY_RESTART_SENTENCE) return T.restarted;
  const timeout = /^(?:\[SPLICE-OVERLOADED\]\s*)?splice progress timeout expired after (\d+)ms without upstream progress; retry\s*$/.exec(sentence);
  const quiet = Number(timeout?.[1]);
  return Number.isFinite(quiet) && quiet > 0 ? T.progressTimeout(secondsText(quiet)) : sentence;
}

export const cacheText = (ratio: number | null): string => (ratio === null ? ABSENT : fmtShare(ratio));

// ── what was kept of a turn ─────────────────────────────────────────────────────────────────────

/** The conversation through this turn's reply cut to the exchange: the message that asked, everything after it, and how
 *  many messages came before. */
export function askAndAnswer<M extends { role: string }>(messages: readonly M[]): { ask: M | null; reply: M[]; earlier: number } {
  let last = -1;
  messages.forEach((message, index) => {
    if (message.role === 'user') last = index;
  });
  if (last < 0) return { ask: null, reply: [...messages], earlier: 0 };
  return { ask: messages[last] ?? null, reply: messages.slice(last + 1), earlier: last };
}

/** A request and Usage use the same words for the daemon's shared cost classification. */
export function turnCostWhy(row: TurnRow): string {
  if (typeof row.cost_usd === 'number') return P.costWhy;
  const reason = row.cost_reason;
  switch (reason) {
    case 'plan': return U.unpricedPlan(1);
    case 'local': return U.unpricedLocal(1);
    case 'uncounted': return U.unpricedUncounted(1);
    case 'undeclared': return U.unpricedUndeclared(1);
    case 'unanswered': return U.unanswered(1);
    case null:
    case undefined:
      return (row.upstream_req_bytes ?? 0) > 0 && (row.in_tokens == null || row.out_tokens == null) ? P.costUnreported : P.costNone;
  }
  const remaining: never = reason;
  return remaining;
}

/** The figures a turn moved, each only when the row carries it. */
export interface Moved {
  read: number | null;
  cached: number | null;
  written: number | null;
  cost: number | null;
  retries: number | null;
}
export const movedOf = (row: TurnRow): Moved => ({
  read: row.in_tokens ?? null,
  cached: row.cached_tokens ?? null,
  written: row.out_tokens ?? null,
  cost: typeof row.cost_usd === 'number' ? row.cost_usd : null,
  retries: row.retries !== undefined && row.retries > 0 ? row.retries : null,
});

/** What splice.toml holds for a head's body capture: `[heads.<key>.overrides] trace`, written as the text "true" or "false". Null when
 *  the file sets none (the head then follows the default, so nothing is waiting) or the read is not here. The file is what a save
 *  changes and a restart applies, so this read is the same after a reload and on another turn's page. */
export function savedCapture(topology: TopologyState | undefined, head: string): boolean | null {
  if (topology === undefined || 'pending' in topology) return null;
  const heads = topology.topology.heads;
  const entry = typeof heads === 'object' && heads !== null ? (heads as Record<string, unknown>)[head] : undefined;
  const overrides = typeof entry === 'object' && entry !== null ? (entry as { overrides?: unknown }).overrides : undefined;
  const trace = typeof overrides === 'object' && overrides !== null ? (overrides as { trace?: unknown }).trace : undefined;
  return trace === 'true' ? true : trace === 'false' ? false : null;
}

/** What a head's body capture is, from the two things the daemon says. `running` is the capture read: what it records now. `saved` is what
 *  splice.toml holds, which a recorder built at start only follows after a restart. The switch shows what was saved, and a difference
 *  between the two is a restart waiting. */
export function captureState(running: boolean | null, saved: boolean | null): { on: boolean; recording: boolean; pending: boolean } {
  const on = saved ?? running ?? false;
  return { on, recording: running === true, pending: saved !== null && running !== null && saved !== running };
}
