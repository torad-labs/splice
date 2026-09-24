// The turns page: what is running now, what landed, and how each turn spent its time.
//
// THREE BAYS, and what each one can honestly say today.
//   in flight  - the gate snapshots on GET /api/heads, which exist: one strip per live turn, with
//                the phase and the idle time, cocked when the turn has outlived the head's own
//                idle threshold (the difference between reasoning and hung).
//   summary    - GET /api/perf/summary, which exists: per head, the windowed percentiles, the
//                failure share and the dropped-telemetry lower bound.
//   landed     - GET /api/perf/turns, which does NOT exist yet (V4-127): the honest empty names
//                that row, and no mocked row ever stands in for it.
//
// The waterfall is the answer to "why was this slow": queue wait, upstream wait and streaming as
// separate rows of one bar, because the daemon records where the time went and never why (the
// console labels the phases and stops there - FEATURES.md 2.12).
import { useEffect, useRef, useState } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import { ViewTabs, useViews } from '@features/views';
import type { View } from '@features/views';
import {
  fetchCapture,
  inflightFrom,
  putCapture,
  startPerfSummaryPolling,
  startPerfTurnsPolling,
  useCapture,
  usePerfSummary,
  usePerfTurns,
  waterfall,
} from '@entities/perf';
import type {
  CaptureState,
  InflightTurn,
  PendingRoute,
  PerfSummaryHead,
  PerfSummaryPayload,
  StageGroup,
  TurnRow,
  TurnsState,
} from '@entities/perf';
import { useHeads, startHeadsPolling } from '@entities/heads';
import { useSession } from '@entities/session';
import { RequestDrawer, Waterfall } from '@widgets/waterfall';
import { Bay, Empty, Figure, HolderEdge, Strip, StripField } from '@shared/ui';
import { Fault } from '@shared/controls';
import { fmtMs, timeAgo } from '@shared/lib';
import type { Basis } from '@shared/ui';
import { basisProp } from './strip';
import { S } from './strings';
import { itemsOf, selectionOf } from './select';
import type { Selection } from './select';
import { InflightStrip, LandedNames, TurnStrip } from './strip';
import './turns.css';

const PAGE_ID = 'turns';

const LANDED_FIELDS = [
  'time', 'head', 'model', 'outcome', 'total', 'firstByte', 'tokensIn', 'cached', 'cacheWrite',
  'tokensOut', 'retries', 'attempts', 'inflight', 'dropped',
];
const INFLIGHT_FIELDS = ['session', 'head', 'phase', 'age', 'idle', 'compact'];

/** The four views this page ships. `table` is the default and stands first. */
const DEFAULT_VIEWS: View[] = [
  { id: 'table', name: 'table', layout: 'table', filter: {}, sort: null, group: null, fields: LANDED_FIELDS },
  { id: 'timeline', name: 'timeline', layout: 'timeline', filter: { window: '24h', bucket: '1h' }, sort: null, group: null, fields: ['time', 'head', 'model', 'outcome', 'total'] },
  { id: 'by-model', name: 'by model', layout: 'table', filter: {}, sort: null, group: 'model', fields: LANDED_FIELDS },
  { id: 'by-outcome', name: 'by outcome', layout: 'table', filter: {}, sort: null, group: 'outcome', fields: LANDED_FIELDS },
];

const ROW_H = 40;

/** A cell the rollup does not carry prints the absence glyph, never a zero the daemon did not
 *  report. It carries no basis: `–` is the whole statement, and the word `unavailable` beside it
 *  said the same thing twice (m1 design review B8). */
function cell(value: number | undefined, format: (n: number) => string): { value: string; basis?: Basis | undefined } {
  return value === undefined ? { value: S.absent } : { value: format(value), basis: 'measured' };
}

/** The summary rack's own columns (it summarizes a window, so its fields are not the landed
 *  rack's), declared once for the bay head and the rows below it (CONTRACTS.md section 2, m1
 *  design review B9). */
