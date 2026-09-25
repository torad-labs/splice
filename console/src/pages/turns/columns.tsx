// The turns page's tables, column by column: the in-flight set, the window summary, and the landed
// turns. A number that has a shape is drawn (docs/design/DESIGN.md section 9, "show, then say"): a
// turn's time as its waterfall on one scale shared by every row, a cache hit as a bar, a percentile
// pair as one bar whose pale end reaches p95, an idle turn against its head's own limit. The
// figure stays beside every shape, and an absent value prints the absence dash, never a zero the
// daemon did not report.
import { STAGE_MARKS, waterfall } from '@entities/perf';
import type { InflightTurn, PerfStats, PerfSummaryHead, TurnRow } from '@entities/perf';
import type { HeadStatus } from '@shared/api';
import { HeadMark } from '@entities/control-status';
import { fmtMs, fmtShare, fmtTokens } from '@shared/lib';
import { Badge, Meter, Pips, StackedBar, Tip, Waterfall } from '@shared/ui';
import type { Column } from '@shared/ui';
import { H, S, U } from './strings';

// ---------------------------------------------------------------------------------- landed

/** The landed table's columns, by the field keys a view lists, in the order the table prints. */
export const LANDED_KEYS = ['time', 'head', 'model', 'outcome', 'timing', 'firstByte', 'cache', 'tokensIn', 'tokensOut'] as const;
export type LandedKey = (typeof LANDED_KEYS)[number];

/** Field keys a saved view may still carry from before the tables drew their numbers: each names
 *  the column that now shows it, or nothing where the column became a badge on the outcome. */
const ALIASES: Record<string, LandedKey | null> = {
  total: 'timing',
  cached: 'cache',
  cacheWrite: 'cache',
  retries: null,
  attempts: null,
  inflight: null,
  dropped: null,
};

/** A view's fields as the landed table's columns: known keys and their aliases, once each, in the
 *  view's order. */
export function landedKeysOf(fields: readonly string[]): LandedKey[] {
  const keys: LandedKey[] = [];
  for (const field of fields) {
    const key = (LANDED_KEYS as readonly string[]).includes(field) ? (field as LandedKey) : ALIASES[field] ?? null;
    if (key !== null && !keys.includes(key)) keys.push(key);
  }
  return keys;
}

const pad = (value: number): string => String(value).padStart(2, '0');

/** HH:MM:SS of a turn, or null when the row carries no timestamp. */
export function atText(ts: number | undefined): string | null {
  if (typeof ts !== 'number' || !Number.isFinite(ts)) return null;
  const at = new Date(ts);
  return `${pad(at.getHours())}:${pad(at.getMinutes())}:${pad(at.getSeconds())}`;
}

/** A turn's length: the daemon's closing tally, else the last mark it stamped. */
export function lengthOf(row: TurnRow): number {
  return row.total ?? Math.max(0, ...waterfall(row).map((stage) => stage.end));
}

/** The cache's share of the prompt: `in_tokens` holds the cached part (PerfKeys), so the ratio is
 *  cached over in. Null without both, and without any input: 0 would be a claim. */
export function cacheHitOf(row: TurnRow): number | null {
  if (row.in_tokens === undefined || row.cached_tokens === undefined || row.in_tokens <= 0) return null;
  return row.cached_tokens / row.in_tokens;
}

/** What a turn wears beside its outcome: a failure's own tag, a compaction, retries, and telemetry
 *  the daemon lost before it reached disk, which makes every figure on the row short by an unknown
 *  amount and so is said rather than shown as smaller numbers. */
export function badgesOf(row: TurnRow): { key: string; tone: 'ok' | 'warn' | 'danger' | 'neutral'; text: string }[] {
  const badges: { key: string; tone: 'ok' | 'warn' | 'danger' | 'neutral'; text: string }[] = [
    { key: 'outcome', tone: row.outcome === 'ok' ? 'ok' : 'danger', text: row.outcome },
  ];
  if (row.compact === true) badges.push({ key: 'compact', tone: 'neutral', text: S.compaction });
  if ((row.retries ?? 0) > 0) badges.push({ key: 'retries', tone: 'warn', text: `${row.retries} ${U.retries}` });
  if ((row.async_io_drops ?? 0) > 0) badges.push({ key: 'dropped', tone: 'warn', text: S.dropped });
  return badges;
}

const figure = (value: number | undefined, format: (n: number) => string): string => (value === undefined ? S.absent : format(value));

/** The landed table's columns for a view. `scale` is the longest turn in the table, so every
 *  waterfall shares one axis; `nameOf` prints a head by the label the daemon gives it. */
