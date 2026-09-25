// Compaction: what the daemon's compactions did, drawn, and the instruction rules in effect.
//
// A reader, and a numbers page (docs/design/DESIGN.md section 7): the week's outcomes as one split
// bar, the tail's time and summary length as sparklines, each head's own split, the rules with their
// lengths as bars you can compare, and the recent compactions one per row. The one fact an operator
// needs about the feature (it runs on the session's own model, and the console offers no knob to
// move it) sits behind an info mark: a pin would move the reasoning off the session's model and miss
// the provider's prompt cache on the whole transcript, the most expensive request a session makes.
//
// The rules come from GET /api/compaction/instructions: scope, source label, live length and the
// heads each applies to. Never the rule's text: the route carries none.
import { useEffect, useState } from 'react';
import { useLocation } from 'react-router';
import type { CompactPayload, CompactRow } from '@shared/api';
import { startCompactPolling, startInstructionsPolling, useCompact, useInstructions } from '@entities/compact-stats';
import type { InstructionsState } from '@entities/compact-stats';
import { HeadMark, hueClass, useHues } from '@entities/control-status';
import { Blank, Fault } from '@shared/controls';
import { ABSENT, fmtInt, fmtMs, fmtShare, ratio, timeAgo } from '@shared/lib';
import {
  Badge, DataTable, DetailPanel, Empty, KeyValue, Meter, PageHeader, Section, Sparkline, StackedBar, Stat, StatRow,
} from '@shared/ui';
import type { Column } from '@shared/ui';
import { dispositions } from './coverage';
import {
  failedOf, headRows, medianOf, outcomeCounts, outcomeParts, outcomeRows, outcomeText, seriesOf, shareText, stateOf, TONE,
} from './model';
import type { HeadOutcomes } from './model';
import { H, S, U } from './strings';
import { CompactionRules } from '@widgets/compaction-rule';
import './compaction.css';

export { dispositions };

/** The name this page accepts in the hash query. Declared HERE, not in the fixture module: a
 *  static import of that module, even for one constant, is a real dependency edge, so the bundler
 *  would include the fixture and its strings would ship (CONTRACTS.md section 4). */
const FIXTURE = 'compaction';

/** Whether the address asks for THIS page's fixture, by that fixture's own file name. Exported
 *  because the capture marker rests on it (law 23): a name this page does not carry is not a
 *  fixture, so the page must end with no marker rather than a stale one. */
export function wantsFixture(search: string): boolean {
  return import.meta.env.DEV && new URLSearchParams(search).get('fixture') === FIXTURE;
}

/** The rules in effect: one row per configured rule, every head that could not be asked named with
 *  the daemon's reason, and no rule at all said as one line. */
export function RulesSection({ instructions, error = null, lastRead = null }: {
  instructions: InstructionsState | null;
  error?: string | null;
  /** When the rules on screen were read, which the fault prints as stale while `error` stands. */
  lastRead?: number | null;
}) {
  const rules = instructions?.rules ?? [];
  return (
    <Section title={S.rules} {...(instructions === null ? {} : { count: rules.length })}>
      {error === null ? null : <Fault message={error} lastRead={lastRead} />}
      {instructions?.unread.map((unread) => <Fault key={unread.head} message={`${unread.head}: ${unread.reason}`} />)}
      {instructions === null ? null : rules.length === 0 ? (
        <Empty text={S.noRules} source={H.noRules} />
      ) : (
        <CompactionRules rules={rules} headsOf={(rule) => rule.heads} label={S.rules} />
      )}
    </Section>
  );
}

function eventKey(row: CompactRow, index: number): string {
  return `${row.head}-${row.ts}-${index}`;
}

/** The opened compaction: every field the row carries, the error text included, because the point
 *  of the tail is the one that went wrong. */
function eventFacts(row: CompactRow): [string, string][] {
  return [
    [S.when, timeAgo(row.ts)],
    [S.took, row.ms === undefined ? ABSENT : fmtMs(row.ms)],
    [S.summary, row.chars === undefined ? ABSENT : `${fmtInt(row.chars)} ${U.chars}`],
    [S.instructions, row.instructions_source === 'client' ? S.clientDefault : row.instructions_source ?? ABSENT],
    [S.error, row.error ?? ABSENT],
  ];
}

