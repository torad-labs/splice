// The arithmetic and words of the Turns pages: one plan row per summary head, one line per finished turn, and a
// turn's four stages. Pure over the daemon's payloads; the pages only draw what this returns.
import { ABSENT, fmtDurationS, fmtShare, fmtUsd } from './format';
import { waterfall } from './perf';
import { colourOfHead } from './model';
import type { ModelColour } from './model';
import { STUCK_IDLE_MS, spanText } from './sessions';
import { OUTCOME_WORD, STAGE_PHRASE, T } from './words-turns';
import type { TopologyState } from '../types/topology';
import type { LiveTurn } from '../types/turns';
import type { InflightTurn, PerfSummaryHead, PerfWindowLabel, TurnRow } from '../types/perf';

export const WINDOW_MS: Record<PerfWindowLabel, number> = { '1h': 3_600_000, '24h': 86_400_000, '7d': 604_800_000 };

/** The plan a head key belongs to, for its colour: unknown heads wear the neutral. */
export type ColourOf = (head: string) => ModelColour;
export const colourFromHeads = (heads: readonly { key: string; authKind: string }[]): ColourOf => {
  const table = new Map(heads.map((head) => [head.key, colourOfHead(head.authKind)] as const));
  return (head) => table.get(head) ?? 'none';
};

// ── outcomes ────────────────────────────────────────────────────────────────────────────────────

export type OutcomeTone = 'work' | 'stuck' | 'idle';
export interface OutcomeRead {
  word: string;
  tone: OutcomeTone;
  failed: boolean;
}

