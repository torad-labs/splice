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
  startPerfSummaryPolling,
  startPerfTurnsPolling,
  useCapture,
  usePerfSummary,
  usePerfTurns,
} from '@entities/perf';
import type {
  CaptureSlice,
  InflightTurn,
  PendingRoute,
  PerfSummaryHead,
  PerfSummaryPayload,
  TurnRow,
  TurnsState,
} from '@entities/perf';
import { useHeads, startHeadsPolling } from '@entities/heads';
import { useSession } from '@entities/session';
import { RequestDrawer, Waterfall } from '@widgets/waterfall';
import { Bay, Empty, Figure, HolderEdge, Strip, StripField } from '@shared/ui';
import { Fault } from '@shared/controls';
import type { Basis } from '@shared/ui';
import { basisProp } from './strip';
import { S } from './strings';
import { itemsOf, selectionOf } from './select';
import type { Selection } from './select';
import { InflightStrip, TurnStrip } from './strip';
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
 *  report. It carries no basis: `n/r` is the whole statement, and the word `unavailable` beside it
 *  said the same thing twice (m1 design review B8). */
function cell(value: number | undefined, format: (n: number) => string): { value: string; basis?: Basis | undefined } {
  return value === undefined ? { value: S.absent } : { value: format(value), basis: 'measured' };
}

/** The summary rack's own columns (it summarizes a window, so its fields are not the landed
 *  rack's), declared once for the bay head and the rows below it (CONTRACTS.md section 2, m1
 *  design review B9). */
export const SUMMARY_COLUMNS: readonly { key: string; label: string; w: number }[] = [
  { key: 'head', label: S.head, w: 20 },
  { key: 'window', label: S.time, w: 15 },
  { key: 'rows', label: S.rows, w: 15 },
  { key: 'first_p50', label: S.firstByte, w: 15 },
  { key: 'first_p95', label: `${S.firstByte} p95`, w: 15 },
  { key: 'total_p50', label: S.total, w: 15 },
  { key: 'total_p95', label: `${S.total} p95`, w: 15 },
  { key: 'failure', label: S.failureShare, w: 15 },
  { key: 'retries', label: S.retries, w: 15 },
  { key: 'refreshes', label: S.refreshes, w: 15 },
  { key: 'cache', label: S.cacheHit, w: 15 },
  { key: 'peak', label: S.peakInflight, w: 15 },
  { key: 'drops', label: S.ioDrops, w: 15 },
];

function summaryFields(head: PerfSummaryHead): { key: string; label: string; w: number; value: string; basis?: Basis | undefined }[] {
  const first = head.time_before_first_byte_ms;
  const total = head.total_ms;
  const values: Record<string, { value: string; basis?: Basis | undefined }> = {
    head: { value: head.label, basis: 'measured' },
    window: { value: head.window, basis: 'measured' },
    rows: { value: String(head.count), basis: 'measured' },
    first_p50: cell(first?.p50, String),
    first_p95: cell(first?.p95, String),
    total_p50: cell(total?.p50, String),
    total_p95: cell(total?.p95, String),
    failure: cell(head.failure_share, (n) => n.toFixed(2)),
    retries: cell(head.retries, String),
    refreshes: cell(head.refreshes, String),
    cache: cell(head.cache_hit_ratio ?? undefined, (n) => n.toFixed(2)),
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
export const NO_ROWS = 'no turns in window';

/** One head's windowed summary. An empty window says so on the edge rather than reading as fast.
 *  The edge prints a state and not the sentence: four words on a holder edge is over the cap and
 *  staggered the field grid beside it (m1 design review B10). */
function SummaryStrip({ head }: { head: PerfSummaryHead }) {
  return (
    <Strip
      edge={head.empty ? 'grey' : 'green'}
      /* A state, not the head's name: the name is a column of this rack now (CONTRACTS.md section
         2, m1 design review B10), and at 15 characters it was being clipped to `claude-` by the
         edge's 6ch budget. */
      edgeLabel={head.empty ? S.noRows : S.hasRows}
      ariaLabel={`${S.summary} ${head.label}${head.empty ? ` ${NO_ROWS}` : ''}`}
    >
      {summaryFields(head).map((field) => (
        <StripField key={field.key} w={field.w} label={field.label} value={field.value} {...basisProp(field.basis)} />
      ))}
    </Strip>
  );
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
  capture: CaptureSlice | null;
  locked?: boolean;
  error?: string | null;
  /** The fixture's own file name when a fixture fed this board, undefined otherwise: the capture
   *  marker and the sample chrome are the same value, so they cannot disagree. */
  sample?: string | undefined;
}

export function TurnsBoard({ inflight, landed, summary, capture, locked = false, error = null, sample }: TurnsBoardProps) {
  const { active } = useViews(PAGE_ID, DEFAULT_VIEWS);
  const [openKey, setOpenKey] = useState<string | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);

  const pending = landed !== null && 'pending' in landed;
  const rows = landed !== null && !pending ? landed.landed : [];
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

  useEffect(() => {
    if (open?.kind !== 'row') return;
    void fetchCapture(open.row.head, open.row.ts);
  }, [open?.kind === 'row' ? open.row.head : null, open?.kind === 'row' ? open.row.ts : null]);

  const idleBuckets = selection.kind === 'timeline'
    ? selection.timeline.buckets.filter((bucket) => bucket.rows.length === 0).length
    : 0;

  if (locked) return <Empty text="console locked" source="management key" />;
  if (error !== null && landed === null) return <Fault message={error} />;

  return (
    <div className="myx-tn">
      <header className="myx-tn-head">
        <h2 className="myx-tn-title">{S.title}</h2>
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
            empty={{ text: 'nothing in flight', source: '/api/heads' }}
          >
            {inflight.map((turn) => (
              <InflightStrip key={`${turn.head}:${turn.label}`} turn={turn} order={INFLIGHT_FIELDS} />
            ))}
          </Bay>

          <Bay
            label={S.summary}
            {...(summary === null ? {} : { count: summary.heads.length })}
            empty={{ text: 'no summary read yet', source: '/api/perf/summary' }}
          >
            {summary?.heads.map((head) => <SummaryStrip key={head.key} head={head} />)}
          </Bay>

          <Bay
            label={S.landed}
            {...(pending ? {} : { count: rows.length, empty: { text: NO_ROWS, source: '/api/perf/turns' } })}
          >
            {pending ? (
              <Empty text="per-turn rows have no route yet" source="row V4-127" />
            ) : (
              <div className="myx-tn-scroll" ref={scrollRef}>
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

        <aside className="myx-tn-detail" aria-label={S.detail}>
          {open?.kind !== 'row' ? null : (
            <>
              <div className="myx-tn-detail-head">
                <span className="myx-tn-detail-name">{`${open.row.head} ${open.row.model}`}</span>
                <button type="button" className="myx-tn-close" onClick={() => setOpenKey(null)}>
                  {S.close}
                </button>
              </div>
              <Bay label={S.title}>
                <Waterfall row={open.row} />
              </Bay>
              <Bay label={S.detail}>
                <RequestDrawer capture={capture} />
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
      landed={fixture !== null ? { inflight: fixture.inflight, landed: fixture.landed } : turns.data}
      summary={fixture !== null ? fixture.summary : summary.data}
      capture={capture.data}
      locked={locked}
      error={turns.error}
      sample={sample?.name}
    />
  );
}