export function landedColumns(keys: readonly LandedKey[], scale: number, nameOf: (key: string) => string): Column<TurnRow>[] {
  const all: Record<LandedKey, Column<TurnRow>> = {
    time: { key: 'time', label: S.time, width: '10%', mono: true, cell: (row) => atText(row.ts) ?? S.absent },
    head: { key: 'head', label: S.head, width: '14%', cell: (row) => <HeadMark head={row.head}>{nameOf(row.head)}</HeadMark> },
    model: { key: 'model', label: S.model, width: '12%', primary: true, cell: (row) => row.model ?? S.absent },
    outcome: {
      key: 'outcome',
      label: S.outcome,
      width: '11%',
      cell: (row) => (
        <span className="myx-tn-badges">
          {badgesOf(row).map((badge) => <Badge key={badge.key} tone={badge.tone} quiet>{badge.text}</Badge>)}
        </span>
      ),
    },
    timing: {
      key: 'timing',
      label: S.timing,
      width: '19.5%',
      cell: (row) => {
        const length = lengthOf(row);
        return (
          <span className="myx-tn-timing">
            <Waterfall
              stages={waterfall(row).map((stage) => ({ key: stage.key, label: stage.label, start: stage.start, end: stage.end, mark: STAGE_MARKS[stage.group] }))}
              scale={scale}
              label={`${S.timing} ${fmtMs(length)}`}
            />
            <span className="myx-tn-figure">{row.total === undefined && length === 0 ? S.absent : fmtMs(length)}</span>
          </span>
        );
      },
    },
    firstByte: { key: 'firstByte', label: S.firstByte, width: '8.5%', align: 'end', mono: true, cell: (row) => figure(row.first_byte, fmtMs) },
    cache: {
      key: 'cache',
      label: S.cacheHit,
      width: '10.5%',
      cell: (row) => {
        const hit = cacheHitOf(row);
        return hit === null ? S.absent : <Meter tone="neutral" value={hit} label={`${S.cacheHit} ${fmtShare(hit)}`} figure={fmtShare(hit)} />;
      },
    },
    tokensIn: { key: 'tokensIn', label: S.input, width: '7.5%', align: 'end', mono: true, cell: (row) => figure(row.in_tokens, fmtTokens) },
    tokensOut: { key: 'tokensOut', label: S.output, width: '7%', align: 'end', mono: true, cell: (row) => figure(row.out_tokens, fmtTokens) },
  };
  return keys.map((key) => all[key]);
}

// --------------------------------------------------------------------------------- in flight

/** One head's admission gate: the turns it runs now against its slot limit, and those waiting for
 *  a slot. The daemon serves these counts on every head (HeadStatus.json) even where it serves no
 *  per-turn list, so they are what the in-flight section can always say. */
export interface HeadSlots {
  head: string;
  label: string;
  inflight: number;
  queued: number;
  /** Null when the head sets no limit. */
  max: number | null;
}

/** The slots of every head that publishes a gate, in the daemon's order. */
export function slotsFrom(heads: readonly HeadStatus[]): HeadSlots[] {
  return heads.flatMap((head) => (head.gate === null ? [] : [{
    head: head.key,
    label: head.label,
    inflight: head.gate.inflight,
    queued: head.gate.queued,
    max: typeof head.gate.max === 'number' && head.gate.max > 0 ? head.gate.max : null,
  }]));
}

/** Past this many slots a pip is too fine to count. */
const MOST_PIPS = 32;

/** Each head's gate as a tile: its name, a pip per slot with the ones in use lit, the count, and
 *  the queue when a turn waits. One pip per slot while the limit can be counted; a head with no
 *  limit, or one too wide to count, prints its count alone. A full gate turns amber: the next turn
 *  queues. */
export function Gates({ slots }: { slots: readonly HeadSlots[] }) {
  return (
    <div className="myx-tn-gates" role="list" aria-label={S.slots}>
      {slots.map((gate) => (
        <div key={gate.head} className="myx-tn-gate" role="listitem">
          <HeadMark head={gate.head}>{gate.label}</HeadMark>
          <span className="myx-tn-slots">
            {gate.max === null || gate.max > MOST_PIPS ? null : (
              <Pips used={gate.inflight} total={gate.max} label={`${gate.label} ${S.slots}`} mark={gate.inflight >= gate.max ? 'warn' : 'series-1'} />
            )}
            <span className="myx-tn-figure">{gate.max === null ? String(gate.inflight) : `${gate.inflight} / ${gate.max}`}</span>
            {gate.queued > 0 ? <Badge tone="warn">{`${gate.queued} ${U.queued}`}</Badge> : null}
          </span>
        </div>
      ))}
    </div>
  );
}

/** A live turn idle past its head's own stream idle limit is the one that needs the operator: it
 *  is the difference between a turn that is reasoning and one that has hung. */
export function isStalled(turn: InflightTurn): boolean {
  return turn.idleMs > turn.streamIdleMs;
}

