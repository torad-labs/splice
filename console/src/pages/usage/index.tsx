// Usage: the numbers page (docs/design/DESIGN.md section 7). Plan windows first, then the chosen
// window's totals, then the heads, then the opened head's charts drawn in its own colour.
//
// The page answers the one question the burn surface exists for — how much of the plan is gone and
// how fast it is going — and it answers it in the daemon's own numbers. Everything here is a SUM the
// daemon already computed (FEATURES.md 2.5: "Sums only; every rate is derived in the console so it
// stays recomputable per window"); the one derived number is cost, which is exactly why its inset
// says `estimated` and the rest say `measured`.
//
// The burn gauge reads TOTAL input, never uncached input. That is not a preference: measured across
// two exhaustion windows on 2026-08-01 the plan metered 1.716B total input at 97% while a burn gauge
// built on uncached tokens would have read comfortable at the moment the quota ran out
// (entities/economics/model/derive.ts carries the whole story).
import { useEffect, useState } from 'react';
import { useLocation } from 'react-router';
import { useEconomics, startEconomicsPolling, burn, hitRate, perTurn, amplification, wireDelta, sum, within } from '@entities/economics';
import type { EconomicsPayload, HeadEconomics, UsagePayload } from '@shared/api';
import { startModelsPolling, useModels, slotTiers, windowSourceText } from '@entities/model';
import type { ModelsPayload, PendingRoute } from '@entities/model';
import { useUsage } from '@entities/usage';
import { useViews, ViewTabs } from '@features/views';
// THE MOUNT M2-06 NEVER WROTE (M1-96). M2-07 shipped these two panels and its own title says the
// mounting is "a one-line orchestrator note on M2-06 if it lands first"; M2-06 landed and the note
// was never written, so two finished features sat invisible while a census counted their sheets as
// eight of seventy-three unexercised rules. Imported through each feature's PUBLIC component --
// never by reaching into its internals, because the FSD boundary walls are enforced by
// eslint-plugin-boundaries and a cross-slice import is refused. Alerts imports nothing from
// budgets and neither imports the page.
import { AlertsPanel } from '@features/alerts';
import { BudgetsPanel } from '@features/budgets';
import { HeadMark, hueClass, useHues } from '@entities/control-status';
import { cx, fmtDurationS, fmtInt, fmtShare, fmtTokens, timeAgo } from '@shared/lib';
import { Badge, DataTable, Empty, InfoTip, KeyValue, PageHeader, Ring, Section, Segmented, Sparkline, StackedBar, Stat, StatRow } from '@shared/ui';
import type { Column, RowGroup } from '@shared/ui';
import { Blank, Fault } from '@shared/controls';
import { TokenChart, CostChart, ByteChart, ToolChart, LimitedChart, WINDOWS } from '@widgets/scope-chart';
import { dispositions } from './coverage';
import { DEFAULT_VIEWS, fmtUsd, perHour, pricedCost, ratesFor, sortedHeads } from './model';
import { PlanBay } from './plan';
import { H, S, U } from './strings';
import './usage.css';

export { dispositions };

const PAGE_ID = 'usage';



const POLL_MS = 30000;

/** The trend beside each figure: the last day, hour by hour, whatever window the figures sum. */
const TREND_HOURS = 24;

/** The name this page accepts in the hash query, declared HERE and not in the fixture module: a
 *  static import of that module — even for one constant — is a dependency edge the bundler
 *  honours, so the bytes ship. This page was the leak console/tools/src/commands/leak.ts reported as
 *  BLIND: every long string in its fixture also appears in application source, so the wall had no
 *  evidence to search for, while the fixture demonstrably shipped (usage.ts FIXTURE_NOW =
 *  1_787_400_000_000, in no other file, printed as 17874e8 in dist). */
const FIXTURE = 'usage';

/** Whether the address asks for THIS page's fixture, by that fixture's own FILE name. Exported
 *  because the capture marker's whole value rests on it (law 23): a name this page does not carry
 *  is not a fixture, so the page must end with no marker rather than a stale one, and a test pins
 *  that here rather than inferring it from a rendered label. */
export function wantsFixture(search: string): boolean {
  return import.meta.env.DEV && new URLSearchParams(search).get('fixture') === FIXTURE;
}


