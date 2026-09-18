// The fleet page: one rack of head strips. ARRIVE is "which head needs me", so the holder edge
// carries one printed cause and the `attention first` view puts the worst head at the top.
//
// Three of this page's fields come from routes that are still rows (the model catalog, the topology
// file, the account pool). They read `not built` in the field and the page prints one honest empty
// naming the rows, rather than inventing a value for a source nobody can read yet.
import { useEffect, useState } from 'react';
import { headAttention } from '@entities/heads';
import { restartHead, startHead, startHeadsPolling, stopHead, useHeads } from '@entities/heads';
import type { HeadSignals } from '@entities/heads';
import { startAuthPolling, useAuth } from '@entities/auth';
import { dispositionText, fetchConfig, fetchTopologyStale, knobDispositions, useConfig } from '@entities/config';
import type { KnobDisposition } from '@entities/config';
import { startModelsPolling, useModels } from '@entities/model';
import { startTopologyPolling, useTopology } from '@entities/topology';
import { headWindow, startUsagePolling, useUsage } from '@entities/usage';
import { useViews, ViewTabs } from '@features/views';
import type { View } from '@features/views';
import { poll } from '@shared/lib';
import type { HeadStatus } from '@shared/api';
import { Bay, Empty, ErrorNote, FieldBox, HolderEdge, SkeletonRows } from '@shared/ui';
import { HeadStrip, HEAD_COLUMNS } from '@widgets/head-strip';
import { EMPTIES, arrangeHeads, columnsOf, dialectOf } from './model';
import { dispositions } from './coverage';
import { S } from './strings';
import './fleet.css';

export { dispositions };

const PAGE_ID = 'fleet';
const HEADS_MS = 2000;
const USAGE_MS = 5000;
const SLOW_MS = 30000;

/** The three views this page ships with. `by head` is first because it is the default. */
export const DEFAULT_VIEWS: readonly View[] = [
  { id: 'by-head', name: S.byHead, layout: 'bay', filter: {}, sort: null, group: null, fields: [] },
  { id: 'by-provider', name: S.byProvider, layout: 'bay', filter: {}, sort: null, group: 'provider', fields: [] },
  { id: 'attention', name: S.attentionFirst, layout: 'bay', filter: {}, sort: { field: 'attention', dir: 'desc' }, group: null, fields: [] },
];

const NOT_BUILT = 'not built';

/** One knob row inside the detail column: the value, its provenance layer, and whether saving it
 *  needs a restart. The provenance comes from the daemon's own layer map, never a guess. */
function KnobRow({ knob }: { knob: KnobDisposition }) {
  return (
    <FieldBox
      label={knob.key}
      value={knob.value === null ? '' : String(knob.value)}
      provenance={knob.provenance}
      hot={knob.hot}
    />
  );
}

/** The lifecycle controls. Raw buttons for the same reason the accounts feature uses them: the old
 *  Btn/ConfirmBtn exports are what M2 exists to retire (CONTRACTS.md section 2). Stop and restart
 *  interrupt live sessions, so both arm in place before they fire. */
function Lifecycle({ head }: { head: HeadStatus }) {
  const [busy, setBusy] = useState<string | null>(null);
  const [armed, setArmed] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const run = (name: string, work: (key: string) => Promise<unknown>) => {
    setBusy(name);
    setError(null);
    setArmed(null);
    work(head.key).then(
      () => setNote(`${name} sent`),
      (err: unknown) => setError(err instanceof Error ? err.message : String(err)),
    ).finally(() => setBusy(null));
  };

  const live = head.gate?.live.length ?? 0;

  return (
    <div className="myx-fleet-life">
      {!head.running ? (
        <button type="button" className="myx-fleet-btn" disabled={busy !== null} onClick={() => run(S.start, startHead)}>
          {S.start}
        </button>
      ) : armed === null ? (
        <>
          <button type="button" className="myx-fleet-btn" disabled={busy !== null} onClick={() => setArmed(S.restart)}>
            {S.restart}
          </button>
          <button type="button" className="myx-fleet-btn" disabled={busy !== null} onClick={() => setArmed(S.stop)}>
            {S.stop}
          </button>
        </>
      ) : (
        <>
          {/* The confirmation names what it interrupts. A stop with live turns is not the same
              action as a stop without, and the operator decides on that number. */}
          <span className="myx-fleet-warn" role="alert">
            {`${armed}, ${live} live`}
          </span>
          <button
            type="button"
            className="myx-fleet-btn myx-fleet-btn-armed"
            disabled={busy !== null}
            onClick={() => run(armed, armed === S.stop ? stopHead : restartHead)}
          >
            {`${armed} now`}
          </button>
          <button type="button" className="myx-fleet-btn" onClick={() => setArmed(null)}>{'cancel'}</button>
        </>
      )}
      {error === null ? null : <ErrorNote message={error} />}
      {note === null ? null : <p className="myx-fleet-note" role="status">{note}</p>}
    </div>
  );
}

