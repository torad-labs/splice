// The fleet page: one rack of head strips. ARRIVE is "which head needs me", so the holder edge
// carries one printed cause and the `attention first` view puts the worst head at the top.
//
// Two of this page's fields come from routes that were rows (the model catalog, the topology file).
// While either is unread the page prints one honest empty naming the rows, rather than inventing a
// value for a source nobody can read yet. The account pool is read (GET /api/accounts, M4-02): an
// opened head shows the accounts it rides, and a head whose selected account is excluded says so on
// the rack.
import { useEffect, useState } from 'react';
import { startAccountsPolling, useAccounts } from '@entities/account';
import type { AccountRow, AccountsState } from '@entities/account';
import { headAttention } from '@entities/heads';
import { restartHead, startHead, startHeadsPolling, stopHead, useHeads } from '@entities/heads';
import type { HeadSignals } from '@entities/heads';
import { startAuthPolling, useAuth } from '@entities/auth';
import { dispositionText, fetchConfig, fetchTopologyStale, knobDispositions, useConfig } from '@entities/config';
import type { KnobDisposition } from '@entities/config';
import { startModelsPolling, useModels } from '@entities/model';
import { startTopologyPolling, useTopology } from '@entities/topology';
import { headWindow, startUsagePolling, useUsage } from '@entities/usage';
import { DaemonRestart } from '@features/daemon-restart';
import { useViews, ViewTabs } from '@features/views';
import type { View } from '@features/views';
import { poll } from '@shared/lib';
import type { HeadStatus } from '@shared/api';
import { Bay, Empty, FieldBox, HolderEdge } from '@shared/ui';
import { Blank, Fault, Key } from '@shared/controls';
import { ACCOUNT_COLUMNS, AccountStrip } from '@widgets/account-strip';
import { HeadStrip, HEAD_COLUMNS } from '@widgets/head-strip';
import { EMPTIES, arrangeHeads, columnsOf, dialectOf, poolEmpty, poolNext, poolOf, selectedExcluded } from './model';
import { dispositions } from './coverage';
import { S } from './strings';
import './fleet.css';

export { dispositions };

const PAGE_ID = 'fleet';
const HEADS_MS = 2000;
const USAGE_MS = 5000;
/** The accounts page's own cadence: windows move per turn, not per second. */
const POOL_MS = 15000;
const SLOW_MS = 30000;

/** The three views this page ships with. `by head` is first because it is the default. */
export const DEFAULT_VIEWS: readonly View[] = [
  { id: 'by-head', name: S.byHead, layout: 'bay', filter: {}, sort: null, group: null, fields: [] },
  { id: 'by-provider', name: S.byProvider, layout: 'bay', filter: {}, sort: null, group: 'provider', fields: [] },
  { id: 'attention', name: S.attentionFirst, layout: 'bay', filter: {}, sort: { field: 'attention', dir: 'desc' }, group: null, fields: [] },
];

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
      {error === null ? null : <Fault message={error} />}
      {note === null ? null : <p className="myx-fleet-note" role="status">{note}</p>}
    </div>
  );
}

/** The key one account strip holds in the pool rack: the label within a pool, else the credential
 *  file a single login is joined on. */
function poolKey(account: AccountRow): string {
  return `${account.kind}:${account.label ?? account.credential_path ?? account.heads.join(',')}`;
}

/** The opened head's account pool: the accounts entity filtered to this head, printed as the same
 *  strips the accounts page prints (so an exclusion is struck and its reason printed the same way),
 *  with the daemon's own next target named above the rack, where a 24rem column can show it. */