/**
 * WHERE THE TIME WENT, AND WHAT IT COST (M2-20) -- TWO SMALL MEMBERS, EACH WEARING ITS OWN HEADER.
 *
 * WHY THESE TWO AND NOT THE OTHER SIXTY. M2-20's trap is printing the headroom: turns holds 117
 * served fields and prints 55, and consuming that inventory would be the padded page M1-111 refused
 * from the other direction. M1-109 measured what actually earns PRINTED -- the grey header strip a
 * table wears, and the plate a bay label sits on -- and the comp earns 6.17 by composing MANY SMALL
 * MEMBERS where our pages compose few large ones. So the test for a member is not "is this field
 * served" but "does it answer a question a person has", and this page's person is scanning for cost,
 * latency and outcome.
 *
 * WHAT EARNED ITS PLACE. The page's own header says the daemon "records where the time went and
 * never why", and the summary rack prints P50 and P95 totals -- it says how LONG a turn took and
 * never WHERE. The pipeline marks are served on every row, are the daemon's own instrumentation,
 * and answer the question the page's comment names, summed into the five parts of a turn the
 * waterfall already groups them by (see stageRowsOf). SECOND MEMBER: the summary prints ONE cache scalar, while the four token
 * classes (in, cached, cache write, out) are served and unprinted -- and cached versus written is
 * the ten-to-one price difference the operator actually pays. Both are per-head rows, which is the
 * grain the rest of this page reads.
 *
 * WHAT WAS REJECTED, WITH REASONS, because a census that only lists what it took is an inventory:
 *   - THE TRANSPORT FIELDS (req_bytes, upstream_req_bytes, sse_bytes_in, bytes_out, events_in,
 *     frames_out, content_frames_out, frames_skipped): they answer a protocol engineer's question
 *     and not this page's. A reader scanning for cost, latency and outcome cannot act on a frame
 *     count.
 *   - THE RETRY BREAKDOWN (attempts, post_send_retries, reanchors, backoff_ms, auth_ms, refresh_ms,
 *     write_ms, usage_ms, stall_ms): mostly zero on healthy traffic, so a table of them spends a
 *     header to say nothing, and the summary already prints retries and refreshes as scalars. If a
 *     retry ever earns a place it earns it as a column on a table that is already there.
 *   - THE PER-TURN IDENTITY FIELDS (session, account, cache_cold, inflight, async_io_drops,
 *     tools_eager, tools_deferred): they belong to the strip that is already rendering that turn,
 *     not to a second table about it.
 *   - first_byte, first_frame, first_delta, total, ts, model, outcome, compact: ALREADY PRINTED,
 *     by the summary rack and the landed strips.
 */
const STAGE_GROUPS: readonly { group: StageGroup; label: string }[] = [
  { group: 'ingest', label: S.stageIngest },
  { group: 'queue', label: S.stageQueue },
  { group: 'upstream', label: S.stageUpstream },
  { group: 'stream', label: S.stageStream },
  { group: 'finish', label: S.stageFinish },
];

export interface StageRow {
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
 * entities/perf's `waterfall` computes for one turn. This table used to add the raw marks up as if
 * each were a duration, so `finish` (the whole turn) and `stream end` (nearly the whole turn) read
 * half of all time each and every earlier stage read 0.0 % (console review, 2026-09-24). A part a
 * turn never reached is left out of that turn rather than counted as zero, and a part no loaded turn
 * reached is not printed.
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
  return STAGE_GROUPS.flatMap(({ group, label }) => {
    const at = totals.get(group);
    return at === undefined ? [] : [{ label, ms: at.ms, perTurn: at.ms / at.turns, share: sum === 0 ? 0 : at.ms / sum }];
  });
}

/** A 0..1 share as a person reads it: `25%`, with one decimal under ten so 2.4% is not 2%. */
export function shareText(share: number): string {
  const value = share * 100;
  if (value === 0) return '0%';
  if (value < 0.1) return '<0.1%';
  return `${value < 10 ? value.toFixed(1) : value.toFixed(0)}%`;
}

