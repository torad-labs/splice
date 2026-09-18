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
import { Bay, Empty, ErrorNote, Figure, HolderEdge, Strip, StripField } from '@shared/ui';
import type { Basis } from '@shared/ui';
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

/** A percentile the daemon did not compute prints `-`, never a zero. */
function stat(value: { p50: number; p95: number } | undefined, which: 'p50' | 'p95'): string {
  return value === undefined ? S.absent : String(value[which]);
}

function summaryFields(head: PerfSummaryHead): { key: string; label: string; value: string; basis: Basis }[] {
  const first = head.time_before_first_byte_ms;
  const total = head.total_ms;
  return [
    { key: 'window', label: S.time, value: head.window, basis: 'measured' },
    { key: 'rows', label: S.rows, value: String(head.count), basis: 'measured' },
    { key: 'first_p50', label: S.firstByte, value: stat(first, 'p50'), basis: first === undefined ? 'unavailable' : 'measured' },
    { key: 'first_p95', label: `${S.firstByte} p95`, value: stat(first, 'p95'), basis: first === undefined ? 'unavailable' : 'measured' },
    { key: 'total_p50', label: S.total, value: stat(total, 'p50'), basis: total === undefined ? 'unavailable' : 'measured' },
    { key: 'total_p95', label: `${S.total} p95`, value: stat(total, 'p95'), basis: total === undefined ? 'unavailable' : 'measured' },
    { key: 'failure', label: S.failureShare, value: head.failure_share === undefined ? S.absent : head.failure_share.toFixed(2), basis: head.failure_share === undefined ? 'unavailable' : 'measured' },
    { key: 'retries', label: S.retries, value: head.retries === undefined ? S.absent : String(head.retries), basis: head.retries === undefined ? 'unavailable' : 'measured' },
    { key: 'refreshes', label: S.refreshes, value: head.refreshes === undefined ? S.absent : String(head.refreshes), basis: head.refreshes === undefined ? 'unavailable' : 'measured' },
    { key: 'cache', label: S.cacheHit, value: head.cache_hit_ratio == null ? S.absent : head.cache_hit_ratio.toFixed(2), basis: head.cache_hit_ratio == null ? 'unavailable' : 'measured' },
    { key: 'peak', label: S.peakInflight, value: head.peak_inflight === undefined ? S.absent : String(head.peak_inflight), basis: head.peak_inflight === undefined ? 'unavailable' : 'measured' },
    { key: 'drops', label: S.ioDrops, value: head.io_drops_in_window === undefined ? S.absent : String(head.io_drops_in_window), basis: head.io_drops_in_window === undefined ? 'unavailable' : 'measured' },
  ];
}

/** The sentence an empty window prints, which is FEATURES.md 4.3's own: a window with no rows says
 *  so, and never reads as zero latency. It lives here, not in strings.ts, because an honest empty
 *  is not a label (CONTRACTS.md section 4). */
export const NO_ROWS = 'no turns in window';

/** One head's windowed summary. An empty window says so on the edge rather than reading as fast. */
function SummaryStrip({ head }: { head: PerfSummaryHead }) {
  return (
    <Strip
      edge={head.empty ? 'grey' : 'green'}
      edgeLabel={head.empty ? NO_ROWS : head.label}
      ariaLabel={`${S.summary} ${head.label}`}
    >
      {summaryFields(head).map((field) => (
        <StripField key={field.key} w={15} label={field.label} value={field.value} basis={field.basis} />
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
  sample?: boolean;
}

export function TurnsBoard({ inflight, landed, summary, capture, locked = false, error = null, sample = false }: TurnsBoardProps) {
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
  if (error !== null && landed === null) return <ErrorNote message={error} />;

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
        {sample ? <HolderEdge state="grey" label={S.sample} /> : null}
      </header>

      <div className={open === undefined ? 'myx-tn-board' : 'myx-tn-board myx-tn-board-open'}>
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
  const [fixture, setFixture] = useState<Fixture | null>(null);
  const name = fixtureName();

  useEffect(() => {
    const stops = [startHeadsPolling(2000), startPerfTurnsPolling(undefined, 5000), startPerfSummaryPolling('24h', 15000)];
    return () => stops.forEach((stop) => stop());
  }, []);

  useEffect(() => {
    if (name === null) return undefined;
    let live = true;
    void import(/* @vite-ignore */ `./fixtures/${name}.ts`)
      .then((module: { fixture?: Fixture }) => {
        if (live) setFixture(module.fixture ?? null);
      })
      .catch(() => undefined);
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
      sample={fixture !== null}
    />
  );
}
