// Models: what each head runs for each Claude Code tier, its window, and its price.
//
// A live list (docs/design/DESIGN.md section 7) answered from GET /api/models (V4-127): one row per
// model, grouped by head or by provider, with the context window and both prices drawn as bars
// against the largest of their column across every head, so two heads compare at a glance. Each
// head's four tiers print as chips, green where a model fills the tier and grey where none does,
// which is FEATURES.md 4.8's "which tiers Claude Code will and will not get on this head" without a
// sentence. The catalog is never assembled from the topology; the topology supplies only the opened
// head's own windows (GET /api/topology, V4-128), joined on the head and provider keys.
import { useEffect, useState } from 'react';
import { useLocation } from 'react-router';
import { HeadMark, hueClass, useHues } from '@entities/control-status';
import { slotTiers, startModelsPolling, useModels } from '@entities/model';
import { startTopologyPolling, useTopology } from '@entities/topology';
import type { HeadCatalog, ModelsPayload, PendingRoute } from '@entities/model';
import { useViews, ViewTabs } from '@features/views';
import { Blank, Fault } from '@shared/controls';
import { ABSENT, fmtInt, fmtTokens, ratio } from '@shared/lib';
import { Badge, DataTable, DetailPanel, Empty, KeyValue, Meter, PageHeader, Section, Stat, StatRow } from '@shared/ui';
import type { Column, RowGroup } from '@shared/ui';
import {
  byProvider, columnMax, DEFAULT_VIEWS, entriesOf, findModel, hasRates, headWindows, rateText, tierText, tiersFilled, windowFromText,
} from './model';
import type { ColumnMax, HeadWindows, ModelEntry, OpenedModel } from './model';
import { H, S, U } from './strings';
import './models.css';

const PAGE_ID = 'models';
const POLL_MS = 30000;

/** The name this page's fixture answers to: the fixture's own FILE name (CONTRACTS.md section 4).
 *  One vocabulary for every page, so a driver's table is the fixtures directory listing. */
const FIXTURE = 'models';

/** The fixture name this page accepts, or null. The name must RESOLVE and not merely be present: a
 *  page that renders fixture bytes for a name it does not carry would set the capture marker to a
 *  fixture that does not exist, which is the stale marker by another route (law 23). Exported
 *  because a test pins exactly that. */
export function fixtureModels(name: string | null): string | null {
  return import.meta.env.DEV && name === FIXTURE ? name : null;
}

/** A head's four tiers as chips: green where a model fills the tier, grey where none does. */
function TierChips({ head }: { head: HeadCatalog }) {
  return (
    <span className="myx-md-tiers" aria-label={`${S.tiers} ${head.head}`}>
      {slotTiers(head).map((tier) => (
        <Badge key={tier.slot} tone={tier.model === null ? 'neutral' : 'ok'} quiet>{tierText(tier.slot)}</Badge>
      ))}
    </span>
  );
}

/** The figures the page leads with: how many heads and models, how many tiers a model fills, the
 *  widest window, which is the scale every window bar is drawn against, and how many models carry a
 *  rate card, which says what the rate columns would have when there are none to draw. */
function Figures({ heads, max }: { heads: readonly HeadCatalog[]; max: ColumnMax }) {
  const { filled, total } = tiersFilled(heads);
  const models = heads.reduce((sum, head) => sum + head.models.length, 0);
  const priced = heads.flatMap(entriesOf).filter((entry) => hasRates(entry.model)).length;
  return (
    <StatRow>
      <Stat label={S.heads} value={fmtInt(heads.length)} />
      <Stat label={S.models} value={fmtInt(models)} />
      <Stat
        label={S.tiersFilled}
        value={fmtInt(filled)}
        unit={`${U.of} ${fmtInt(total)}`}
        chart={<Meter value={ratio(filled, total)} tone="ok" label={S.tiersFilled} />}
      />
      <Stat label={S.widestWindow} value={max.window === 0 ? ABSENT : fmtTokens(max.window)} />
      <Stat label={S.priced} value={fmtInt(priced)} unit={`${U.of} ${fmtInt(models)}`} />
    </StatRow>
  );
}

/** The table's columns. The rate columns only when a model has a rate card: without one every cell
 *  was a dash, a third of the table (splice-lead, 2026-09-25), and the Priced figure says why. */
