// Usage: the economics rollup drawn as scope insets, with the racks beneath it.
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
import type { EconomicsPayload, HeadEconomics } from '@shared/api';
import { startModelsPolling, useModels, slotTiers } from '@entities/model';
import type { ModelsPayload, PendingRoute } from '@entities/model';
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
import { cx, fmtInt, fmtTokens, timeAgo } from '@shared/lib';
import { Bay, Empty, Figure, HolderEdge, Strip, StripField } from '@shared/ui';
import { Blank, Fault } from '@shared/controls';
import { TokenChart, CostChart, ByteChart, ToolChart, LimitedChart, WINDOWS } from '@widgets/scope-chart';
import { dispositions } from './coverage';
import { DEFAULT_VIEWS, EMPTIES, ratesFor, sortedHeads } from './model';
import { S } from './strings';
import './usage.css';

export { dispositions };

const PAGE_ID = 'usage';



const POLL_MS = 30000;

/** The name this page accepts in the hash query, declared HERE and not in the fixture module: a
 *  static import of that module — even for one constant — is a dependency edge the bundler
 *  honours, so the bytes ship. This page was the leak .dev/web-console/fixture-leak.mjs reported as
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

/** The column widths, in ch, named once so a bay's name row and the cells under it cannot drift
 *  apart. They were inline on the strips before M2-32; a fields row repeating the numbers by hand
 *  is two lists checking each other rather than a check. */
const HEAD_COLS = [18, 11, 11, 14, 9, 13, 13, 13] as const;
const MODEL_COLS = [8, 24, 12, 20, 10, 10] as const;

/** A rack's column names, once, at the same ch widths as the cells they name — the `fields` row
 *  Bay has shipped since m1 and doctor already uses (m1 design review B9).
 *
 *  THE GROWTH IS THE HALF THAT IS EASY TO MISS: `strip-field.tsx` sets flexGrow to the field's OWN
 *  ch, so the cells share their rack's slack in proportion to their declared widths (M1-73) and a
 *  name fixed at `w ch` drifts off the column under it. Measured on this page: the heads rack
 *  renders its 18ch first column as 218.6px, a 1.87x fill, so a fixed name row would sit almost a
 *  hundred pixels left of the cell it names by the last column. The name takes the same growth. */
function ColumnNames({ columns }: { columns: readonly { w: number; label: string }[] }) {
  return (
    <>
      {columns.map((column) => (
        <span key={column.label} className="myx-usage-col" style={{ width: `${column.w}ch`, flexGrow: column.w }}>
          {column.label}
        </span>
      ))}
    </>
  );
}

function hoursLeft(burnRate: number, ceiling: number | null, spent: number): string {
  // The absence glyph and not a word: a head with no ceiling has no exhaustion figure either, and
  // the ceiling cell beside it already says so (m1 design review B8). `idle` is a real reading —
  // a ceiling with no burn against it — and stays.
  if (ceiling === null) return S.absent;
  if (burnRate <= 0) return 'idle';
  const hours = Math.max(0, ceiling - spent) / burnRate;
  return `${hours.toFixed(1)} h`;
}

/** The rack's strip: what this head has spent and what is left, in one printed row. */
function HeadStrip({ head, now, selected, onOpen }: {
  head: HeadEconomics;
  now: number;
  selected: boolean;
  onOpen: () => void;
}) {
  const totals = sum(within(head.buckets, 168, now));
  const projection = burn(head, now);
  const edge = projection.fraction === null ? 'grey' : projection.fraction >= 1 ? 'red' : projection.fraction >= 0.8 ? 'amber' : 'green';
  const label = projection.fraction === null ? 'no cap' : `${Math.round(projection.fraction * 100)}%`;

  return (
    <Strip
      edge={edge}
      edgeLabel={label}
      selected={selected}
      onOpen={onOpen}
      ariaLabel={`${S.openHead} ${head.label}`}
    >
      {/* NO PER-CELL LABELS: the bay prints its eight column names once, above the rack (B9).
          Measured here before the change: every head strip stood 63.8px tall and 42px of that was
          the values -- 21.8px of every row, a third of it, spent reprinting the eight words the
          rack states once. */}
      <StripField w={HEAD_COLS[0]} value={head.label} mono={false} />
      <StripField w={HEAD_COLS[1]} value={fmtTokens(projection.spent)} />
      <StripField w={HEAD_COLS[2]} value={head.ceiling_tokens === null ? S.absent : fmtTokens(head.ceiling_tokens)} />
      <StripField w={HEAD_COLS[3]} value={hoursLeft(projection.ratePerHour, head.ceiling_tokens, projection.spent)} />
      <StripField w={HEAD_COLS[4]} value={fmtInt(totals.turns)} />
      <StripField w={HEAD_COLS[5]} value={fmtTokens(totals.inTokens)} />
      <StripField w={HEAD_COLS[6]} value={fmtTokens(totals.outTokens)} />
      <StripField w={HEAD_COLS[7]} value={fmtInt(totals.rateLimited)} />
    </Strip>
  );
}