/** The sample the fixture module hands over once it has loaded. */
interface UsageFixture {
  economics: EconomicsPayload;
  models: ModelsPayload;
}

function hoursLeft(burnRate: number, ceiling: number | null, spent: number): string {
  // The absence glyph and not a word: a head with no ceiling has no exhaustion figure either, and
  // the ceiling cell beside it already says so. `not in use` is a real reading, a ceiling with no
  // burn against it, and stays.
  if (ceiling === null) return S.absent;
  if (burnRate <= 0) return S.idle;
  const hours = Math.max(0, ceiling - spent) / burnRate;
  return fmtDurationS(hours * 3600);
}

/** The insets for one head: every chart the rollup supports, each framed with its basis. The
 *  bars take the head's own colour (DESIGN.md section 5), stepped by kind of token. */
function HeadCharts({ head, windowIndex, now, rates }: {
  head: HeadEconomics;
  windowIndex: number;
  now: number;
  rates: ReturnType<typeof ratesFor>;
}) {
  const chartWindow = WINDOWS[windowIndex];
  const totals = sum(within(head.buckets, chartWindow.hours, now));
  const per = perTurn(totals);
  const amp = amplification(totals);
  const delta = wireDelta(totals);

  return (
    <div className="myx-usage-detail-grid">
      <div className="myx-usage-charts">
        <TokenChart buckets={head.buckets} window={chartWindow} now={now} />
        <CostChart buckets={head.buckets} window={chartWindow} now={now} rates={rates} />
        <ByteChart buckets={head.buckets} window={chartWindow} now={now} />
        <ToolChart buckets={head.buckets} window={chartWindow} now={now} />
        <LimitedChart buckets={head.buckets} window={chartWindow} now={now} />
      </div>
      {/* The input split is where the plan's meter goes. The cache read part is a DIAGNOSTIC and
          its tip says so: the plan meters cached input too, so a large grey part is not safety. */}
      <Section title={S.inTokens} meta={fmtTokens(totals.inTokens)} info={{ text: H.cacheRead, label: S.cacheRead }} className="myx-usage-read">
        <StackedBar
          label={S.inTokens}
          legend
          format={fmtTokens}
          parts={[
            { key: 'fresh', label: S.fresh, value: Math.max(0, totals.inTokens - totals.cachedTokens - totals.cacheWriteTokens), mark: 'series-1' },
            { key: 'cached', label: S.cached, value: totals.cachedTokens, mark: 'series-2' },
            { key: 'write', label: S.cacheWrite, value: totals.cacheWriteTokens, mark: 'series-3' },
          ]}
        />
        <KeyValue
          rows={[
            [S.outTokens, fmtTokens(totals.outTokens)],
            [S.perTurn, per === null ? S.absent : fmtTokens(Math.round(per))],
            [S.amplification, amp === null ? S.absent : amp.toFixed(1)],
            [S.wireDelta, delta === null ? S.absent : fmtInt(Math.round(delta))],
          ]}
        />
      </Section>
    </div>
  );
}

type Tier = ReturnType<typeof slotTiers>[number];
type ModelRow = { slot: string; model: Tier['model'] };

/** The model table: what each declared model would cost, from the catalog, one run per head. */
function ModelBay({ catalog }: { catalog: ModelsPayload | PendingRoute }) {
  const hueOf = useHues();
  if ('pending' in catalog) return <Empty text={S.noCatalog} source={H.noCatalog} />;
  const columns: Column<ModelRow>[] = [
    { key: 'slot', label: S.slot, width: '10%', cell: (row) => row.slot },
    { key: 'model', label: S.models, width: '30%', primary: true, cell: (row) => row.model?.id ?? S.absent },
    { key: 'window', label: S.contextWindow, width: '14%', align: 'end', mono: true, cell: (row) => (row.model === null || row.model.context_window === null ? S.absent : fmtTokens(row.model.context_window)) },
    { key: 'source', label: S.sourceLabel, width: '22%', cell: (row) => (row.model === null ? S.absent : windowSourceText(row.model.context_window_source)) },
    { key: 'in', label: S.inputRate, width: '12%', align: 'end', mono: true, cell: (row) => (row.model?.rates === undefined || row.model.rates === null ? S.absent : String(row.model.rates.input)) },
    { key: 'out', label: S.outputRate, width: '12%', align: 'end', mono: true, cell: (row) => (row.model?.rates === undefined || row.model.rates === null ? S.absent : String(row.model.rates.output)) },
  ];
  const groups: RowGroup<ModelRow>[] = catalog.heads.map((head) => ({
    key: head.head,
    title: <HeadMark head={head.head} />,
    count: head.models.length,
    hue: hueClass(hueOf(head.head)),
    rows: [
      ...slotTiers(head).map((tier) => ({ slot: tier.slot, model: tier.model })),
      ...head.models.filter((model) => model.slot === null).map((model) => ({ slot: S.noSlot, model })),
    ],
  }));
  if (groups.length === 0) return <Empty text={S.noModels} source={H.noModels} />;
  return (
    <DataTable
      columns={columns}
      groups={groups}
      rowKey={(row) => `${row.slot}:${row.model?.id ?? ''}`}
      label={S.models}
    />
  );
}