function columnsOf(max: ColumnMax, withHead: boolean, priced: boolean): Column<ModelEntry>[] {
  const rate = (value: number | undefined, top: number, label: string) => (
    value === undefined ? ABSENT : <Meter value={ratio(value, top)} tone="neutral" label={label} figure={rateText(value)} />
  );
  return [
    {
      key: 'model',
      label: S.model,
      width: '24%',
      mono: true,
      primary: true,
      cell: ({ model }) => (
        <span className="myx-md-name">
          {model.id}
          {model.pinned ? <Badge tone="accent" quiet>{S.pinned}</Badge> : null}
          {model.resolved ? null : <Badge tone="warn" quiet>{S.unresolved}</Badge>}
        </span>
      ),
    },
    ...(withHead ? [{ key: 'head', label: S.head, width: '14%', cell: ({ head }: ModelEntry) => <HeadMark head={head.head} /> }] : []),
    { key: 'tier', label: S.tier, width: '8%', cell: ({ model }) => (model.slot === null ? ABSENT : tierText(model.slot)) },
    {
      key: 'window',
      label: S.contextWindow,
      width: '20%',
      cell: ({ model }) => (model.context_window === null ? ABSENT : (
        <Meter value={ratio(model.context_window, max.window)} tone="neutral" label={`${S.contextWindow} ${model.id}`} figure={fmtTokens(model.context_window)} />
      )),
    },
    { key: 'from', label: S.windowFrom, width: '12%', cell: ({ model }) => windowFromText(model.context_window_source) },
    ...(priced ? [
      { key: 'input', label: S.input, cell: ({ model }: ModelEntry) => rate(hasRates(model) ? model.rates.input : undefined, max.input, `${S.input} ${model.id}`) },
      { key: 'output', label: S.output, cell: ({ model }: ModelEntry) => rate(hasRates(model) ? model.rates.output : undefined, max.output, `${S.output} ${model.id}`) },
    ] : []),
  ];
}

/** Every fact the opened model carries, and the head windows its topology declares. */
function modelFacts(entry: ModelEntry, windows: HeadWindows | null): [string, string][] {
  const { model } = entry;
  const tokens = (value: number | null | undefined) => (value === null || value === undefined ? ABSENT : fmtTokens(value));
  const usd = (value: number | undefined) => (value === undefined ? ABSENT : rateText(value));
  const rates = hasRates(model) ? model.rates : null;
  return [
    [S.head, entry.head.head],
    [S.tier, model.slot === null ? ABSENT : tierText(model.slot)],
    [S.description, model.description === '' ? ABSENT : model.description],
    [S.contextWindow, tokens(model.context_window)],
    [S.windowFrom, windowFromText(model.context_window_source)],
    [S.input, usd(rates?.input)],
    [S.cacheRead, usd(rates?.cache_read)],
    [S.cacheWrite, usd(rates?.cache_write)],
    [S.output, usd(rates?.output)],
    [S.headWindow, tokens(windows?.headWindow)],
    [S.defaultWindow, tokens(windows?.defaultWindow)],
    [S.extraWindows, windows === null ? ABSENT : fmtInt(windows.extraWindows)],
    [S.windowRules, windows === null ? ABSENT : fmtInt(windows.windowRules)],
    ...(model.resolved ? [] : [[S.reason, model.reason ?? ABSENT] as [string, string]]),
  ];
}