/** What a turn's outcome tag reads as. A tag this console does not know is still a failure, and says so plainly. */
export function outcomeOf(outcome: string): OutcomeRead {
  if (outcome === 'ok') return { word: 'Done', tone: 'work', failed: false };
  if (outcome === '?') return { word: 'Unknown', tone: 'idle', failed: false };
  const word = OUTCOME_WORD[outcome] ?? 'Failed';
  const quiet = outcome === 'client_abort' || outcome === 'error:cancelled' || outcome === 'error:stopped';
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

/** Turns that ended anywhere but `ok`, the unattributed `?` excluded (it is not a failure, it is unknown). */
export function failedCount(head: PerfSummaryHead): number {
  return Object.entries(head.outcomes ?? {}).reduce((n, [tag, count]) => (tag === 'ok' || tag === '?' ? n : n + count), 0);
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

export function turnsLede(rows: readonly PlanRow[], window: PerfWindowLabel): string {
  const turns = rows.reduce((n, row) => n + row.turns, 0);
  if (turns === 0) return `No model has answered a turn in ${T.windowSpoken[window]}.`;
  const failed = rows.reduce((n, row) => n + row.failed, 0);
  const count = `${turns.toLocaleString('en-US')} ${turns === 1 ? 'turn' : 'turns'} in ${T.windowSpoken[window]}`;
  const fails = failed === 0 ? 'None failed' : `${failed <= WORDS.length ? WORDS[failed - 1] : failed} failed`;
  const firsts = rows.flatMap((row) => (row.firstP50 === null ? [] : [row.firstP50])).sort((a, b) => a - b);
  const middle = firsts[Math.floor(firsts.length / 2)];
  return middle === undefined ? `${count}. ${fails}.` : `${count}. ${fails}, and the typical first word came back in ${secondsText(middle)}.`;
}

// ── the finished turns ──────────────────────────────────────────────────────────────────────────

export interface TurnLine {
  key: string;
  head: string;
  plan: string;
  colour: ModelColour;
  ts: number;
  title: string;
  model: string | null;
  outcome: OutcomeRead;
  /** A side note beside the model: `Compacted`, or an account switch. */
  tag: string | null;
  compact: boolean;
  tookMs: number | null;
  inTokens: number | null;
  outTokens: number | null;
  cost: string;
}

export const turnKey = (row: Pick<TurnRow, 'head' | 'ts'>): string => `${row.head}/${row.ts}`;

/** The name a session goes by, looked up from its full id. */
export type TitleOf = (sessionId: string | undefined, short: string | undefined) => string | null;

export function lineOf(row: TurnRow, planLabel: (head: string) => string, colourOf: ColourOf, titleOf: TitleOf): TurnLine {
  const outcome = outcomeOf(row.outcome);
  return {
    key: turnKey(row),
    head: row.head,
    plan: planLabel(row.head),
    colour: colourOf(row.head),
    ts: row.ts,
    title: titleOf(row.session_id, row.session) ?? planLabel(row.head),
    model: row.model?.trim() || null,
    outcome,
    tag: row.compact === true ? 'Compacted' : null,
    compact: row.compact === true,
    tookMs: row.total ?? null,
    inTokens: row.in_tokens ?? null,
    outTokens: row.out_tokens ?? null,
    cost: typeof row.cost_usd === 'number' ? fmtUsd(row.cost_usd) : ABSENT,
  };
}

export type TurnFilter = 'all' | 'failed' | 'compacted';

export function filterLines(lines: readonly TurnLine[], filter: TurnFilter, query: string): TurnLine[] {
  const needle = query.trim().toLowerCase();
  return lines.filter((line) => {
    if (filter === 'failed' && !line.outcome.failed) return false;
    if (filter === 'compacted' && !line.compact) return false;
    return needle === '' || [line.title, line.plan, line.model, line.head].some((text) => text?.toLowerCase().includes(needle) === true);
  });
}

/** Newest first, the order the page lists them in. */
export const newestFirst = (lines: readonly TurnLine[]): TurnLine[] => [...lines].sort((a, b) => b.ts - a.ts);

export const tookText = (ms: number | null): string => (ms === null ? ABSENT : secondsText(ms));

// ── the running turns ───────────────────────────────────────────────────────────────────────────

export interface RunningLine {
  key: string;
  head: string;
  plan: string;
  colour: ModelColour;
  label: string;
  /** What the card is called: the session's name, or the model when no session is known: never the gate's short id. */
  title: string;
  /** The model, when the title is the session's name. */
  model: string | null;
  stuck: boolean;
  age: string;
  /** The age the card's figure was made from, to tell a session's turns apart. */
  ageMs: number;
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

/** A live turn is stuck once the plan has said nothing for the same five minutes a session is. */
export function runningOf(turns: readonly InflightTurn[], planLabel: (head: string) => string, colourOf: ColourOf, nameOf: (prefix: string) => string | null = () => null): RunningLine[] {
  return turns
    .map((turn, index) => ({
      key: `${turn.head}/${index}`,
      head: turn.head,
      plan: planLabel(turn.head),
      colour: colourOf(turn.head),
      label: turn.label,
      ...liveTitle(turn.label, nameOf),
      stuck: turn.idleMs > STUCK_IDLE_MS,
      age: spanText(turn.ageMs),
      ageMs: turn.ageMs,
      quiet: turn.idleMs >= 30_000 ? spanText(turn.idleMs) : null,
      phase: turn.phase === 'streaming' ? ('streaming' as const) : ('connect' as const),
    }))
    .sort((left, right) => Number(right.stuck) - Number(left.stuck));
}

/** The live turn a running card stands for. The card is read off the gate, which labels a turn with the first eight of its session id
 *  and its model; the stop names the daemon's own id, so the two are joined on those, and on the age when a session runs two at once
 *  (a compaction beside its turn). A card whose label is no session's names nothing. */
export function liveTurnFor(line: Pick<RunningLine, 'label' | 'ageMs'>, turns: readonly LiveTurn[]): LiveTurn | null {
  const coded = GATE_LABEL.exec(line.label);
  if (coded?.[1] === undefined) return null;
  const [, prefix, model] = coded;
  const match = turns.filter((turn) => !turn.stopped && turn.session?.startsWith(prefix) === true && turn.model === model);
  return match.reduce<LiveTurn | null>((best, turn) => (best === null || Math.abs(turn.age_ms - line.ageMs) < Math.abs(best.age_ms - line.ageMs) ? turn : best), null);
}

// ── one turn's stages ───────────────────────────────────────────────────────────────────────────

export type StageKey = 'prepare' | 'queue' | 'provider' | 'stream';
export const STAGE_ORDER: readonly StageKey[] = ['prepare', 'queue', 'provider', 'stream'];
export interface StageBar {
  key: StageKey;
  ms: number;
}

const STAGE_OF_GROUP = { ingest: 'prepare', queue: 'queue', upstream: 'provider', stream: 'stream', finish: 'stream' } as const;

/** The turn's four stages, in the order a reader thinks of them, each the sum of its marks' segments. A stage
 *  whose marks the row never stamped is absent, not zero. */
export function stagesOf(row: TurnRow): StageBar[] {
  const sums = new Map<StageKey, number>();
  for (const stage of waterfall(row)) {
    const key = STAGE_OF_GROUP[stage.group];
    sums.set(key, (sums.get(key) ?? 0) + stage.ms);
  }
  return STAGE_ORDER.flatMap((key) => {
    const ms = sums.get(key);
    return ms === undefined ? [] : [{ key, ms }];
  });
}

/** The sentence under a turn's title: how long it took and where most of it went. */
/** A record splice answered itself: in Codex code mode the model has already written its script, and each queued tool step is
 *  served to the client as a synthesized tool call with no send upstream (CodexCodeModeMachine.kt:130). It is a step of a turn,
 *  not a turn the model took, and the daemon marks it at the source (`local_step` = 1, PerfKeys.LOCAL_STEP). A row without the
 *  field is a turn. */
export const servedLocally = (row: TurnRow): boolean => row.local_step === 1;

/** The steps splice answered itself across every plan in the window, as the summary counts them. */
export const localStepsOf = (heads: readonly PerfSummaryHead[]): number => heads.reduce((n, head) => n + (head.local_steps ?? 0), 0);

export function turnLede(row: TurnRow, stages: readonly StageBar[]): string {
  const outcome = outcomeOf(row.outcome);
  if (servedLocally(row)) return T.servedLocallyLede;
  const total = row.total ?? stages.reduce((n, stage) => n + stage.ms, 0);
  const took = total > 0 ? secondsText(total) : null;
  const longest = [...stages].sort((left, right) => right.ms - left.ms)[0];
  if (outcome.failed) return took === null ? `${outcome.word}.` : `${outcome.word} after ${took}.`;
  if (took === null) return `${outcome.word}. It carries no timing.`;
  return longest === undefined || longest.ms < 1000 ? `Took ${took}.` : `Took ${took}. Most of it, ${secondsText(longest.ms)}, was ${STAGE_PHRASE[longest.key]}.`;
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

/** The wire tap's bodies that belong to a turn: sent inside the turn's own span (a few seconds of slack either side)
 *  and, where both name one, from the same session. */
export function wireFor<W extends { ts: number; session?: string | undefined }>(records: readonly W[], row: TurnRow): W[] {
  const slack = 5000;
  const from = row.ts - (row.total ?? 0) - slack;
  const to = row.ts + slack;
  return records.filter((record) => record.ts >= from && record.ts <= to && (row.session === undefined || record.session === undefined || record.session === row.session));
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