/** The insets for one head: every chart the rollup supports, each framed with its basis. */
function HeadCharts({ head, windowIndex, now, rates }: {
  head: HeadEconomics;
  windowIndex: number;
  now: number;
  rates: ReturnType<typeof ratesFor>;
}) {
  const chartWindow = WINDOWS[windowIndex];
  const totals = sum(within(head.buckets, chartWindow.hours, now));
  const read = hitRate(totals);
  const per = perTurn(totals);
  const amp = amplification(totals);
  const delta = wireDelta(totals);

  return (
    <div className="myx-usage-charts">
      <TokenChart buckets={head.buckets} window={chartWindow} now={now} />
      <CostChart buckets={head.buckets} window={chartWindow} now={now} rates={rates} />
      <ByteChart buckets={head.buckets} window={chartWindow} now={now} />
      <ToolChart buckets={head.buckets} window={chartWindow} now={now} />
      <LimitedChart buckets={head.buckets} window={chartWindow} now={now} />

      <section className="myx-usage-read">
        <h3 className="myx-usage-sub">{`${head.label} ${S.tokens}`}</h3>
        <p className="myx-usage-figures">
          <Figure value={fmtTokens(totals.inTokens)} unit="in" basis="measured" />
          <Figure value={fmtTokens(totals.cachedTokens)} unit="cached" basis="measured" />
          <Figure value={fmtTokens(totals.cacheWriteTokens)} unit="written" basis="measured" />
          <Figure value={fmtTokens(totals.outTokens)} unit="out" basis="measured" />
        </p>
        <p className="myx-usage-figures">
          {/* The cache hit rate is a DIAGNOSTIC and the page says so: a 90%-cached prompt bills in
              full, so a high number here is not safety and must never be read as one. */}
          {/* not-an-absence: type-member - `unavailable` here is a BASIS, printed beside the figure
              it qualifies to say what kind of figure it is (shared/ui/types.ts). The census counts
              the quoted declaration; a reader counts a word. */}
          <Figure value={read === null ? S.absent : `${Math.round(read * 100)}%`} unit="cache read" basis={read === null ? 'unavailable' : 'measured'} />
          <Figure value={per === null ? S.absent : fmtTokens(Math.round(per))} unit={S.perTurn} basis={per === null ? 'unavailable' : 'measured'} />
          <Figure value={amp === null ? S.absent : amp.toFixed(1)} unit={S.amplification} basis={amp === null ? 'unavailable' : 'measured'} />
          <Figure value={delta === null ? S.absent : fmtInt(Math.round(delta))} unit={S.wireDelta} basis={delta === null ? 'unavailable' : 'measured'} />
        </p>
      </section>
    </div>
  );
}

/** The model rack: what each declared model would cost, from the catalog. */
function ModelBay({ catalog, empty }: { catalog: ModelsPayload | PendingRoute; empty: string }) {
  if ('pending' in catalog) return <Empty text={empty} source={EMPTIES.catalogPending.source} />;
  return (
    <>
      {catalog.heads.map((head) => (
        <Bay
          key={head.key}
          label={head.key}
          count={head.models.length}
          empty={{ text: EMPTIES.noModels.text, source: EMPTIES.noModels.source }}
          fields={(
            <ColumnNames
              columns={[
                { w: MODEL_COLS[0], label: S.slot }, { w: MODEL_COLS[1], label: S.models },
                { w: MODEL_COLS[2], label: S.contextWindow }, { w: MODEL_COLS[3], label: S.sourceLabel },
                { w: MODEL_COLS[4], label: S.inputRate }, { w: MODEL_COLS[5], label: S.outputRate },
              ]}
            />
          )}
        >
          {slotTiers(head).map((tier) => (
            <Strip
              key={tier.slot}
              edge={tier.model === null ? 'grey' : 'green'}
              edgeLabel={tier.model === null ? S.undeclared : S.slotted}
              struck={tier.model === null}
              ariaLabel={`${S.slot} ${tier.slot}`}
            >
              {/* NO PER-CELL LABELS: the bay prints its six column names once (B9). */}
              <StripField w={MODEL_COLS[0]} value={tier.slot} mono={false} />
              <StripField w={MODEL_COLS[1]} value={tier.model === null ? S.absent : tier.model.id} mono={false} />
              <StripField w={MODEL_COLS[2]} value={tier.model === null ? S.absent : fmtTokens(tier.model.context_window)} />
              <StripField w={MODEL_COLS[3]} value={tier.model === null ? S.absent : tier.model.context_window_source} mono={false} />
              <StripField w={MODEL_COLS[4]} value={tier.model?.rates === undefined || tier.model.rates === null ? S.absent : String(tier.model.rates.input)} />
              <StripField w={MODEL_COLS[5]} value={tier.model?.rates === undefined || tier.model.rates === null ? S.absent : String(tier.model.rates.output)} />
            </Strip>
          ))}
          {head.models.filter((model) => model.slot === null).map((model) => (
            <Strip key={model.id} edge="grey" edgeLabel={S.noSlot} ariaLabel={`${S.models} ${model.id}`}>
              <StripField w={MODEL_COLS[0]} value={S.noSlot} mono={false} />
              <StripField w={MODEL_COLS[1]} value={model.id} mono={false} />
              <StripField w={MODEL_COLS[2]} value={fmtTokens(model.context_window)} />
              <StripField w={MODEL_COLS[3]} value={model.context_window_source} mono={false} />
              <StripField w={MODEL_COLS[4]} value={model.rates === undefined || model.rates === null ? S.absent : String(model.rates.input)} />
              <StripField w={MODEL_COLS[5]} value={model.rates === undefined || model.rates === null ? S.absent : String(model.rates.output)} />
            </Strip>
          ))}
        </Bay>
      ))}
    </>
  );
}

