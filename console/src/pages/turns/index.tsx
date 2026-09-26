// The turns page: what is running now, what landed, and where each turn's time went.
//
// WHAT EACH SECTION CAN HONESTLY SAY, and the read behind it.
//   in flight  - the gate snapshots on GET /api/heads: per head, the slots in use against its limit
//                and the turns queued for one. A daemon that also lists the live turns gets a row per
//                turn, with its idle time against the head's own stream idle limit, so a hung turn
//                reads as a full bar and a badge. This daemon lists none: its heads route writes the
//                list empty (HeadStatus.kt), so the counts are what this section can always say, and
//                it said "nothing in flight" beside five running turns before (2026-09-25).
//   summary    - GET /api/perf/summary: per head, the window's percentiles, the failure share and
//                the dropped-telemetry lower bound. A head with no turns in the window is named on
//                one line, never a row of dashes that would read as a fast head.
//   stages,    - the loaded landed turns (GET /api/perf/turns): where their time went, in the five
//   tokens       parts of a turn, and what their prompts cost in cache terms. Both say how many
//                turns they read, because it is not the summary's window.
//   landed     - the same turns, newest first, each with its waterfall on one scale shared by the
//                table, so a slow turn is a long bar before its figure is read.
//
// The waterfall answers "why was this slow" only as far as the daemon can: queue wait, upstream wait
// and streaming as separate parts, because the daemon records where the time went and never why
// (FEATURES.md 2.12).
import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { ViewTabs, useViews } from '@features/views';
import type { View } from '@features/views';
import {
  captureFor,
  fetchCapture,
  fetchPerfTurns,
  inflightFrom,
  putCapture,
  startPerfSummaryPolling,
  useCapture,
  usePerfSummary,
  usePerfTurns,
  waterfall,
  STAGE_MARKS,
  STAGE_NAMES,
} from '@entities/perf';
import type {
  CaptureCell,
  InflightTurn,
  PendingRoute,
  PerfSummaryPayload,
  StageGroup,
  TurnRow,
  TurnsState,
} from '@entities/perf';
import { useHeads, startHeadsPolling } from '@entities/heads';
import { HeadMark } from '@entities/control-status';
import { useSession } from '@entities/session';
import { RequestDrawer, TurnWaterfall } from '@widgets/waterfall';
import { Badge, DataTable, DetailPanel, Empty, InfoTip, KeyValue, Legend, Meter, PageHeader, Section, StackedBar } from '@shared/ui';
import type { Column, RowGroup } from '@shared/ui';
import { Fault } from '@shared/controls';
import { fmtMs, fmtShare, fmtTokens, poll, timeAgo } from '@shared/lib';
import { atText, badgesOf, Gates, inflightColumns, landedColumns, landedKeysOf, lengthOf, slotsFrom, summaryColumns } from './columns';
import type { HeadSlots } from './columns';
import { clockOf, groupByOf, isTimeline, rowKeyer, selectionOf, sinceOf } from './select';
import { H, S, U } from './strings';
import './turns.css';

export { atText, badgesOf, cacheHitOf, landedKeysOf, lengthOf, slotsFrom } from './columns';
export type { HeadSlots } from './columns';

const PAGE_ID = 'turns';

const LANDED_FIELDS = ['time', 'head', 'model', 'outcome', 'timing', 'firstByte', 'cache', 'tokensIn', 'tokensOut'];

/** The landed columns kept while a turn is open beside the table. */
const OPEN_KEYS: ReadonlySet<string> = new Set(['time', 'head', 'model', 'outcome', 'timing']);

/** The four views this page ships. `table` is the default and stands first. */
const DEFAULT_VIEWS: View[] = [
  { id: 'table', name: S.table, layout: 'table', filter: {}, sort: null, group: null, fields: LANDED_FIELDS },
  { id: 'timeline', name: S.timeline, layout: 'timeline', filter: { window: '24h', bucket: '1h' }, sort: null, group: null, fields: ['time', 'head', 'model', 'outcome', 'timing'] },
  { id: 'by-model', name: S.byModel, layout: 'table', filter: {}, sort: null, group: 'model', fields: LANDED_FIELDS },
  { id: 'by-outcome', name: S.byOutcome, layout: 'table', filter: {}, sort: null, group: 'outcome', fields: LANDED_FIELDS },
];