export function inflightColumns(nameOf: (key: string) => string): Column<InflightTurn>[] {
  return [
    { key: 'session', label: S.session, width: '22%', primary: true, cell: (turn) => turn.label },
    { key: 'head', label: S.head, width: '16%', cell: (turn) => <HeadMark head={turn.head}>{nameOf(turn.head)}</HeadMark> },
    {
      key: 'phase',
      label: S.phase,
      width: '22%',
      cell: (turn) => (
        <span className="myx-tn-badges">
          <Badge tone="neutral" quiet>{turn.phase}</Badge>
          {turn.compact ? <Badge tone="neutral" quiet>{S.compaction}</Badge> : null}
          {isStalled(turn) ? <Tip text={H.stalled}><Badge tone="warn">{S.stalled}</Badge></Tip> : null}
        </span>
      ),
    },
    { key: 'age', label: S.age, width: '12%', align: 'end', mono: true, cell: (turn) => fmtMs(turn.ageMs) },
    {
      key: 'idle',
      label: S.idle,
      width: '28%',
      // The bar is the idle time against the head's own limit, so a full bar is a stalled turn.
      cell: (turn) => (
        <Meter
          tone={isStalled(turn) ? 'warn' : 'neutral'}
          value={turn.streamIdleMs <= 0 ? 0 : turn.idleMs / turn.streamIdleMs}
          label={`${S.idle} ${fmtMs(turn.idleMs)} of ${fmtMs(turn.streamIdleMs)}`}
          figure={fmtMs(turn.idleMs)}
        />
      ),
    },
  ];
}

// ----------------------------------------------------------------------------------- summary

/** A percentile pair as one bar on a scale shared down the column: solid to the median, pale to
 *  p95. Absent stats print the absence dash. */
export function Spread({ stats, scale, label }: { stats: PerfStats | undefined; scale: number; label: string }) {
  if (stats === undefined) return <>{S.absent}</>;
  return (
    <span className="myx-tn-spread">
      <StackedBar
        label={label}
        said={`${label} p50 ${fmtMs(stats.p50)}, p95 ${fmtMs(stats.p95)}`}
        total={scale}
        parts={[
          { key: 'p50', label: 'p50', value: stats.p50, mark: 'series-1' },
          { key: 'p95', label: 'p95', value: Math.max(0, stats.p95 - stats.p50), mark: 'series-3' },
        ]}
      />
      <span className="myx-tn-figure">{fmtMs(stats.p50)}</span>
      <span className="myx-tn-figure myx-tn-quiet">{fmtMs(stats.p95)}</span>
    </span>
  );
}

const maxOf = (heads: readonly PerfSummaryHead[], pick: (head: PerfSummaryHead) => number | undefined): number =>
  Math.max(0, ...heads.map((head) => pick(head) ?? 0));

/** The window summary's columns over the heads that ran turns in it. Refreshes and lost log rows
 *  are columns only when a head has some: a column of zeros spends a header to say nothing. */
export function summaryColumns(heads: readonly PerfSummaryHead[]): Column<PerfSummaryHead>[] {
  const firstScale = maxOf(heads, (head) => head.time_before_first_byte_ms?.p95);
  const totalScale = maxOf(heads, (head) => head.total_ms?.p95);
  const lost = heads.some((head) => (head.io_drops_in_window ?? 0) > 0);
  const refreshed = heads.some((head) => (head.refreshes ?? 0) > 0);
  return [
    { key: 'head', label: S.head, width: '14%', cell: (head) => <HeadMark head={head.key}>{head.label}</HeadMark> },
    { key: 'turns', label: S.turns, width: '6%', align: 'end', mono: true, cell: (head) => String(head.count) },
    { key: 'first', label: S.firstByte, width: '20%', cell: (head) => <Spread stats={head.time_before_first_byte_ms} scale={firstScale} label={S.firstByte} /> },
    { key: 'total', label: S.turnTime, width: '20%', cell: (head) => <Spread stats={head.total_ms} scale={totalScale} label={S.turnTime} /> },
    {
      key: 'failed',
      label: S.failed,
      width: '6%',
      align: 'end',
      mono: true,
      cell: (head) => (head.failure_share === undefined ? S.absent : (
        <span className={head.failure_share > 0 ? 'myx-tn-danger' : undefined}>{fmtShare(head.failure_share)}</span>
      )),
    },
    {
      key: 'cache',
      label: S.cacheHit,
      width: '11%',
      cell: (head) => (head.cache_hit_ratio == null ? S.absent : (
        <Meter tone="neutral" value={head.cache_hit_ratio} label={`${S.cacheHit} ${fmtShare(head.cache_hit_ratio)}`} figure={fmtShare(head.cache_hit_ratio)} />
      )),
    },
    { key: 'peak', label: S.peak, width: '13%', align: 'end', mono: true, cell: (head) => figure(head.peak_inflight, String) },
    { key: 'retries', label: S.retries, width: '10%', align: 'end', mono: true, cell: (head) => figure(head.retries, String) },
    ...(refreshed
      ? [{ key: 'refreshes', label: S.refreshes, width: '8%', align: 'end' as const, mono: true, cell: (head: PerfSummaryHead) => figure(head.refreshes, String) }]
      : []),
    ...(lost
      ? [{ key: 'lost', label: S.lostRows, width: '8%', align: 'end' as const, mono: true, cell: (head: PerfSummaryHead) => figure(head.io_drops_in_window, String) }]
      : []),
  ];
}