/** The recent compactions, one row each, newest first; a failure takes the danger tint. */
function RecentSection({ tail }: { tail: readonly CompactRow[] }) {
  const [openKey, setOpenKey] = useState<string | null>(null);
  const rows = [...tail].sort((a, b) => b.ts - a.ts).map((row, index) => ({ row, key: eventKey(row, index) }));
  const open = rows.find((entry) => entry.key === openKey)?.row ?? null;
  const maxMs = Math.max(0, ...tail.map((row) => row.ms ?? 0));
  const maxChars = Math.max(0, ...tail.map((row) => row.chars ?? 0));
  type Entry = (typeof rows)[number];
  const columns: Column<Entry>[] = [
    { key: 'when', label: S.when, width: '12%', mono: true, primary: true, cell: ({ row }) => timeAgo(row.ts) },
    { key: 'head', label: S.head, width: '20%', cell: ({ row }) => <HeadMark head={row.head} /> },
    {
      key: 'outcome',
      label: S.outcome,
      width: '20%',
      cell: ({ row }) => <Badge tone={TONE[stateOf(row.outcome ?? 'unknown')]} quiet>{outcomeText(row.outcome ?? 'unknown')}</Badge>,
    },
    {
      key: 'summary',
      label: S.summary,
      width: '24%',
      cell: ({ row }) => (row.chars === undefined ? ABSENT : (
        <Meter value={ratio(row.chars, maxChars)} tone="neutral" label={S.summary} figure={fmtInt(row.chars)} />
      )),
    },
    {
      key: 'took',
      label: S.took,
      cell: ({ row }) => (row.ms === undefined ? ABSENT : (
        <Meter value={ratio(row.ms, maxMs)} tone="neutral" label={S.took} figure={fmtMs(row.ms)} />
      )),
    },
  ];
  return (
    <Section title={S.recent} count={tail.length}>
      {tail.length === 0 ? <Empty text={S.none} source={H.none} /> : (
        <div className={open === null ? 'myx-cp-board' : 'myx-cp-board myx-cp-board-open'}>
          <DataTable
            columns={columns}
            rows={rows}
            rowKey={(entry) => entry.key}
            label={S.recent}
            onOpen={(entry) => setOpenKey(entry.key === openKey ? null : entry.key)}
            openLabel={(entry) => `${S.open} ${timeAgo(entry.row.ts)}`}
            selectedKey={openKey}
            rowTone={({ row }) => (stateOf(row.outcome ?? 'unknown') === 'fail' ? 'danger' : null)}
          />
          {/* Unmounted at rest: no track and no empty panel until a row is opened. */}
          {open === null ? null : (
            <DetailPanel
              title={<HeadMark head={open.head} />}
              label={S.detail}
              status={<Badge tone={TONE[stateOf(open.outcome ?? 'unknown')]}>{outcomeText(open.outcome ?? 'unknown')}</Badge>}
              onClose={() => setOpenKey(null)}
              closeLabel={S.close}
            >
              <KeyValue rows={eventFacts(open)} />
            </DetailPanel>
          )}
        </div>
      )}
    </Section>
  );
}

/** The outcomes, one row each with its share drawn, and each head's own split. */
function OutcomesSection({ stats }: { stats: CompactPayload['stats'] }) {
  const hueOf = useHues();
  const { counts, total } = outcomeCounts(stats);
  const heads = headRows(stats);
  const outcomeColumns: Column<{ outcome: string; count: number }>[] = [
    {
      key: 'outcome',
      label: S.outcome,
      width: '28%',
      cell: ({ outcome }) => <Badge tone={TONE[stateOf(outcome)]} quiet>{outcomeText(outcome)}</Badge>,
    },
    {
      key: 'share',
      label: S.share,
      cell: ({ outcome, count }) => (
        <Meter value={ratio(count, total)} tone={TONE[stateOf(outcome)]} label={`${S.share} ${outcomeText(outcome)}`} figure={shareText(count, total)} />
      ),
    },
    { key: 'count', label: S.count, width: '14%', align: 'end', mono: true, cell: ({ count }) => fmtInt(count) },
  ];
  const headColumns: Column<HeadOutcomes>[] = [
    { key: 'head', label: S.head, width: '24%', cell: (row) => <HeadMark head={row.head} /> },
    {
      key: 'outcomes',
      label: S.outcomes,
      cell: (row) => <StackedBar parts={outcomeParts(row.counts)} label={`${S.outcomes} ${row.head}`} format={fmtInt} />,
    },
    { key: 'compactions', label: S.compactions, width: '14%', align: 'end', mono: true, cell: (row) => fmtInt(row.total) },
    { key: 'failed', label: S.failed, width: '12%', align: 'end', mono: true, cell: (row) => fmtInt(row.failed) },
  ];
  return (
    <>
      <Section title={S.outcomes} count={total}>
        {total === 0 ? <Empty text={S.none} source={H.none} /> : (
          <DataTable columns={outcomeColumns} rows={outcomeRows(counts)} rowKey={(row) => row.outcome} label={S.outcomes} />
        )}
      </Section>
      {heads.length === 0 ? null : (
        <Section title={S.heads} count={heads.length}>
          <DataTable
            columns={headColumns}
            rows={heads}
            rowKey={(row) => row.head}
            label={S.heads}
            rowHue={(row) => hueClass(hueOf(row.head))}
          />
        </Section>
      )}
    </>
  );
}

/** The figures the page leads with: the week's compactions and their split, the failed share, and
 *  the tail's time and summary length as a middle value over a sparkline. Without seven-day counts
 *  the first tile is the counted rows, which carry no date, so its label says so. */