// ------------------------------------------------------------------------ where the time went

const STAGE_ORDER: readonly StageGroup[] = ['ingest', 'queue', 'upstream', 'stream', 'finish'];

export interface StageRow {
  group: StageGroup;
  label: string;
  /** Summed over every loaded turn that reached this part. */
  ms: number;
  /** The average over those turns: what one turn spends here. */
  perTurn: number;
  /** This part's share of all the time the loaded turns spent. */
  share: number;
}

/**
 * Where the loaded turns spent their time, in the five parts of a turn, in pipeline order.
 *
 * THE MARKS ARE CUMULATIVE: each is ms since the request arrived (PerfKeys, "marks are
 * *_ms-since-arrival"), so a part's time is the DIFFERENCE between two marks, which is what
 * entities/perf's `waterfall` computes for one turn. This used to add the raw marks up as if each
 * were a duration, so `finish` (the whole turn) and `stream end` (nearly the whole turn) read half of
 * all time each and every earlier stage read 0.0 % (console review, 2026-09-24). A part a turn never
 * reached is left out of that turn rather than counted as zero, and a part no loaded turn reached is
 * not printed.
 */
export function stageRowsOf(rows: readonly TurnRow[]): StageRow[] {
  const totals = new Map<StageGroup, { ms: number; turns: number }>();
  let sum = 0;
  for (const row of rows) {
    const reached = new Map<StageGroup, number>();
    for (const stage of waterfall(row)) reached.set(stage.group, (reached.get(stage.group) ?? 0) + stage.ms);
    for (const [group, ms] of reached) {
      const at = totals.get(group) ?? { ms: 0, turns: 0 };
      totals.set(group, { ms: at.ms + ms, turns: at.turns + 1 });
      sum += ms;
    }
  }
  return STAGE_ORDER.flatMap((group) => {
    const at = totals.get(group);
    return at === undefined ? [] : [{ group, label: STAGE_NAMES[group], ms: at.ms, perTurn: at.ms / at.turns, share: sum === 0 ? 0 : at.ms / sum }];
  });
}

/** A 0..1 share as a person reads it (@shared/lib's fmtShare, kept under this page's name). */
export const shareText = fmtShare;

/** The loaded turns filed by head, in the order each head first appears. */
function byHead(rows: readonly TurnRow[]): Map<string, TurnRow[]> {
  const heads = new Map<string, TurnRow[]>();
  for (const row of rows) heads.set(row.head, [...(heads.get(row.head) ?? []), row]);
  return heads;
}

/** One bar per head of an average turn in its parts, on one scale, under the whole table's bar,
 *  which alone carries the figures: the per-head bars compare, the first one reads. */