export function ModelsBoard({ catalog, topology = null, sample }: {
  catalog: ModelsPayload | PendingRoute | null;
  /** The parsed topology from GET /api/topology, for the opened model's head windows; null while
   *  unread, and the detail prints the absence. */
  topology?: Record<string, unknown> | null;
  /** The fixture's own file name when a fixture fed this board, undefined otherwise. */
  sample?: string | undefined;
}) {
  const { active } = useViews(PAGE_ID, DEFAULT_VIEWS);
  const hueOf = useHues();
  const [open, setOpen] = useState<OpenedModel | null>(null);

  const heads = catalog === null || 'pending' in catalog ? [] : catalog.heads;
  const found = catalog === null ? null : findModel(catalog, open);
  const opened = found === null ? null : { key: `${found.head.head}:${found.model.id}`, head: found.head, model: found.model };
  const max = columnMax(heads.flatMap(entriesOf));
  const byHead = active.id !== 'by-provider';
  const groups: RowGroup<ModelEntry>[] = byHead
    ? heads.map((head) => ({
      key: head.head,
      title: <HeadMark head={head.head} />,
      hue: hueClass(hueOf(head.head)),
      count: head.models.length,
      note: head.models.length === 0 ? <Badge tone="neutral" quiet>{S.noModels}</Badge> : <TierChips head={head} />,
      rows: entriesOf(head),
    }))
    : byProvider(heads).map((group) => ({
      key: group.provider,
      title: group.provider,
      count: group.heads.reduce((sum, head) => sum + head.models.length, 0),
      rows: group.heads.flatMap(entriesOf),
    }));

  const body = catalog === null ? <Blank strips={5} />
    : 'pending' in catalog ? <Empty text={S.catalogPending} source={H.pending} />
    : heads.length === 0 ? <Empty text={S.noHeads} source={H.noHeads} />
    : (
      <>
        <Figures heads={heads} max={max} />
        <Section title={S.models} info={{ text: H.rates, label: S.aboutRates }}>
          <div className={opened === null ? 'myx-md-board' : 'myx-md-board myx-md-board-open'}>
            <DataTable
              columns={columnsOf(max, !byHead, heads.some((head) => entriesOf(head).some((entry) => hasRates(entry.model))))}
              groups={groups}
              rowKey={(entry) => entry.key}
              label={S.models}
              onOpen={(entry) => setOpen(opened?.key === entry.key ? null : { head: entry.head.head, id: entry.model.id })}
              openLabel={(entry) => `${S.openModel} ${entry.model.id}`}
              selectedKey={opened?.key ?? null}
              rowTone={(entry) => (entry.model.resolved ? null : 'warn')}
              rowHue={(entry) => hueClass(hueOf(entry.head.head))}
            />
            {/* Unmounted at rest: no track and no empty panel until a model is opened. */}
            {opened === null ? null : (
              <DetailPanel
                title={opened.model.id}
                label={S.detail}
                status={opened.model.pinned ? <Badge tone="accent">{S.pinned}</Badge> : undefined}
                onClose={() => setOpen(null)}
                closeLabel={S.close}
              >
                <KeyValue rows={modelFacts(opened, headWindows(topology, opened.head))} />
              </DetailPanel>
            )}
          </div>
        </Section>
      </>
    );

  return (
    <div className="myx-md" {...(import.meta.env.DEV && sample !== undefined ? { 'data-sample': sample } : {})}>
      <PageHeader
        title={S.title}
        info={{ text: H.tiers, label: S.aboutTiers }}
        {...(sample === undefined ? {} : { actions: <Badge tone="neutral">{S.sample}</Badge> })}
      >
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
      </PageHeader>
      {body}
    </div>
  );
}

export default function ModelsPage() {
  const { search } = useLocation();
  const models = useModels((state) => state);
  const topology = useTopology((state) => state.data);
  const [sample, setSample] = useState<{ name: string; payload: ModelsPayload } | null>(null);
  useEffect(() => startModelsPolling(POLL_MS), []);
  useEffect(() => startTopologyPolling(POLL_MS), []);

  // The name must RESOLVE, not merely be present (law 23): a page that rendered fixture bytes for a
  // name it does not carry would set the capture marker to a fixture that does not exist.
  const name = fixtureModels(new URLSearchParams(search).get('fixture'));

  useEffect(() => {
    if (name === null) {
      // A name that is asked for and then dropped must take the marker with it.
      setSample(null);
      return undefined;
    }
    // The specifier is BUILT AT RUNTIME and the module reached by a DYNAMIC import: a static import
    // is a real dependency edge whatever the DEV branch says, so the fixture's bytes would ship
    // (measured 2026-09-18, CONTRACTS.md section 4).
    void import(/* @vite-ignore */ `./fixtures/${name}.ts`)
      .then((module: { fixtureCatalog?: ModelsPayload }) => {
        setSample(module.fixtureCatalog === undefined ? null : { name, payload: module.fixtureCatalog });
      })
      .catch(() => undefined);
  }, [name]);

  const catalog = sample === null ? models.data : sample.payload;

  return (
    <>
      {models.error === null ? null : <Fault message={models.error} lastRead={sample === null ? models.lastUpdated : null} />}
      <ModelsBoard
        catalog={catalog}
        topology={sample === null && topology !== null && 'topology' in topology ? topology.topology : null}
        sample={sample?.name}
      />
    </>
  );
}