function Figures({ stats }: { stats: CompactPayload['stats'] }) {
  const { counts, total, week } = outcomeCounts(stats);
  const failed = failedOf(counts);
  const ms = medianOf(stats.tail, 'ms');
  const chars = medianOf(stats.tail, 'chars');
  return (
    <StatRow>
      <Stat
        label={week ? S.week : S.counted}
        value={fmtInt(total)}
        chart={<StackedBar parts={outcomeParts(counts)} label={S.outcomes} format={fmtInt} />}
      />
      <Stat
        label={S.failed}
        value={total === 0 ? ABSENT : fmtShare(failed / total)}
        {...(failed > 0 ? { tone: 'danger' as const } : {})}
      />
      <Stat
        label={S.took}
        value={ms === null ? ABSENT : fmtMs(ms)}
        chart={<Sparkline values={seriesOf(stats.tail, 'ms')} label={S.took} format={fmtMs} />}
      />
      <Stat
        label={S.summary}
        value={chars === null ? ABSENT : fmtInt(chars)}
        {...(chars === null ? {} : { unit: U.chars })}
        chart={<Sparkline values={seriesOf(stats.tail, 'chars')} label={S.summary} format={fmtInt} />}
      />
      {/* Beside a dated week, every row the stats files still hold, undated: its own tile and its
          own split, so an old failure rate never reads as this week's. */}
      {week && stats.total > 0 ? (
        <Stat
          label={S.counted}
          value={fmtInt(stats.total)}
          chart={<StackedBar parts={outcomeParts(stats.by_outcome)} label={S.counted} format={fmtInt} />}
        />
      ) : null}
    </StatRow>
  );
}

/**
 * The board takes its payload as a prop rather than reading the store (CONTRACTS.md section 4): a
 * static render only ever sees a zustand store's initial state, so a board that read the store
 * could not be rendered from data by a test or a capture.
 */
export function CompactionBoard({ payload, instructions = null, instructionsError = null, instructionsRead = null, sample }: {
  payload: CompactPayload | null;
  /** The rules in effect; null until read, and null behind a sample (no sample rules exist). */
  instructions?: InstructionsState | null;
  instructionsError?: string | null;
  /** When the rules on screen were read. */
  instructionsRead?: number | null;
  /** The fixture's own file name when a fixture fed this board, undefined otherwise. */
  sample?: string | undefined;
}) {
  // No compaction counted and none in the tail: one line says so, instead of a page of empty tiles.
  const none = payload !== null && payload.stats.total === 0 && payload.stats.tail.length === 0;
  return (
    <div className="myx-cp" {...(import.meta.env.DEV && sample !== undefined ? { 'data-sample': sample } : {})}>
      <PageHeader
        title={S.title}
        info={{ text: H.model, label: S.aboutModel }}
        {...(sample === undefined ? {} : { actions: <Badge tone="neutral">{S.sample}</Badge> })}
      />
      {payload === null ? <Blank strips={4} /> : none ? <Empty text={S.none} source={H.none} /> : (
        <>
          <Figures stats={payload.stats} />
          <OutcomesSection stats={payload.stats} />
        </>
      )}
      {sample === undefined ? <RulesSection instructions={instructions} error={instructionsError} lastRead={instructionsRead} /> : null}
      {payload === null || none ? null : <RecentSection tail={payload.stats.tail} />}
    </div>
  );
}

export default function CompactionPage() {
  const { search } = useLocation();
  const compact = useCompact((state) => state);
  const instructions = useInstructions((state) => state);
  useEffect(() => {
    const stops = [startCompactPolling(5000), startInstructionsPolling(15000)];
    return () => stops.forEach((stop) => stop());
  }, []);

  const [sample, setSample] = useState<{ name: string; payload: CompactPayload } | null>(null);

  // The fixture loads through a DYNAMIC import inside the DEV branch, so a production build drops
  // the branch and the fixture is not a dependency of anything that ships. The specifier is built at
  // runtime: a literal `import('./fixtures/compaction')` stays a dependency edge through the
  // single-file build even when the branch around it is dead (measured 2026-09-18).
  useEffect(() => {
    if (!wantsFixture(search)) {
      // The address no longer asks for the fixture, so the marker must go (a stale marker is the
      // defect the capture marker exists to prevent).
      setSample(null);
      return;
    }
    void import(/* @vite-ignore */ `./fixtures/${FIXTURE}.ts`).then((module: { fixtureCompact?: CompactPayload }) => {
      setSample(module.fixtureCompact === undefined ? null : { name: FIXTURE, payload: module.fixtureCompact });
    }).catch(() => undefined);
  }, [search]);

  return (
    <>
      {compact.error === null ? null : <Fault message={compact.error} lastRead={sample === null ? compact.lastUpdated : null} />}
      <CompactionBoard
        payload={sample === null ? compact.data : sample.payload}
        instructions={instructions.data}
        instructionsError={instructions.error}
        instructionsRead={instructions.lastUpdated}
        sample={sample?.name}
      />
    </>
  );
}