function StageBars({ rows, nameOf }: { rows: readonly TurnRow[]; nameOf: (key: string) => string }) {
  const lines = [...byHead(rows)].map(([head, turns]) => ({ head, stages: stageRowsOf(turns) }));
  const length = (stages: readonly StageRow[]): number => stages.reduce((held, stage) => held + stage.perTurn, 0);
  const scale = Math.max(0, ...lines.map((line) => length(line.stages)));
  const parts = (stages: readonly StageRow[]) => stages.map((stage) => ({
    key: stage.group, label: stage.label, value: stage.perTurn, mark: STAGE_MARKS[stage.group],
  }));
  return (
    <div className="myx-tn-stages">
      <StackedBar label={S.stages} legend format={fmtMs} parts={parts(stageRowsOf(rows))} />
      <div className="myx-tn-stage-heads">
        {lines.map((line) => (
          <div key={line.head} className="myx-tn-stage-head">
            <HeadMark head={line.head}>{nameOf(line.head)}</HeadMark>
            <StackedBar label={`${S.stages} ${nameOf(line.head)}`} format={fmtMs} total={scale} parts={parts(line.stages)} />
            <span className="myx-tn-figure">{fmtMs(length(line.stages))}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

// ------------------------------------------------------------------------------------ tokens

export interface TokenRow {
  head: string;
  in: number;
  cached: number;
  write: number;
  out: number;
}

/** The four token classes per head: what the operator pays for, since a cache read and a cache
 *  write are priced ten to one apart. */
export function tokenRowsOf(rows: readonly TurnRow[]): TokenRow[] {
  const heads = new Map<string, TokenRow>();
  for (const row of rows) {
    const at = heads.get(row.head) ?? { head: row.head, in: 0, cached: 0, write: 0, out: 0 };
    at.in += row.in_tokens ?? 0;
    at.cached += row.cached_tokens ?? 0;
    at.write += row.cache_write_tokens ?? 0;
    at.out += row.out_tokens ?? 0;
    heads.set(row.head, at);
  }
  return [...heads.values()];
}

/** The input's three parts: `in_tokens` holds the cache read and the cache write (PerfKeys), and
 *  what is left is the miss, the part priced in full. The strong grey is the expensive part. */
const INPUT_KEY = [
  { mark: 'series-3', label: S.cached },
  { mark: 'series-2', label: S.written },
  { mark: 'series-1', label: S.uncached },
] as const;

function tokenColumns(scale: number, nameOf: (key: string) => string): Column<TokenRow>[] {
  return [
    { key: 'head', label: S.head, width: '26%', cell: (row) => <HeadMark head={row.head}>{nameOf(row.head)}</HeadMark> },
    {
      key: 'input',
      label: S.input,
      width: '40%',
      cell: (row) => (
        <span className="myx-tn-split">
          <StackedBar
            label={S.input}
            total={scale}
            format={fmtTokens}
            parts={[
              { key: 'cached', label: S.cached, value: row.cached, mark: INPUT_KEY[0].mark },
              { key: 'write', label: S.written, value: row.write, mark: INPUT_KEY[1].mark },
              { key: 'miss', label: S.uncached, value: Math.max(0, row.in - row.cached - row.write), mark: INPUT_KEY[2].mark },
            ]}
          />
          <span className="myx-tn-figure">{fmtTokens(row.in)}</span>
        </span>
      ),
    },
    {
      key: 'hit',
      label: S.cacheHit,
      width: '20%',
      cell: (row) => (row.in === 0 ? S.absent : (
        <Meter tone="neutral" value={row.cached / row.in} label={`${S.cacheHit} ${fmtShare(row.cached / row.in)}`} figure={fmtShare(row.cached / row.in)} />
      )),
    },
    { key: 'out', label: S.output, width: '14%', align: 'end', mono: true, cell: (row) => fmtTokens(row.out) },
  ];
}

/** One turn's prompt in its three parts, with the figures, and what it answered with. */
function TurnTokens({ row }: { row: TurnRow }) {
  if (row.in_tokens === undefined && row.out_tokens === undefined) return <>{S.absent}</>;
  const input = row.in_tokens ?? 0;
  const cached = row.cached_tokens ?? 0;
  const write = row.cache_write_tokens ?? 0;
  return (
    <div className="myx-tn-turn-tokens">
      <StackedBar
        label={S.input}
        legend
        format={fmtTokens}
        parts={[
          { key: 'cached', label: S.cached, value: cached, mark: INPUT_KEY[0].mark },
          { key: 'write', label: S.written, value: write, mark: INPUT_KEY[1].mark },
          { key: 'miss', label: S.uncached, value: Math.max(0, input - cached - write), mark: INPUT_KEY[2].mark },
        ]}
      />
      <KeyValue
        rows={[
          [S.input, row.in_tokens === undefined ? S.absent : fmtTokens(row.in_tokens)],
          [S.output, row.out_tokens === undefined ? S.absent : fmtTokens(row.out_tokens)],
          [S.firstByte, row.first_byte === undefined ? S.absent : fmtMs(row.first_byte)],
        ]}
      />
    </div>
  );
}

// ------------------------------------------------------------------------------------ summary

/** An idle head as the line names it, with when it last ran a turn when the daemon says
 *  (`last_ts`): `bonsai 2d ago`, `bonsai-vast never`. */
function lastText(last: number | null | undefined): string | null {
  if (last === undefined) return null;
  return last === null ? S.never : `${U.last} ${timeAgo(last)}`;
}

/** The heads a window holds no turns for, named on one line: an absence said once, not per row. */
export function IdleHeads({ summary }: { summary: PerfSummaryPayload }) {
  const idle = summary.heads.filter((head) => head.empty);
  if (idle.length === 0) return null;
  return (
    <p className="myx-tn-idle">
      <span className="myx-tn-idle-name">{S.noTurnsIn}</span>
      {idle.map((head) => {
        const last = lastText(head.last_ts);
        return (
          <span key={head.key} className="myx-tn-idle-head">
            <HeadMark head={head.key}>{head.label}</HeadMark>
            {last === null ? null : <span className="myx-tn-quiet">{last}</span>}
          </span>
        );
      })}
    </p>
  );
}

// -------------------------------------------------------------------------------------- board

export interface TurnsBoardProps {
  /** Each head's gate: slots in use, the limit and the queue. */
  slots?: readonly HeadSlots[];
  /** The live turns, where the daemon lists them. */
  inflight: InflightTurn[];
  landed: TurnsState | PendingRoute | null;
  summary: PerfSummaryPayload | null;
  /** The capture the console holds; the drawer asks it for the open turn's head alone. */
  capture: CaptureCell | null;
  locked?: boolean;
  error?: string | null;
  /** When the landed turns on screen were read, which the fault prints as stale while `error` stands. */
  lastRead?: number | null;
  /** The fixture's own file name when a fixture fed this board, undefined otherwise: the capture
   *  marker and the sample chrome are the same value, so they cannot disagree. */
  sample?: string | undefined;
}

export function TurnsBoard({ slots = [], inflight, landed, summary, capture, locked = false, error = null, lastRead = null, sample }: TurnsBoardProps) {
  const { active } = useViews(PAGE_ID, DEFAULT_VIEWS);
  const [openKey, setOpenKey] = useState<string | null>(null);

  const pending = landed !== null && 'pending' in landed;
  const rows = landed !== null && !pending ? landed.landed : [];
  const unread = landed !== null && !pending ? landed.unread : [];
  // The rows carry the head KEY (`bonsai`); the summary and the fleet print its label
  // (`claude-bonsai`), the name the operator launches it by. One name per head on the page.
  const labels = new Map((summary?.heads ?? []).map((head) => [head.key, head.label]));
  const nameOf = (key: string): string => labels.get(key) ?? key;

  const selection = selectionOf(rows, active, Date.now());
  // A row's key is its head and ts, with an ordinal only among twins, computed ONCE per render so
  // the table, the selection and the open panel all read the same key for the same row.
  const keyer = rowKeyer();
  const listed = selection.kind === 'table' ? selection.rows
    : selection.kind === 'groups' ? selection.groups.flatMap((group) => group.rows)
      : [...selection.timeline.buckets.flatMap((bucket) => bucket.rows), ...selection.timeline.undated];
  const keys = new Map(listed.map((row) => [row, keyer(row)]));
  const keyOf = (row: TurnRow): string => keys.get(row) ?? `${row.head}:${row.ts}`;
  const open = listed.find((row) => keyOf(row) === openKey) ?? null;

  // Re-read on every opened turn, not only on a new head: the settings the daemon runs change at a
  // restart, and the turn the operator just opened is the moment they are asking about.
  useEffect(() => {
    if (open === null) return;
    void fetchCapture(open.head);
  }, [open?.head, open?.ts]);

  if (locked) return <Empty text={S.locked} />;
  if (error !== null && landed === null) return <Fault message={error} />;

  const by = groupByOf(active);
  // Opened, the table gives the detail its room and keeps the columns that find a turn: the cache
  // and the tokens move into the detail, where they were cut to `90!` and `1...` beside it.
  const keysShown = landedKeysOf(active.fields)
    .filter((key) => key !== by)
    .filter((key) => open === null || OPEN_KEYS.has(key));
  const scale = Math.max(0, ...listed.map(lengthOf));
  const columns = landedColumns(keysShown, scale, nameOf);

  const groups: RowGroup<TurnRow>[] | null = selection.kind === 'groups'
    ? selection.groups.map((group) => ({
      key: group.key,
      title: by === 'outcome' ? <Badge tone={group.key === 'ok' ? 'ok' : 'danger'} quiet>{group.key}</Badge> : group.key,
      count: group.count,
      rows: group.rows,
    }))
    : selection.kind === 'timeline'
      ? [
        ...selection.timeline.buckets
          .filter((bucket) => bucket.rows.length > 0)
          .map((bucket) => ({ key: String(bucket.start), title: clockOf(bucket.start), count: bucket.rows.length, rows: bucket.rows })),
        ...(selection.timeline.undated.length === 0
          ? []
          : [{ key: 'undated', title: S.undated, count: selection.timeline.undated.length, rows: selection.timeline.undated }]),
      ]
      : null;

  // Where the loaded turns stop being every turn (V4-290): a busy day holds more than the read's cap,
  // so the timeline is drawn from there, and an hour before it with no rows is unread, not idle.
  const completeFrom = landed !== null && !pending ? landed.completeFrom : undefined;
  const cutAt = selection.kind === 'timeline' && completeFrom !== undefined && completeFrom > selection.window.from
    ? completeFrom
    : null;
  const idleHours = selection.kind === 'timeline'
    ? selection.timeline.buckets.filter((bucket) => bucket.rows.length === 0 && (cutAt === null || bucket.start >= cutAt)).length
    : 0;
  const cutText = cutAt === null ? '' : `, ${U.completeFrom} ${atText(cutAt) ?? S.absent}`;
  // An hour is idle only where every head was read: a head that could not be read may have served
  // turns in any of them, and it is named above the table (V4-300).
  const idleText = unread.length > 0 ? '' : `, ${idleHours} ${U.idle}`;
  const ran = summary?.heads.filter((head) => !head.empty) ?? [];
  const counted = slots.reduce((held, head) => held + head.inflight, 0);
  const unlisted = Math.max(0, counted - inflight.length);
  const tokenRows = tokenRowsOf(rows);
  const tokenScale = Math.max(0, ...tokenRows.map((row) => row.in));

  let landedBody: ReactNode;
  if (pending) landedBody = <Empty text={S.historyUnavailable} source={H.historyUnavailable} />;
  else if (listed.length === 0) landedBody = <Empty text={S.noTurns} source={H.noTurns} />;
  else {
    const table = {
      columns,
      rowKey: keyOf,
      label: S.landed,
      onOpen: (row: TurnRow) => setOpenKey(keyOf(row)),
      openLabel: (row: TurnRow) => `${S.detail} ${nameOf(row.head)} ${row.model ?? S.absent}`,
      selectedKey: openKey,
      rowTone: (row: TurnRow) => (row.outcome === 'ok' ? null : 'danger' as const),
    };
    landedBody = groups === null
      ? <DataTable className="myx-tn-table" {...table} rows={listed} />
      : <DataTable className="myx-tn-table" {...table} groups={groups} />;
  }

  return (
    <div className="myx-tn">
      <PageHeader
        title={S.title}
        info={{ text: H.about, label: S.about }}
        actions={sample === undefined ? undefined : <Badge tone="neutral">{S.sample}</Badge>}
      >
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
      </PageHeader>

      {/* A read that fails after one landed keeps the turns and says so. The fault used to show
          only while nothing had loaded (console walkthrough, 2026-09-24): with the daemon down this
          page printed its last summary and stage times as if they were live, and no fault at all. */}
      {error === null ? null : <Fault message={error} lastRead={lastRead} />}

      <Section title={S.inflight} count={Math.max(inflight.length, counted)}>
        {slots.length === 0 && inflight.length === 0 ? <Empty text={S.nothingInFlight} source={H.nothingInFlight} /> : null}
        {slots.length === 0 ? null : <Gates slots={slots} />}
        {/* The gates count turns the daemon does not list (HeadStatus.kt writes the list empty, row
            V4-213): said as a count of unlisted turns, never as "nothing in flight". */}
        {unlisted > 0 ? (
          <p className="myx-tn-unlisted">
            <Badge tone="neutral">{`${unlisted} ${U.unlisted}`}</Badge>
            <InfoTip text={H.unlisted} label={S.unlistedWhy} />
          </p>
        ) : null}
        {inflight.length === 0 ? null : (
          <DataTable
            columns={inflightColumns(nameOf)}
            rows={inflight}
            rowKey={(turn) => `${turn.head}:${turn.label}`}
            label={S.inflight}
            rowTone={(turn) => (turn.idleMs > turn.streamIdleMs ? 'warn' : null)}
          />
        )}
      </Section>

      <Section title={S.summary} {...(summary === null ? {} : { count: ran.length })} info={{ text: H.summary, label: S.summaryWhy }}>
        {summary === null ? (
          <Empty text={S.noSummary} source={H.noSummary} />
        ) : (
          <>
            {ran.length === 0 ? null : (
              <DataTable columns={summaryColumns(ran)} rows={ran} rowKey={(head) => head.key} label={S.summary} />
            )}
            <IdleHeads summary={summary} />
          </>
        )}
      </Section>

      {pending || rows.length === 0 ? null : (
        <div className="myx-tn-pair">
          <Section title={S.stages} info={{ text: H.stages, label: S.stagesWhy }}>
            <StageBars rows={rows} nameOf={nameOf} />
          </Section>
          <Section title={S.tokens} info={{ text: H.tokens, label: S.tokensWhy }} actions={<Legend items={INPUT_KEY} label={S.tokensKey} />}>
            <DataTable columns={tokenColumns(tokenScale, nameOf)} rows={tokenRows} rowKey={(row) => row.head} label={S.tokens} />
          </Section>
        </div>
      )}

      {/* The capture marker (law 23): set on the same DEV branch as the fixture import and
          carrying that fixture's own file name, so a driver asserts "the fixture loaded" instead of
          inferring it. The guard is IN the expression, so a production build drops the branch and
          the attribute's very name - fixture-leak.mjs asserts it is absent from dist. */}
      <div
        className={open === null ? 'myx-tn-board' : 'myx-tn-board myx-tn-board-open'}
        {...(import.meta.env.DEV && sample !== undefined ? { 'data-sample': sample } : {})}
      >
        <Section
          title={S.landed}
          {...(pending ? {} : { count: rows.length })}
          {...(selection.kind === 'timeline' ? { meta: `${selection.window.hours}h ${U.window}${cutText}${idleText}` } : {})}
          actions={listed.length === 0 ? undefined : <Legend items={[
            { mark: STAGE_MARKS.ingest, label: STAGE_NAMES.ingest },
            { mark: STAGE_MARKS.upstream, label: S.waits },
            { mark: STAGE_MARKS.stream, label: STAGE_NAMES.stream },
          ]} label={S.stagesKey} />}
        >
          {/* A head whose turns could not be read is NAMED, in the daemon's words: the table below
              is missing its rows, and a table that silently lost a head reads like one that idled. */}
          {unread.map((head) => <Fault key={`${head.head}:${head.reason}`} message={`${nameOf(head.head)}: ${head.reason}`} />)}
          {landedBody}
        </Section>

        {/* THE DETAIL IS UNMOUNTED AT REST: nothing holds a column until a row is opened, and an
            empty labelled <aside> would still be a landmark in a reader's list (M1-123). */}
        {open === null ? null : (
          <DetailPanel
            title={`${nameOf(open.head)} ${open.model ?? S.absent}`}
            label={S.detail}
            status={(
              <span className="myx-tn-badges">
                {badgesOf(open).map((badge) => <Badge key={badge.key} tone={badge.tone} quiet>{badge.text}</Badge>)}
              </span>
            )}
            onClose={() => setOpenKey(null)}
            closeLabel={S.close}
          >
            <Section title={S.timing}>
              <TurnWaterfall row={open} />
            </Section>
            <Section title={S.tokens}>
              <TurnTokens row={open} />
            </Section>
            <Section title={S.capture}>
              {/* Only this turn's head: another head's capture or failure never stands in while this
                  head's read is in flight, or after it never lands (V4-301). */}
              <RequestDrawer
                {...captureFor(capture, open.head)}
                onSwitch={(enabled) => void putCapture(open.head, enabled)}
              />
            </Section>
          </DetailPanel>
        )}
      </div>
    </div>
  );
}

/** The DEV-only sample board a capture reads (CONTRACTS.md section 4, the fixture rule). */
interface Fixture {
  inflight: InflightTurn[];
  landed: TurnRow[];
  summary: PerfSummaryPayload;
}

/** One fixture, as ONE value: the name it was asked for and the bytes that arrived. The capture
 *  marker is set from this and from nothing else, so a name with no module can never leave a
 *  marker behind - a marker that survives a failed import says the opposite of the truth (law 23:
 *  an instrument must be able to distinguish PASSED, FAILED and DID NOT RUN). Exported because a
 *  test pins exactly that, with a name that resolves to no file at all. */
export async function loadFixture(name: string): Promise<{ name: string; payload: Fixture } | null> {
  if (!import.meta.env.DEV) return null;
  const module = await import(/* @vite-ignore */ `./fixtures/${name}.ts`)
    .then((loaded: { fixture?: Fixture }) => loaded)
    .catch(() => null);
  const payload = module === null ? null : module.fixture ?? null;
  return payload === null ? null : { name, payload };
}

function fixtureName(): string | null {
  if (!import.meta.env.DEV || typeof window === 'undefined') return null;
  const fromSearch = new URLSearchParams(window.location.search).get('fixture');
  if (fromSearch !== null) return fromSearch;
  const at = window.location.hash.indexOf('?');
  return at === -1 ? null : new URLSearchParams(window.location.hash.slice(at)).get('fixture');
}

/** The rows a timeline reads per head: the route's own ceiling (PerfRoutes.kt MAX_TURNS), as the
 *  Teams day reads, so its window is short only on a day busier than the route serves, and then
 *  the meta says where it starts. */
const WINDOW_CAP = 2_000;

/** How often the landed turns are re-read: the fleet's newest every 5 s, a timeline's window (the
 *  heaviest read on the page) at the 15 s its summary is read at, which hourly buckets never outpace. */
const TAIL_EVERY_MS = 5_000;
const WINDOW_EVERY_MS = 15_000;

/** One read of the landed turns for a view: what the page's poll runs on every tick. */
export function readTurnsFor(view: View): Promise<void> {
  const since = sinceOf(view, Date.now());
  return since === null ? fetchPerfTurns() : fetchPerfTurns(undefined, WINDOW_CAP, since);
}

export default function TurnsPage() {
  const { active } = useViews(PAGE_ID, DEFAULT_VIEWS);
  const locked = useSession((s) => s.locked);
  const heads = useHeads((s) => s);
  const turns = usePerfTurns((s) => s);
  const summary = usePerfSummary((s) => s);
  const capture = useCapture((s) => s);
  const [sample, setSample] = useState<{ name: string; payload: Fixture } | null>(null);
  const name = fixtureName();
  const fixture = sample === null ? null : sample.payload;

  useEffect(() => {
    const stops = [startHeadsPolling(2000), startPerfSummaryPolling('24h', 15000)];
    return () => stops.forEach((stop) => stop());
  }, []);

  useEffect(() => poll(() => readTurnsFor(active), isTimeline(active) ? WINDOW_EVERY_MS : TAIL_EVERY_MS), [active]);

  useEffect(() => {
    if (name === null) {
    // The address no longer asks for this page's fixture, so the marker must GO: a name that is
    // asked for and then dropped is exactly the stale marker this row exists to prevent (measured
    // in a browser on 2026-09-18 - five pages kept one across a hash change, because the early
    // return left the previous state in place; a static render cannot see an effect, so the suite
    // was green while it happened).
      setSample(null);
      return undefined;
    }
    let live = true;
    // The whole value, name and bytes together: a second state for the name would be the stale
    // marker this row exists to prevent.
    void loadFixture(name).then((loaded) => {
      if (live) setSample(loaded);
    });
    return () => {
      live = false;
    };
  }, [name]);

  return (
    <TurnsBoard
      slots={fixture !== null ? [] : slotsFrom(heads.data ?? [])}
      inflight={fixture !== null ? fixture.inflight : inflightFrom(heads.data ?? [])}
      landed={fixture !== null ? { inflight: fixture.inflight, landed: fixture.landed, unread: [], truncated: [] } : turns.data}
      summary={fixture !== null ? fixture.summary : summary.data}
      capture={capture.data}
      locked={locked}
      error={fixture === null ? turns.error : null}
      lastRead={turns.lastUpdated}
      sample={sample?.name}
    />
  );
}