/** The four token classes per head, with the cache hit share: what the operator pays for. */
function tokenRowsOf(rows: readonly TurnRow[]): { head: string; in: number; cached: number; write: number; out: number; hit: number }[] {
  const byHead = new Map<string, { head: string; in: number; cached: number; write: number; out: number; hit: number }>();
  for (const row of rows) {
    const at = byHead.get(row.head) ?? { head: row.head, in: 0, cached: 0, write: 0, out: 0, hit: 0 };
    at.in += row.in_tokens ?? 0;
    at.cached += row.cached_tokens ?? 0;
    at.write += row.cache_write_tokens ?? 0;
    at.out += row.out_tokens ?? 0;
    byHead.set(row.head, at);
  }
  return [...byHead.values()].map((at) => ({ ...at, hit: at.in === 0 ? 0 : at.cached / at.in }));
}

/** The window is the bay's own label now, not a column repeating `24h` on every row; the rest
 *  are sized to what they print (`10.6s`, `98%`), which is what let the rack fit its bay. */
export const SUMMARY_COLUMNS: readonly { key: string; label: string; w: number }[] = [
  { key: 'head', label: S.head, w: 20 },
  { key: 'rows', label: S.rows, w: 8 },
  { key: 'first_p50', label: S.firstByte, w: 10 },
  { key: 'first_p95', label: S.firstByteP95, w: 12 },
  { key: 'total_p50', label: S.turnTime, w: 10 },
  { key: 'total_p95', label: S.turnTimeP95, w: 12 },
  { key: 'failure', label: S.failureShare, w: 8 },
  { key: 'retries', label: S.retries, w: 8 },
  { key: 'refreshes', label: S.refreshes, w: 9 },
  { key: 'cache', label: S.cacheHit, w: 9 },
  { key: 'peak', label: S.peakInflight, w: 13 },
  { key: 'drops', label: S.ioDrops, w: 12 },
];

function summaryFields(head: PerfSummaryHead): { key: string; label: string; w: number; value: string; basis?: Basis | undefined }[] {
  const first = head.time_before_first_byte_ms;
  const total = head.total_ms;
  const values: Record<string, { value: string; basis?: Basis | undefined }> = {
    head: { value: head.label, basis: 'measured' },
    rows: { value: String(head.count), basis: 'measured' },
    first_p50: cell(first?.p50, fmtMs),
    first_p95: cell(first?.p95, fmtMs),
    total_p50: cell(total?.p50, fmtMs),
    total_p95: cell(total?.p95, fmtMs),
    failure: cell(head.failure_share, shareText),
    retries: cell(head.retries, String),
    refreshes: cell(head.refreshes, String),
    cache: cell(head.cache_hit_ratio ?? undefined, shareText),
    peak: cell(head.peak_inflight, String),
    drops: cell(head.io_drops_in_window, String),
  };
  return SUMMARY_COLUMNS.flatMap((column) => {
    const found = values[column.key];
    return found === undefined ? [] : [{ ...column, ...found }];
  });
}

/** The sentence an empty window prints, which is FEATURES.md 4.3's own: a window with no rows says
 *  so, and never reads as zero latency. It lives here, not in strings.ts, because an honest empty
 *  is not a label (CONTRACTS.md section 4). It is what a reader who needs the whole statement gets
 *  — the strip's aria-label — because the printed edge carries the state in two words. */
export const NO_ROWS = 'no turns in this window';

/** Where a turn goes once it lands: said under every rack of landed rows when there are none. */
const LANDS_HERE = 'turns land here as the heads serve them';

/** One head's windowed summary. A head whose window is empty never gets a strip: eight rows of
 *  dashes buried the two heads that had turns (console review, 2026-09-24), so the empty ones are
 *  NAMED on one line under the rack instead (IdleHeads), and an empty window still never reads as a
 *  fast one. */