export function UsageBoard({ payload, catalog, now, sample }: {
  payload: EconomicsPayload | null;
  catalog: ModelsPayload | PendingRoute | null;
  now: number;
  /** The fixture's own file name when a fixture fed this board, undefined otherwise: the capture
   *  marker and the sample chrome are the same value, so they cannot disagree. */
  sample?: string | undefined;
}) {
  const views = useViews(PAGE_ID, DEFAULT_VIEWS);
  const [windowIndex, setWindowIndex] = useState(1);
  const [open, setOpen] = useState<string | null>(null);

  const heads = payload === null ? [] : sortedHeads(payload.heads);
  const active = heads.find((head) => head.key === open) ?? heads[0] ?? null;

  return (
    <div
      className="myx-usage"
      {...(import.meta.env.DEV && sample !== undefined ? { 'data-sample': sample } : {})}
    >
      <header className="myx-usage-head">
        <h1 className="myx-usage-title">{S.title}</h1>
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
        {sample === undefined ? null : <HolderEdge state="grey" label={S.sample} />}
      </header>

      {/* A row of selectable things is the rail's idiom, not a Key: the state rides a HolderEdge
          that prints its own word, green when this is the window on screen and grey when it is not.
          An `armed` Key would say "about to fire", which is not what a selected tab means. */}
      <div className="myx-usage-windows" role="group" aria-label={S.window}>
        {WINDOWS.map((entry, index) => (
          <button
            key={entry.id}
            type="button"
            className={cx('myx-usage-window', index === windowIndex && 'myx-usage-window-active')}
            aria-pressed={index === windowIndex}
            onClick={() => setWindowIndex(index)}
          >
            <HolderEdge state={index === windowIndex ? 'green' : 'grey'} label={entry.label} />
          </button>
        ))}
      </div>

      {payload === null ? (
        <Blank strips={4} />
      ) : views.active.id === 'by-model' ? (
        catalog === null ? (
          <Blank strips={4} />
        ) : (
          <ModelBay catalog={catalog} empty={EMPTIES.catalogPending.text} />
        )
      ) : (
        <>
          <Bay
            label={S.heads}
            count={heads.length}
            empty={{ text: EMPTIES.noHeads.text, source: EMPTIES.noHeads.source }}
            fields={(
              <ColumnNames
                columns={[
                  { w: HEAD_COLS[0], label: S.heads }, { w: HEAD_COLS[1], label: S.spent },
                  { w: HEAD_COLS[2], label: S.ceiling }, { w: HEAD_COLS[3], label: S.exhaustion },
                  { w: HEAD_COLS[4], label: S.turns }, { w: HEAD_COLS[5], label: S.inTokens },
                  { w: HEAD_COLS[6], label: S.outTokens }, { w: HEAD_COLS[7], label: S.limited },
                ]}
              />
            )}
          >
            {heads.map((head) => (
              <HeadStrip
                key={head.key}
                head={head}
                now={now}
                selected={active !== null && active.key === head.key}
                onOpen={() => setOpen(head.key)}
              />
            ))}
          </Bay>

          {active === null ? (
            <Empty text={EMPTIES.noHeads.text} source={EMPTIES.noHeads.source} />
          ) : (
            <div className="myx-usage-detail">
              <div className="myx-usage-row">
                <span className="myx-usage-sub">{active.label}</span>
                <span className="myx-usage-note">{`rollup ${timeAgo(payload.generated_at, now)}`}</span>
              </div>
              <HeadCharts head={active} windowIndex={windowIndex} now={now} rates={ratesFor(catalog, active.key)} />
            </div>
          )}

          {/* THE MOUNT M2-06 NEVER WROTE (M1-96). Rendered OUTSIDE the active-head branch on
              purpose: budgets and alerts are per-head and fleet-wide respectively, and a panel
              that only draws when a head happens to be selected is a panel that is invisible in
              the state most people arrive in. Both are the feature's PUBLIC component -- the FSD
              boundary walls are enforced by eslint-plugin-boundaries and reaching into a slice's
              internals is refused, which is the correct wall and not an obstacle to route around.
              Mounted, not proven: M1-96 asks for a capture showing both DRAW, because a component
              mounted into a slot that never displays is the same nothing with an import in front
              of it. */}
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
      {economics.error === null ? null : <Fault message={economics.error} />}
      {models.error === null ? null : <Fault message={models.error} />}
      <UsageBoard
        payload={payload}
        catalog={catalog}
        now={fixture === null ? Date.now() : payload === null ? 0 : payload.generated_at}
        sample={sample?.name}
      />
    </>
  );
}
