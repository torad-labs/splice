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
import { fmtInt, fmtTokens, timeAgo } from '@shared/lib';
import { Bay, Btn, Empty, ErrorNote, Figure, HolderEdge, SkeletonRows, Strip, StripField } from '@shared/ui';
import { TokenChart, CostChart, ByteChart, ToolChart, LimitedChart, WINDOWS } from '@widgets/scope-chart';
import { dispositions } from './coverage';
import { DEFAULT_VIEWS, EMPTIES, ratesFor, sortedHeads } from './model';
import { fixtureEconomics, fixtureModels, fixtureName } from './fixtures/usage';
import { S } from './strings';
import './usage.css';

export { dispositions };

const PAGE_ID = 'usage';
const POLL_MS = 30000;

function hoursLeft(burnRate: number, ceiling: number | null, spent: number): string {
  // `unknown`, not a sentence: the ceiling field beside it already prints `none`, and a long
  // string here would be the one field on the rack that clips.
  if (ceiling === null) return 'unknown';
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
  const label = projection.fraction === null ? 'no ceiling' : `${Math.round(projection.fraction * 100)}%`;

  return (
    <Strip
      edge={edge}
      edgeLabel={label}
      selected={selected}
      onOpen={onOpen}
      ariaLabel={`${S.openHead} ${head.label}`}
    >
      <StripField w={18} label={S.heads} value={head.label} mono={false} />
      <StripField w={11} label={S.spent} value={fmtTokens(projection.spent)} />
      <StripField w={11} label={S.ceiling} value={head.ceiling_tokens === null ? 'none' : fmtTokens(head.ceiling_tokens)} />
      <StripField w={14} label={S.exhaustion} value={hoursLeft(projection.ratePerHour, head.ceiling_tokens, projection.spent)} />
      <StripField w={8} label={S.turns} value={fmtInt(totals.turns)} />
      <StripField w={11} label={S.inTokens} value={fmtTokens(totals.inTokens)} />
      <StripField w={11} label={S.outTokens} value={fmtTokens(totals.outTokens)} />
      <StripField w={14} label={S.limited} value={fmtInt(totals.rateLimited)} />
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
          <Figure value={read === null ? 'not reported' : `${Math.round(read * 100)}%`} unit="cache read" basis={read === null ? 'unavailable' : 'measured'} />
          <Figure value={per === null ? 'no turns' : fmtTokens(Math.round(per))} unit={S.perTurn} basis={per === null ? 'unavailable' : 'measured'} />
          <Figure value={amp === null ? 'no output' : amp.toFixed(1)} unit={S.amplification} basis={amp === null ? 'unavailable' : 'measured'} />
          <Figure value={delta === null ? 'no turns' : fmtInt(Math.round(delta))} unit={S.wireDelta} basis={delta === null ? 'unavailable' : 'measured'} />
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
        >
          {slotTiers(head).map((tier) => (
            <Strip
              key={tier.slot}
              edge={tier.model === null ? 'grey' : 'green'}
              edgeLabel={tier.model === null ? 'undeclared' : tier.slot}
              struck={tier.model === null}
              ariaLabel={`${S.slot} ${tier.slot}`}
            >
              <StripField w={8} label={S.slot} value={tier.slot} mono={false} />
              <StripField w={24} label={S.models} value={tier.model === null ? 'not declared' : tier.model.id} mono={false} />
              <StripField w={12} label={S.contextWindow} value={tier.model === null ? '' : fmtTokens(tier.model.context_window)} />
              <StripField w={20} label={S.sourceLabel} value={tier.model === null ? '' : tier.model.context_window_source} mono={false} />
              <StripField w={10} label={S.inputRate} value={tier.model?.rates === undefined || tier.model.rates === null ? 'no rates' : String(tier.model.rates.input)} />
              <StripField w={10} label={S.outputRate} value={tier.model?.rates === undefined || tier.model.rates === null ? 'no rates' : String(tier.model.rates.output)} />
            </Strip>
          ))}
          {head.models.filter((model) => model.slot === null).map((model) => (
            <Strip key={model.id} edge="grey" edgeLabel="no slot" ariaLabel={`${S.models} ${model.id}`}>
              <StripField w={8} label={S.slot} value="none" mono={false} />
              <StripField w={24} label={S.models} value={model.id} mono={false} />
              <StripField w={12} label={S.contextWindow} value={fmtTokens(model.context_window)} />
              <StripField w={20} label={S.sourceLabel} value={model.context_window_source} mono={false} />
              <StripField w={10} label={S.inputRate} value={model.rates === null ? 'no rates' : String(model.rates.input)} />
              <StripField w={10} label={S.outputRate} value={model.rates === null ? 'no rates' : String(model.rates.output)} />
            </Strip>
          ))}
        </Bay>
      ))}
    </>
  );
}

export function UsageBoard({ payload, catalog, now, sample = false }: {
  payload: EconomicsPayload | null;
  catalog: ModelsPayload | PendingRoute | null;
  now: number;
  sample?: boolean;
}) {
  const views = useViews(PAGE_ID, DEFAULT_VIEWS);
  const [windowIndex, setWindowIndex] = useState(1);
  const [open, setOpen] = useState<string | null>(null);

  const heads = payload === null ? [] : sortedHeads(payload.heads);
  const active = heads.find((head) => head.key === open) ?? heads[0] ?? null;

  return (
    <div className="myx-usage">
      <header className="myx-usage-head">
        <h1 className="myx-usage-title">{S.title}</h1>
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
        {sample ? <HolderEdge state="grey" label={S.sample} /> : null}
      </header>

      <div className="myx-usage-windows" role="group" aria-label={S.window}>
        {WINDOWS.map((entry, index) => (
          <Btn
            key={entry.id}
            kind={index === windowIndex ? 'primary' : 'control'}
            onClick={() => setWindowIndex(index)}
          >
            {entry.label}
          </Btn>
        ))}
      </div>

      {payload === null ? (
        <SkeletonRows rows={4} cols={5} />
      ) : views.active.id === 'by-model' ? (
        catalog === null ? (
          <SkeletonRows rows={4} cols={4} />
        ) : (
          <ModelBay catalog={catalog} empty={EMPTIES.catalogPending.text} />
        )
      ) : (
        <>
          <Bay label={S.heads} count={heads.length} empty={{ text: EMPTIES.noHeads.text, source: EMPTIES.noHeads.source }}>
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

  const fixture = fixtureName(search, import.meta.env.DEV);
  const payload = fixture === null ? economics.data : fixtureEconomics;
  const catalog = fixture === null ? models.data : fixtureModels;

  return (
    <>
      {economics.error === null ? null : <ErrorNote message={economics.error} />}
      {models.error === null ? null : <ErrorNote message={models.error} />}
      <UsageBoard
        payload={payload}
        catalog={catalog}
        now={fixture === null ? Date.now() : payload === null ? 0 : payload.generated_at}
        sample={fixture !== null}
      />
    </>
  );
}