function SummaryStrip({ head }: { head: PerfSummaryHead }) {
  return (
    <Strip edge="green" edgeLabel="" ariaLabel={`${S.summary} ${head.label}`}>
      {summaryFields(head).map((field) => (
        <StripField key={field.key} w={field.w} label={field.label} value={field.value} {...basisProp(field.basis)} />
      ))}
    </Strip>
  );
}

/** An idle head as the line names it, with when it last ran a turn when the daemon says
 *  (`last_ts`): `bonsai (last 2d ago)`, `bonsai-vast (never)`. */
function idleName(head: PerfSummaryHead): string {
  if (head.last_ts === undefined) return head.label;
  return head.last_ts === null ? `${head.label} (never)` : `${head.label} (last ${timeAgo(head.last_ts)})`;
}

/** The heads a window holds no turns for, named on one line: an absence said once, not per row. */
export function IdleHeads({ summary }: { summary: PerfSummaryPayload }) {
  const idle = summary.heads.filter((head) => head.empty).map(idleName);
  if (idle.length === 0) return null;
  return <p className="myx-tn-idle">{`${S.noTurnsIn} ${summary.window}: ${idle.join(', ')}`}</p>;
}

/** A band between groups in the virtualized list: the name of what follows, and how much of it. */
function Band({ label, count }: { label: string; count: number }) {
  return (
    <div className="myx-tn-band">
      <HolderEdge state="grey" label={label} />
      <span className="myx-tn-band-count">{count}</span>
    </div>
  );
}

export interface TurnsBoardProps {
  inflight: InflightTurn[];
  landed: TurnsState | PendingRoute | null;
  summary: PerfSummaryPayload | null;
  /** The capture of whichever head was last read; the drawer shows it only for the open turn's. */
  capture: CaptureState | null;
  /** A capture read that failed, in the daemon's words. */
  captureError?: string | null;
  locked?: boolean;
  error?: string | null;
  /** The fixture's own file name when a fixture fed this board, undefined otherwise: the capture
   *  marker and the sample chrome are the same value, so they cannot disagree. */
  sample?: string | undefined;
}