export function UsageBoard({ payload, usage = null, usageError = null, catalog, now, sample }: {
  payload: EconomicsPayload | null;
  /** /api/usage, for the plan limits. The status strip polls it on every page. */
  usage?: UsagePayload | null;
  usageError?: string | null;
  catalog: ModelsPayload | PendingRoute | null;
  now: number;
  /** The fixture's own file name when a fixture fed this board, undefined otherwise: the capture
   *  marker and the sample chrome are the same value, so they cannot disagree. */
  sample?: string | undefined;
}) {
  const views = useViews(PAGE_ID, DEFAULT_VIEWS);
  const hueOf = useHues();
  const [windowIndex, setWindowIndex] = useState(1);
  const [open, setOpen] = useState<string | null>(null);

  const heads = payload === null ? [] : sortedHeads(payload.heads);
  const active = heads.find((head) => head.key === open) ?? heads[0] ?? null;
  const chartWindow = WINDOWS[windowIndex];

  // The chosen window's totals across every head: the numbers this page is about.
  const perHead = heads.map((head) => ({ head, totals: sum(within(head.buckets, chartWindow.hours, now)) }));
  const all = sum(heads.flatMap((head) => within(head.buckets, chartWindow.hours, now)));
  const allTokens = all.inTokens + all.outTokens;
  const read = hitRate(all);

  // Cost prices each head by its own rate card; a head with none is left out and counted, never
  // priced at zero.
  const priceOf = pricedCost(new Map(heads.map((head) => [head.key, ratesFor(catalog, head.key)])));
  const unpriced = perHead.filter(({ head, totals }) => totals.turns > 0 && ratesFor(catalog, head.key) === null).length;
  const cost = perHead.reduce((held, { head, totals }) => held + priceOf(head, totals), 0);
  const priced = perHead.some(({ head }) => ratesFor(catalog, head.key) !== null);
  const trend = (values: number[], label: string, format: (value: number) => string) => (
    <span className="myx-usage-trend">
      <Sparkline values={values} label={`${label}, ${U.lastDay}`} format={format} />
      <span className="myx-usage-trend-note" aria-hidden="true">{U.lastDay}</span>
    </span>
  );

  type HeadRow = (typeof perHead)[number];
  const columns: Column<HeadRow>[] = [
    { key: 'head', label: S.head, width: '16%', primary: true, cell: ({ head }) => <HeadMark head={head.key}>{head.label}</HeadMark> },
    {
      key: 'share',
      label: S.share,
      width: '14%',
      cell: ({ totals }) => {
        const share = allTokens === 0 ? 0 : (totals.inTokens + totals.outTokens) / allTokens;
        return (
          <span className="myx-usage-share">
            <span className="myx-usage-share-bar"><span className="myx-usage-share-fill" style={{ width: `${share * 100}%` }} /></span>
            <span className="myx-usage-share-pct">{fmtShare(share)}</span>
          </span>
        );
      },
    },
    {
      key: 'trend',
      label: S.trend,
      width: '12%',
      cell: ({ head }) => (
        <Sparkline values={perHour([head], TREND_HOURS, now, (_, totals) => totals.turns)} label={`${head.label} ${S.trend}, ${U.lastDay}`} mark="hue" />
      ),
    },
    { key: 'turns', label: S.turns, width: '7%', align: 'end', mono: true, cell: ({ totals }) => fmtInt(totals.turns) },
    { key: 'in', label: S.inTokens, width: '9%', align: 'end', mono: true, cell: ({ totals }) => fmtTokens(totals.inTokens) },
    { key: 'out', label: S.outTokens, width: '9%', align: 'end', mono: true, cell: ({ totals }) => fmtTokens(totals.outTokens) },
    { key: 'spent', label: S.spent, width: '9%', align: 'end', mono: true, cell: ({ head }) => fmtTokens(burn(head, now).spent) },
    { key: 'limit', label: S.ceiling, width: '8%', align: 'end', mono: true, cell: ({ head }) => (head.ceiling_tokens === null ? S.absent : fmtTokens(head.ceiling_tokens)) },
    {
      key: 'runs-out',
      label: S.exhaustion,
      width: '9%',
      align: 'end',
      mono: true,
      cell: ({ head }) => {
        const projection = burn(head, now);
        return hoursLeft(projection.ratePerHour, head.ceiling_tokens, projection.spent);
      },
    },
    { key: 'limited', label: S.limited, width: '7%', align: 'end', mono: true, cell: ({ totals }) => fmtInt(totals.rateLimited) },
  ];

  return (
    <div
      className="myx-usage"
      {...(import.meta.env.DEV && sample !== undefined ? { 'data-sample': sample } : {})}
    >
      <PageHeader
        title={S.title}
        info={{ text: H.about, label: S.about }}
        actions={(
          <>
            {sample === undefined ? null : <Badge tone="neutral">{S.sample}</Badge>}
            <Segmented
              label={S.window}
              options={WINDOWS.map((entry, index) => ({ value: String(index), label: entry.label }))}
              value={String(windowIndex)}
              onChange={(next) => setWindowIndex(Number(next))}
            />
          </>
        )}
      >
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
      </PageHeader>

      {payload === null ? (
        <Blank strips={4} />
      ) : views.active.id === 'by-model' ? (
        catalog === null ? (
          <Blank strips={4} />
        ) : (
          <ModelBay catalog={catalog} />
        )
      ) : (
        <>
          {/* No plan limits behind a sample: the fixture carries no /api/usage, and a section fed
              nothing is a skeleton that never resolves. */}
          {sample !== undefined ? null : <PlanBay usage={usage} error={usageError} now={now} />}

          <Section title={S.totals} meta={chartWindow.label}>
            <StatRow>
              <Stat
                label={S.tokens}
                value={fmtTokens(allTokens)}
                figure={trend(perHour(heads, TREND_HOURS, now, (_, totals) => totals.inTokens + totals.outTokens), S.tokens, fmtTokens)}
                chart={(
                  <StackedBar
                    label={S.tokens}
                    legend
                    format={fmtTokens}
                    parts={[
                      { key: 'in', label: S.inTokens, value: all.inTokens, mark: 'series-1' },
                      { key: 'out', label: S.outTokens, value: all.outTokens, mark: 'series-3' },
                    ]}
                  />
                )}
              />
              <Stat
                label={S.turns}
                value={fmtInt(all.turns)}
                figure={trend(perHour(heads, TREND_HOURS, now, (_, totals) => totals.turns), S.turns, fmtInt)}
              />
              <Stat
                label={S.cost}
                value={priced ? fmtUsd(cost) : S.absent}
                figure={trend(perHour(heads, TREND_HOURS, now, priceOf), S.cost, fmtUsd)}
                {...(unpriced === 0 ? {} : { sub: <>{`${unpriced} ${U.unpriced}`}<InfoTip text={H.unpriced} label={S.unpricedWhy} /></> })}
              />
              <Stat
                label={S.cacheRead}
                value={read === null ? S.absent : `${Math.round(read * 100)}%`}
                {...(read === null ? {} : { figure: <Ring value={read} label={S.cacheRead}>{''}</Ring> })}
                sub={<>{`${fmtTokens(all.cachedTokens)} ${U.cached}`}<InfoTip text={H.cacheRead} label={S.cacheRead} /></>}
              />
              <Stat label={S.limited} value={fmtInt(all.rateLimited)} {...(all.rateLimited > 0 ? { tone: 'warn' as const } : {})} sub={U.turns} />
            </StatRow>
          </Section>

          <Section title={S.heads} count={heads.length}>
            {heads.length === 0 ? (
              <Empty text={S.noUsage} source={H.noUsage} />
            ) : (
              <DataTable
                columns={columns}
                rows={perHead}
                rowKey={({ head }) => head.key}
                label={S.heads}
                onOpen={({ head }) => setOpen(head.key)}
                openLabel={({ head }) => `${S.openHead} ${head.label}`}
                selectedKey={active?.key ?? null}
                rowHue={({ head }) => hueClass(hueOf(head.key))}
              />
            )}
          </Section>

          {active === null ? null : (
            <Section
              title={<HeadMark head={active.key}>{active.label}</HeadMark>}
              meta={S.detail}
              actions={<span className="myx-usage-note">{`${S.updated} ${timeAgo(payload.generated_at, now)}`}</span>}
              className={cx('myx-usage-detail', hueClass(hueOf(active.key)))}
            >
              <HeadCharts head={active} windowIndex={windowIndex} now={now} rates={ratesFor(catalog, active.key)} />
            </Section>
          )}

          {/* Budgets are per head and alerts fleet-wide, so both draw whatever head is open: a panel
              that only drew with a head selected would be invisible in the state most people arrive
              in. Both are the feature's PUBLIC component; the FSD walls refuse anything deeper. */}
          <div className="myx-usage-panels">
            <BudgetsPanel heads={heads.map((head) => head.key)} />
            <AlertsPanel />
          </div>
        </>
      )}
    </div>
  );
}