function Pool({ head, payload, nowMs }: { head: HeadStatus; payload: AccountsState | null; nowMs: number }) {
  if (payload === null) return <Blank strips={1} />;
  if ('pending' in payload) return <Empty text={EMPTIES.pool.text} source={EMPTIES.pool.source} />;
  const pool = poolOf(payload.accounts, head.key);
  if (pool.length === 0) {
    const empty = poolEmpty(head.authKind);
    return <Empty text={empty.text} source={empty.source} />;
  }
  const next = poolNext(pool);
  // A single login has no pool to select from, so "no next target" is a fact about it only when the
  // rack holds a labeled pool account.
  const pooled = pool.some((account) => account.label !== null);
  return (
    <>
      {next !== null ? (
        <p className="myx-fleet-note">{`${S.nextTarget} ${next.label}, ${next.rule}`}</p>
      ) : pooled ? (
        <Empty text={EMPTIES.noneAvailable.text} source={EMPTIES.noneAvailable.source} />
      ) : null}
      <Bay label={S.accounts} count={pool.length} compact>
        {pool.map((account) => {
          const isNext = next !== null && next.label === account.label;
          return (
            <AccountStrip
              key={poolKey(account)}
              account={account}
              isNext={isNext}
              nextRule={isNext ? next.rule : ''}
              columns={ACCOUNT_COLUMNS}
              nowMs={nowMs}
            />
          );
        })}
      </Bay>
    </>
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
  const accountsResource = useAccounts((state) => state);

  useEffect(() => {
    const stops = [
      startHeadsPolling(HEADS_MS),
      startUsagePolling(USAGE_MS),
      startAccountsPolling(POOL_MS),
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
  const fieldsPending = (topology !== null && 'pending' in topology)
    || (modelsResource.data !== null && 'pending' in modelsResource.data);

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

  // Read once per render: the heads poll re-renders this page every two seconds, which is finer than
  // any exclusion expiry or reset line it is compared against.
  const nowMs = Date.now();
  const accounts: readonly AccountRow[] = accountsResource.data !== null && 'accounts' in accountsResource.data
    ? accountsResource.data.accounts
    : [];

  const signalsFor = (head: HeadStatus): HeadSignals => ({
    credentialPresent: auth?.[head.key]?.present ?? null,
    refreshLatched: auth?.[head.key]?.refresh_latched ?? null,
    // GET /api/accounts is served (V4-132 landed). Until it answers, `accounts` is empty and this is
    // false: a head must not read as excluded on a route nobody has read yet.
    accountExcluded: selectedExcluded(poolOf(accounts, head.key), nowMs),
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
      <header className="myx-page-head">
        <h1 className="myx-page-title">{S.title}</h1>
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
      </header>

      {headsResource.error === null ? null : <Fault message={headsResource.error} />}
      {headsResource.data === null ? <Blank strips={4} /> : null}

      <div className={opened === null ? 'myx-fleet-body' : 'myx-fleet-body myx-fleet-body-open'}>
        <div className="myx-fleet-bays">
          {headsResource.data !== null && heads.length === 0 ? (
            <Empty text={EMPTIES.noHeads.text} source={EMPTIES.noHeads.source} />
          ) : (
            groups.map((group) => (
              <Bay
                key={group.key === '' ? S.bay : group.key}
                label={group.key === '' ? S.bay : group.key}
                count={group.heads.length}
                compact
              >
                {group.heads.map((head) => (
                  <HeadStrip
                    key={head.key}
                    head={head}
                    attention={headAttention(head, signalsFor(head))}
                    window={headWindow(usageResource.data, head.key)}
                    account={auth?.[head.key]?.account_id_masked ?? auth?.[head.key]?.login ?? null}
                    dialect={dialectOf(topologyTable, head.key)}
                    model={catalogs?.find((entry) => entry.head === head.key)?.pinned_model ?? null}
                    columns={columns}
                    selected={openKey === head.key}
                    onOpen={() => toggle(head.key)}
                  />
                ))}
              </Bay>
            ))
          )}

          {/* The two field sources, when a daemon older than them answered 404: named here rather
              than left as a bare `none` in every strip, so the operator learns which work item brings
              them. Still loading, or a read that failed, is not that answer (M4-06). */}
          {fieldsPending ? (
            <Empty text={EMPTIES.fields.text} source={EMPTIES.fields.source} />
          ) : null}
        </div>

        {/* THE COLUMN IS A ZERO TRACK AT REST AND SWELLS OPEN (M1-116 rules collapse over the
            unmount M1-102 shipped here; the measurement below is M1-102's and still stands). The
            resting column cost 25.0% of the frame - 384 of 1536 - to carry a card covering 3.5% of
            itself, and the rack paid for it: this rack's own field grid declares 1200px and the bay
            had 969.8px, so `window` was cut 126px past the bay edge and `last turn` sat 308px past
            it, entirely invisible. At rest the rack now measures 1365.8px and fits its own grid.
            THE ASIDE STAYS MOUNTED AND EMPTY, which is the difference and the whole point: it gives
            the track something to transition FROM, so CONTRACTS section 6's swell is one
            declaration on grid-template-columns rather than a mount followed by a fade that pops if
            the mount lands a frame late. This shape is turns', sessions' and projects'.
            THE EMPTY STAYS GONE, deliberately rather than by oversight: an honest empty says what a
            panel is missing and which source would supply it, and at rest there is no panel to be
            missing anything - the console has not been asked for a head yet, so a card captioned
            "no head opened" describes a panel that does not exist. The strips are the affordance;
            the comp of record has no resting detail column either. `EMPTIES.noOpened` went with it:
            M1-102 could not delete it because model.ts was outside that fence, and M2-28 did. */}
        {/* THE EMPTY LANDMARK IS HIDDEN WHILE IT IS EMPTY (M1-123, one shape across five pages).
            At rest this aside is mounted and holds nothing, and an <aside> with a label is a
            COMPLEMENTARY LANDMARK whatever else it carries — measured in the live accessibility
            tree: role=complementary, ignored=false, children=0 — so a reader's landmark list
            carried an empty "fleet detail". aria-hidden is gated by the SAME `opened === null` that
            gates the content, so the exposure and the content cannot desync: they are one
            expression, not two facts kept in step. The element stays mounted, which is what gives
            the track something to transition from — the whole reason collapse beat unmount. */}
        <aside className="myx-fleet-detail myx-swell" aria-label={S.detail} aria-hidden={opened === null}>
          {opened === null ? null : (
            <>
              <Key className="myx-swell-close" onClick={() => setOpenKey(null)}>{S.close}</Key>
              <div className="myx-fleet-detail-head">
                <HolderEdge state={headAttention(opened, signalsFor(opened)).edge} label={headAttention(opened, signalsFor(opened)).label} />
                <span className="myx-fleet-detail-name">{opened.label}</span>
                <span className="myx-fleet-detail-port">{opened.version ?? S.absent}</span>
              </div>

              <section className="myx-fleet-section">
                <h2 className="myx-fleet-section-title">{S.lifecycle}</h2>
                <Lifecycle head={opened} />
                {/* The daemon-level restart (POST /api/daemon/restart), distinct from the head
                    restart above: it drains every head's turns and the daemon's supervisor brings
                    it back. The same control the doctor's upgrade section mounts. */}
                <DaemonRestart />
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
                {accountsResource.error === null ? null : <Fault message={accountsResource.error} />}
                <Pool head={opened} payload={accountsResource.data} nowMs={nowMs} />
              </section>
            </>
          )}
        </aside>
      </div>
    </div>
  );
}

export default FleetPage;