export function FleetPage() {
  const views = useViews(PAGE_ID, DEFAULT_VIEWS);
  const active = views.active;
  const headsResource = useHeads((state) => state);
  const usageResource = useUsage((state) => state);
  const authResource = useAuth((state) => state);
  const configResource = useConfig((state) => state);
  const topologyResource = useTopology((state) => state);
  const modelsResource = useModels((state) => state);

  useEffect(() => {
    const stops = [
      startHeadsPolling(HEADS_MS),
      startUsagePolling(USAGE_MS),
      startAuthPolling(SLOW_MS),
      startTopologyPolling(SLOW_MS),
      startModelsPolling(SLOW_MS),
    ];
    return () => stops.forEach((stop) => stop());
  }, []);

  const [openKey, setOpenKey] = useState<string | null>(null);
  const toggle = (key: string) => setOpenKey((current) => (current === key ? null : key));

  const heads: readonly HeadStatus[] = headsResource.data ?? [];
  const auth = authResource.data;
  const topology = topologyResource.data;
  const topologyTable = topology !== null && 'topology' in topology ? topology.topology : null;
  const catalogs = modelsResource.data !== null && 'heads' in modelsResource.data
    ? modelsResource.data.heads
    : null;

  // /health's flag is the one part of the topology contract that EXISTS today, so it is read from
  // the config entity's own pass-through rather than from the pending /api/topology route.
  const [topologyStale, setTopologyStale] = useState(false);
  useEffect(() => poll(() => {
    void fetchTopologyStale().then(setTopologyStale, () => undefined);
  }, SLOW_MS), []);

  // The knobs are read for the head that is open, and only then: a per-head config view is a
  // different payload per head, and polling every head would be eight requests for one panel.
  useEffect(() => {
    if (openKey === null) return;
    return poll(() => { void fetchConfig(openKey); }, SLOW_MS);
  }, [openKey]);

  const signalsFor = (head: HeadStatus): HeadSignals => ({
    credentialPresent: auth?.[head.key]?.present ?? null,
    refreshLatched: auth?.[head.key]?.refresh_latched ?? null,
    // The pool route is V4-132, so nothing can report an excluded account yet. Left false rather
    // than guessed: a head must not read as excluded on a route nobody has read.
    accountExcluded: false,
    topologyStale,
  });

  const groups = arrangeHeads(heads, active, signalsFor);
  const columns = columnsOf(active, HEAD_COLUMNS);
  const opened = heads.find((head) => head.key === openKey) ?? null;
  const overrides = opened === null || configResource.data === null
    ? []
    : knobDispositions(configResource.data, opened.key).filter((knob) => knob.provenance === 'head override');

  return (
    <div className="myx-fleet">
      <header className="myx-fleet-head">
        <h1 className="myx-fleet-title">{S.title}</h1>
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
      </header>

      {headsResource.error === null ? null : <ErrorNote message={headsResource.error} />}
      {headsResource.data === null ? <SkeletonRows rows={4} cols={6} /> : null}

      <div className="myx-fleet-body">
        <div className="myx-fleet-bays">
          {headsResource.data !== null && heads.length === 0 ? (
            <Empty text={EMPTIES.noHeads.text} source={EMPTIES.noHeads.source} />
          ) : (
            groups.map((group) => (
              <Bay
                key={group.key === '' ? S.bay : group.key}
                label={group.key === '' ? S.bay : group.key}
                count={group.heads.length}
              >
                {group.heads.map((head) => (
                  <HeadStrip
                    key={head.key}
                    head={head}
                    attention={headAttention(head, signalsFor(head))}
                    window={headWindow(usageResource.data, head.key)}
                    account={auth?.[head.key]?.account_id_masked ?? auth?.[head.key]?.login ?? null}
                    dialect={dialectOf(topologyTable, head.key)}
                    model={catalogs?.find((entry) => entry.key === head.key)?.pinned_model ?? null}
                    columns={columns}
                    selected={openKey === head.key}
                    onOpen={() => toggle(head.key)}
                  />
                ))}
              </Bay>
            ))
          )}

          {/* The two field sources that are still rows. Named here rather than left as a bare
              `not built` in nine strips, so the operator learns which work item brings them. */}
          {topologyTable === null || catalogs === null ? (
            <Empty text={EMPTIES.fields.text} source={EMPTIES.fields.source} />
          ) : null}
        </div>

        <aside className="myx-fleet-detail" aria-label={S.detail}>
          {opened === null ? (
            <Empty text={EMPTIES.noOpened.text} source={EMPTIES.noOpened.source} />
          ) : (
            <>
              <div className="myx-fleet-detail-head">
                <HolderEdge state={headAttention(opened, signalsFor(opened)).edge} label={headAttention(opened, signalsFor(opened)).label} />
                <span className="myx-fleet-detail-name">{opened.label}</span>
                <span className="myx-fleet-detail-port">{opened.version ?? NOT_BUILT}</span>
              </div>

              <section className="myx-fleet-section">
                <h2 className="myx-fleet-section-title">{S.lifecycle}</h2>
                <Lifecycle head={opened} />
                <Empty text={EMPTIES.daemonRestart.text} source={EMPTIES.daemonRestart.source} />
              </section>

              <section className="myx-fleet-section">
                <h2 className="myx-fleet-section-title">{S.knobs}</h2>
                {overrides.length === 0 ? (
                  <p className="myx-fleet-note">{S.noOverrides}</p>
                ) : (
                  overrides.map((knob) => (
                    <div key={knob.key}>
                      <KnobRow knob={knob} />
                      <p className="myx-fleet-note">{dispositionText(knob.hot)}</p>
                    </div>
                  ))
                )}
              </section>

              <section className="myx-fleet-section">
                <h2 className="myx-fleet-section-title">{S.pool}</h2>
                <Empty text={EMPTIES.pool.text} source={EMPTIES.pool.source} />
              </section>
            </>
          )}
        </aside>
      </div>
    </div>
  );
}

export default FleetPage;