export default function UsagePage() {
  const { search } = useLocation();
  const economics = useEconomics((state) => state);
  const models = useModels((state) => state);
  const usage = useUsage((state) => state);

  useEffect(() => startEconomicsPolling(POLL_MS), []);
  useEffect(() => startModelsPolling(POLL_MS), []);

  const [sample, setSample] = useState<{ name: string; payload: UsageFixture } | null>(null);
  const fixture = sample === null ? null : sample.payload;

  // The fixture loads through a DYNAMIC import inside the DEV branch, so it is a build-time
  // nothing: `import.meta.env.DEV` is statically false in a production build, the branch is
  // dropped, and the fixture is not a dependency of anything that ships. The board renders the
  // store's payload while the module loads and swaps in the sample when it arrives.
  useEffect(() => {
    if (!wantsFixture(search)) {
    // The address no longer asks for this page's fixture, so the marker must GO: a name that is
    // asked for and then dropped is exactly the stale marker this row exists to prevent (measured
    // in a browser on 2026-09-18 - five pages kept one across a hash change, because the early
    // return left the previous state in place; a static render cannot see an effect, so the suite
    // was green while it happened).
      setSample(null);
      return;
    }
    // The specifier is BUILT AT RUNTIME, not written as a literal: a statically analyzable
    // `import('./fixtures/x')` stays a dependency edge through the single-file build even when the
    // branch around it is dead, so the module's bytes are inlined into dist/index.html (measured
    // 2026-09-18: this page shipped its own literals that way; the pages that compose the specifier
    // at runtime shipped none). CONTRACTS.md section 4 asks for the dynamic import; this is the half
    // of it the bundler can actually drop.
    void import(/* @vite-ignore */ `./fixtures/${FIXTURE}.ts`).then((module: {
      fixtureEconomics?: EconomicsPayload;
      fixtureModels?: ModelsPayload;
    }) => {
      if (module.fixtureEconomics === undefined || module.fixtureModels === undefined) {
        setSample(null);
        return;
      }
      setSample({ name: FIXTURE, payload: { economics: module.fixtureEconomics, models: module.fixtureModels } });
    }).catch(() => undefined);
  }, [search]);

  const payload = fixture === null ? economics.data : fixture.economics;
  const catalog = fixture === null ? models.data : fixture.models;

  return (
    <>
      {economics.error === null ? null : <Fault message={economics.error} lastRead={fixture === null ? economics.lastUpdated : null} />}
      {models.error === null ? null : <Fault message={models.error} lastRead={fixture === null ? models.lastUpdated : null} />}
      {usage.error === null || fixture !== null ? null : <Fault message={usage.error} lastRead={usage.lastUpdated} />}
      <UsageBoard
        payload={payload}
        usage={fixture === null ? usage.data : null}
        usageError={fixture === null ? usage.error : null}
        catalog={catalog}
        now={fixture === null ? Date.now() : payload === null ? 0 : payload.generated_at}
        sample={sample?.name}
      />
    </>
  );
}