export function TurnsBoard({ inflight, landed, summary, capture, captureError = null, locked = false, error = null, sample }: TurnsBoardProps) {
  const { active } = useViews(PAGE_ID, DEFAULT_VIEWS);
  const [openKey, setOpenKey] = useState<string | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);

  const pending = landed !== null && 'pending' in landed;
  const rows = landed !== null && !pending ? landed.landed : [];
  const unread = landed !== null && !pending ? landed.unread : [];
  const stageRows = stageRowsOf(rows);
  const tokenRows = tokenRowsOf(rows);
  const selection: Selection = selectionOf(rows, active, Date.now());
  const items = pending ? [] : itemsOf(selection);
  const open = items.find((item) => item.kind === 'row' && item.key === openKey);

  const virtualizer = useVirtualizer({
    count: items.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => ROW_H,
    getItemKey: (index) => items[index].key,
    overscan: 10,
  });

  // Re-read on every opened turn, not only on a new head: the settings the daemon runs change at a
  // restart, and the turn the operator just opened is the moment they are asking about.
  useEffect(() => {
    if (open?.kind !== 'row') return;
    void fetchCapture(open.row.head);
  }, [open?.kind === 'row' ? open.row.head : null, open?.kind === 'row' ? open.row.ts : null]);

  const idleBuckets = selection.kind === 'timeline'
    ? selection.timeline.buckets.filter((bucket) => bucket.rows.length === 0).length
    : 0;

  if (locked) return <Empty text="console locked" source="management key" />;
  if (error !== null && landed === null) return <Fault message={error} />;

  return (
    <div className="myx-tn">
      <header className="myx-page-head">
        <h2 className="myx-page-title">{S.title}</h2>
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
        {selection.kind === 'timeline' ? (
          <>
            <Figure value={selection.window.hours} unit="h" basis="measured" />
            <Figure value={idleBuckets} unit={S.idle} basis="measured" />
          </>
        ) : null}
        {sample === undefined ? null : <HolderEdge state="grey" label={S.sample} />}
      </header>

      {/* The capture marker (law 23): set on the same DEV branch as the fixture import and
          carrying that fixture's own file name, so a driver asserts "the fixture loaded" instead of
          inferring it. The guard is IN the expression, so a production build drops the branch and
          the attribute's very name - fixture-leak.mjs asserts it is absent from dist. */}
      <div
        className={open === undefined ? 'myx-tn-board' : 'myx-tn-board myx-tn-board-open'}
        {...(import.meta.env.DEV && sample !== undefined ? { 'data-sample': sample } : {})}
      >
        <div className="myx-tn-bays">
          <Bay
            label={S.inflight}
            count={inflight.length}
            compact
            empty={{ text: 'nothing in flight', source: 'a turn shows here while it runs' }}
          >
            {inflight.map((turn) => (
              <InflightStrip key={`${turn.head}:${turn.label}`} turn={turn} order={INFLIGHT_FIELDS} />
            ))}
          </Bay>

          <Bay
            label={summary === null ? S.summary : `${S.summary} ${summary.window}`}
            compact
            {...(summary === null ? {} : { count: summary.heads.filter((head) => !head.empty).length })}
            empty={{ text: 'no summary yet', source: 'the daemon has not answered' }}
          >
            {summary?.heads.filter((head) => !head.empty).map((head) => <SummaryStrip key={head.key} head={head} />)}
            {summary === null ? null : <IdleHeads key="idle" summary={summary} />}
          </Bay>

          {/* THE TWO COMPOSED MEMBERS (M2-20). Each is a small table wearing its own header, which
              is the unit M1-109 measured the comp earning its printed area with. They sit between
              the summary and the landed rack because they are aggregates over the same rows: the
              summary says how long turns took, these say where the time went and what it cost. */}
          <Bay
            label={S.stages}
            compact
            {...(pending ? {} : { count: stageRows.length, empty: { text: NO_ROWS, source: LANDS_HERE } })}
          >
            {stageRows.map((stage) => (
              <Strip key={stage.label} edge="grey" edgeLabel="" ariaLabel={`${S.stage} ${stage.label}`}>
                <StripField w={20} label={S.stage} value={stage.label} mono={false} />
                <StripField w={10} label={S.perTurn} value={fmtMs(stage.perTurn)} basis="measured" />
                <StripField w={8} label={S.share} value={shareText(stage.share)} basis="measured" />
              </Strip>
            ))}
          </Bay>

          <Bay
            label={S.tokens}
            compact
            {...(pending ? {} : { count: tokenRows.length, empty: { text: NO_ROWS, source: LANDS_HERE } })}
          >
            {tokenRows.map((row) => (
              <Strip key={row.head} edge="grey" edgeLabel="" ariaLabel={`${S.tokens} ${row.head}`}>
                <StripField w={20} label={S.head} value={row.head} mono={false} />
                <StripField w={11} label={S.tokIn} value={row.in.toLocaleString('en-US')} />
                <StripField w={11} label={S.tokCached} value={row.cached.toLocaleString('en-US')} />
                <StripField w={13} label={S.tokWrite} value={row.write.toLocaleString('en-US')} />
                <StripField w={11} label={S.tokOut} value={row.out.toLocaleString('en-US')} />
                <StripField w={8} label={S.hit} value={row.in === 0 ? S.absent : shareText(row.hit)} {...basisProp(row.in === 0 ? undefined : 'measured')} />
              </Strip>
            ))}
          </Bay>

          <Bay
            label={S.landed}
            compact
            {...(pending ? {} : { count: rows.length, empty: { text: NO_ROWS, source: LANDS_HERE } })}
          >
            {/* A head whose turns could not be read is NAMED, in the daemon's words: the list below is
                missing its rows, and a rack that silently lost a head reads exactly like one that
                was idle. */}
            {unread.map((head) => <Fault key={`${head.head}:${head.reason}`} message={`${head.head}: ${head.reason}`} />)}
            {pending ? (
              <Empty text="turn history unavailable" source="this splice version does not serve it" />
            ) : (
              <div className="myx-tn-scroll" ref={scrollRef}>
                <LandedNames order={active.fields} />
                <div className="myx-tn-inner" style={{ height: virtualizer.getTotalSize() }}>
                  {virtualizer.getVirtualItems().map((item) => {
                    const entry = items[item.index];
                    return (
                      <div
                        key={entry.key}
                        className="myx-tn-item"
                        data-index={item.index}
                        ref={virtualizer.measureElement}
                        style={{ transform: `translateY(${item.start}px)` }}
                      >
                        {entry.kind === 'band' ? (
                          <Band label={entry.label} count={entry.count} />
                        ) : (
                          <TurnStrip
                            row={entry.row}
                            selected={entry.key === openKey}
                            order={active.fields}
                            onOpen={() => setOpenKey(entry.key)}
                          />
                        )}
                      </div>
                    );
                  })}
                </div>
              </div>
            )}
          </Bay>
        </div>

        {/* M1-123'S DECISION, APPLIED VERBATIM (M2-20): the aside stays mounted and keeps its
            aria-label, and carries aria-hidden while collapsed, gated by the SAME expression that
            gates its content -- so the exposure and the content cannot desync, because they are one
            expression rather than two facts kept in step. The shape was decided by the seat holding
            fleet, sessions and projects, with the three rejected alternatives measured off the real
            accessibility tree; this seat is applying it rather than choosing a variant. The column
            stays mounted at rest for the reason collapse beat unmount: the collapsing track needs
            something to transition from. */}
        <aside className="myx-tn-detail myx-swell" aria-label={S.detail} aria-hidden={open?.kind !== 'row' ? true : undefined}>
          {open?.kind !== 'row' ? null : (
            <>
              <div className="myx-tn-detail-head">
                <span className="myx-tn-detail-name">{`${open.row.head} ${open.row.model ?? S.absent}`}</span>
                <button type="button" className="myx-tn-close" onClick={() => setOpenKey(null)}>
                  {S.close}
                </button>
              </div>
              <Bay label={S.title}>
                <Waterfall row={open.row} />
              </Bay>
              <Bay label={S.detail}>
                {/* Another head's capture never stands in for this one while its read is in
                    flight: the drawer waits for a read of the head this turn ran on. */}
                <RequestDrawer
                  capture={capture !== null && capture.running.head === open.row.head ? capture : null}
                  error={captureError}
                  onSwitch={(enabled) => void putCapture(open.row.head, enabled)}
                />
              </Bay>
            </>
          )}
        </aside>
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

export default function TurnsPage() {
  const locked = useSession((s) => s.locked);
  const heads = useHeads((s) => s);
  const turns = usePerfTurns((s) => s);
  const summary = usePerfSummary((s) => s);
  const capture = useCapture((s) => s);
  const [sample, setSample] = useState<{ name: string; payload: Fixture } | null>(null);
  const name = fixtureName();
  const fixture = sample === null ? null : sample.payload;

  useEffect(() => {
    const stops = [startHeadsPolling(2000), startPerfTurnsPolling(undefined, 5000), startPerfSummaryPolling('24h', 15000)];
    return () => stops.forEach((stop) => stop());
  }, []);

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
      inflight={fixture !== null ? fixture.inflight : inflightFrom(heads.data ?? [])}
      landed={fixture !== null ? { inflight: fixture.inflight, landed: fixture.landed, unread: [] } : turns.data}
      summary={fixture !== null ? fixture.summary : summary.data}
      capture={capture.data}
      captureError={capture.error}
      locked={locked}
      error={turns.error}
      sample={sample?.name}
    />
  );
}
