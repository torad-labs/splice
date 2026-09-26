// Fleet: every head, drawn by the state it is in and how fast and how full it runs. The question on
// arrival is "which head needs me", so the figures lead with the heads split by health and the
// `Attention first` view puts the worst head at the top.
//
// Health is the head's state NOW, never a history the daemon does not keep: a badge per head and one
// split bar for the fleet. Latency does have a history, the landed turns (GET /api/perf/turns), so
// each head draws its time to first byte as a sparkline over its recent turns.
//
// An opened head holds its facts, the step that clears its state, the lifecycle keys, its live turns
// with the stop (V4-319), the settings it overrides and the accounts it rides (GET /api/accounts,
// M4-02), drawn by the same account rows the accounts page prints.
import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { poolOf, selectedExcluded, startAccountsPolling, useAccounts } from '@entities/account';
import type { AccountRow, AccountsState } from '@entities/account';
import { startAuthPolling, useAuth } from '@entities/auth';
import { fetchConfig, fetchTopologyStale, knobDispositions, useConfig } from '@entities/config';
import type { KnobDisposition } from '@entities/config';
import { HeadMark } from '@entities/control-status';
import {
  FAMILY_NAME, familyName, headAttention, inflightText, restartHead, startHead, startHeadsPolling, stopHead, useHeads,
} from '@entities/heads';
import type { HeadAttention, HeadSignals } from '@entities/heads';
import { startModelsPolling, useModels } from '@entities/model';
import type { HeadCatalog } from '@entities/model';
import { startPerfSummaryPolling, startPerfTurnsPolling, usePerfSummary, usePerfTurns } from '@entities/perf';
import type { TurnRow } from '@entities/perf';
import { startTopologyPolling, useTopology } from '@entities/topology';
import { headWindow, startUsagePolling, useUsage } from '@entities/usage';
import type { HeadWindow } from '@entities/usage';
import { DaemonRestart } from '@features/daemon-restart';
import { limitText, limitTone, nearestLimit } from '@features/nearest-limit';
import type { NearestLimit } from '@features/nearest-limit';
import { useViews, ViewTabs } from '@features/views';
import type { View } from '@features/views';
import type { AuthPayload, ConfigPayload, HeadStatus, UsagePayload } from '@shared/api';
import { Blank, Confirm, Copy, Fault, Key, KeyLink } from '@shared/controls';
import { ABSENT, fmtInt, fmtMs, poll, ratio, readFor, timeAgo, useLinkedId, useOpen } from '@shared/lib';
import type { Keyed } from '@shared/lib';
import {
  Badge, DataTable, DetailPanel, Empty, KeyValue, Meter, PageHeader, Pips, Section, Sparkline, StackedBar, Stat, StatRow, Tip,
} from '@shared/ui';
import type { Column, RowGroup } from '@shared/ui';
import { NextRule, accountColumns, accountKey, accountName, accountTone } from '@widgets/account-table';
import { AddBackend } from '@widgets/add-backend';
import { KnobReadout } from '@widgets/knob-form';
import { dispositions } from './coverage';
import { LiveTurns } from './live-turns';
import {
  EMPTIES, HEAD_FIELDS, arrangeHeads, causeHelp, columnsOf, dialectOf, firstBytes, healthParts,
  inflightTotals, lastTurnOf, median, noneAvailable, poolEmpty, rowTone, stateTone, windowTone,
} from './model';
import type { CauseHelp, LastTurn } from './model';
import { H, S } from './strings';
import './fleet.css';

export { dispositions };

const PAGE_ID = 'fleet';
const HEADS_MS = 2000;
const USAGE_MS = 5000;
/** The accounts page's own cadence: windows move per turn, not per second. */
const POOL_MS = 15000;
/** Latency is read per turn landed, and a sparkline of the last turns does not need a faster eye. */
const TURNS_MS = 15000;
const SLOW_MS = 30000;
const LAST_TURN_MS = 60000;

/** The three views this page ships with. `By head` is first because it is the default. */
export const DEFAULT_VIEWS: readonly View[] = [
  { id: 'by-head', name: S.byHead, layout: 'bay', filter: {}, sort: null, group: null, fields: [] },
  { id: 'by-provider', name: S.byProvider, layout: 'bay', filter: {}, sort: null, group: 'provider', fields: [] },
  { id: 'attention', name: S.attentionFirst, layout: 'bay', filter: {}, sort: { field: 'attention', dir: 'desc' }, group: null, fields: [] },
];

/** How a head joins the fleet: `splice add` over HTTP (V4-220 item 3). The key opens the add in the
 *  detail panel, where a head's detail would open; the settings topology edits heads that exist, it
 *  cannot add one. */
export function AddKey({ onAdd }: { onAdd: () => void }) {
  return <Key onClick={onAdd}>{S.addBackend}</Key>;
}

/** Why the opened head is in the state its badge names, and the step that clears it: one line, then
 *  the command to copy or the page to open. */
export function CauseLine({ help }: { help: CauseHelp | null }) {
  if (help === null) return null;
  return (
    <p className="myx-fl-cause" role="status">
      <span>{help.text}</span>
      {help.command === undefined ? null : (
        <>
          <code className="myx-fl-command">{help.command}</code>
          <Copy value={help.command} />
        </>
      )}
      {help.href === undefined ? null : <KeyLink href={help.href}>{help.link}</KeyLink>}
    </p>
  );
}

/** The lifecycle keys. Stop and restart interrupt live sessions, so both arm in place before they
 *  fire (the world's inline two-step); the head's in-flight count is in its facts right above. */
function Lifecycle({ head }: { head: HeadStatus }) {
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const run = (work: (key: string) => Promise<unknown>) => {
    setBusy(true);
    setSent(false);
    setError(null);
    work(head.key).then(
      () => setSent(true),
      (err: unknown) => setError(err instanceof Error ? err.message : String(err)),
    ).finally(() => setBusy(false));
  };

  return (
    <div className="myx-fl-life">
      <div className="myx-fl-keys">
        {head.running ? (
          <>
            <Confirm label={S.restart} confirmLabel={S.restartNow} busy={busy} onConfirm={() => run(restartHead)} />
            <Confirm label={S.stop} confirmLabel={S.stopNow} busy={busy} onConfirm={() => run(stopHead)} />
          </>
        ) : (
          <Key busy={busy} onClick={() => run(startHead)}>{S.start}</Key>
        )}
        {sent ? <Badge tone="ok">{S.sent}</Badge> : null}
      </div>
      {error === null ? null : <Fault message={error} />}
    </div>
  );
}

/** One head as the table and its detail read it: every source joined once, per render. */
export interface HeadLine {
  head: HeadStatus;
  attention: HeadAttention;
  window: HeadWindow;
  /** The masked account id the auth card names, or null. */
  account: string | null;
  /** The head's pinned model, or null when it pins none or the catalog is unread. */
  model: string | null;
  dialect: string | null;
  /** Time to first byte per landed turn, oldest first. */
  latency: readonly number[];
  last: LastTurn;
}

function Latency({ line }: { line: HeadLine }) {
  const middle = median(line.latency);
  if (middle === null) return <>{ABSENT}</>;
  return (
    <span className="myx-fl-lat">
      <Sparkline values={line.latency} label={`${S.firstByte} ${line.head.label}`} format={fmtMs} />
      <span className="myx-fl-figure">{fmtMs(middle)}</span>
    </span>
  );
}

/** Up to this many slots, each is a pip the eye can count; past it, a meter. */
const PIPS_MAX = 16;

/** Past this many slots, the pips stand in two even rows: one row of twelve does not fit the
 *  column beside its figure, and wrapped nine and three. */
const PIPS_ROW = 8;

function InFlight({ head }: { head: HeadStatus }) {
  const gate = head.gate;
  if (gate === null) return <>{ABSENT}</>;
  if (gate.max === 'unlimited') return <span className="myx-fl-figure">{inflightText(head)}</span>;
  if (gate.max <= PIPS_MAX) {
    return (
      <span className="myx-fl-slots">
        <Pips
          used={gate.inflight}
          total={gate.max}
          label={`${S.inflight} ${head.label}`}
          mark={gate.inflight >= gate.max ? 'warn' : 'series-1'}
          rows={gate.max > PIPS_ROW ? 2 : 1}
        />
        <span className="myx-fl-figure">{inflightText(head)}</span>
      </span>
    );
  }
  return (
    <Meter
      value={ratio(gate.inflight, gate.max)}
      tone={gate.inflight >= gate.max ? 'warn' : 'accent'}
      label={`${S.inflight} ${head.label}`}
      figure={inflightText(head)}
    />
  );
}

function WindowFigure({ window, label }: { window: HeadWindow; label: string }) {
  if (window.pct === null) return <>{ABSENT}</>;
  return <Meter value={window.pct / 100} tone={windowTone(window)} label={label} figure={`${Math.round(window.pct)}%`} />;
}

function LastTurnCell({ last, nowMs }: { last: LastTurn; nowMs: number }) {
  switch (last.kind) {
    case 'live':
      // The column has room for a word, and "streaming 3.2s" ran past its edge at 1600: the cell
      // says a turn is running, and its phase and age stand in the tip.
      return <Tip text={`${last.phase} ${fmtMs(last.ageMs)}`}><Badge tone="accent" quiet>{S.running}</Badge></Tip>;
    case 'ago':
      return <>{timeAgo(last.ts, nowMs)}</>;
    case 'none':
      return <>{S.none}</>;
    case 'unknown':
      return <>{ABSENT}</>;
  }
}

function StateBadge({ attention, quiet = false }: { attention: HeadAttention; quiet?: boolean }) {
  return <Badge tone={stateTone(attention.cause)} quiet={quiet}>{S.stateName[attention.cause]}</Badge>;
}

/** The columns an opened head's facts repeat, so an open panel takes their width and the figures
 *  keep theirs: its identity, and its plan window, which reads `–` on every head without a login. */
const IDENTITY_FIELDS: ReadonlySet<string> = new Set(['provider', 'model', 'account', 'window']);

function headColumns(fields: readonly string[], grouped: string | null, nowMs: number, opened: boolean): Column<HeadLine>[] {
  const wanted = new Set(fields.filter((field) => !opened || !IDENTITY_FIELDS.has(field)));
  // Every column but the head's name takes a width in rem, sized to what it draws (a meter with its
  // figure, a sparkline with its median), so opening the panel narrows the names and never clips a
  // figure; the name shares whatever is left.
  const columns: (Column<HeadLine> | null)[] = [
    { key: 'head', label: S.head, primary: true, cell: (line) => <HeadMark head={line.head.key} /> },
    wanted.has('provider') && grouped !== 'provider'
      ? { key: 'provider', label: S.provider, width: 'calc(6.5 * var(--u))', cell: (line) => familyName(line.head.authKind) }
      : null,
    { key: 'state', label: S.state, width: 'calc(6 * var(--u))', cell: (line) => <StateBadge attention={line.attention} quiet /> },
    wanted.has('model') ? { key: 'model', label: S.model, width: 'calc(10 * var(--u))', mono: true, cell: (line) => line.model ?? ABSENT } : null,
    wanted.has('account') ? { key: 'account', label: S.account, width: 'calc(7.5 * var(--u))', mono: true, cell: (line) => line.account ?? ABSENT } : null,
    wanted.has('inflight') ? { key: 'inflight', label: S.inflight, width: 'calc(12 * var(--u))', cell: (line) => <InFlight head={line.head} /> } : null,
    wanted.has('window')
      ? { key: 'window', label: S.window, width: 'calc(9 * var(--u))', cell: (line) => <WindowFigure window={line.window} label={`${S.window} ${line.head.label}`} /> }
      : null,
    wanted.has('latency') ? { key: 'latency', label: S.firstByte, width: 'calc(9.5 * var(--u))', cell: (line) => <Latency line={line} /> } : null,
    wanted.has('turn') ? { key: 'turn', label: S.lastTurn, width: 'calc(6.5 * var(--u))', cell: (line) => <LastTurnCell last={line.last} nowMs={nowMs} /> } : null,
  ];
  return columns.filter((column): column is Column<HeadLine> => column !== null);
}

/** The figures the page leads with: the heads by health, what is in flight against the ceiling, the
 *  fleet's time to first byte, and the nearest limit (the one definition the strip and the accounts
 *  page print). */
function Figures({ lines, landed, limit }: { lines: readonly HeadLine[]; landed: readonly TurnRow[]; limit: NearestLimit | null }) {
  const totals = inflightTotals(lines.map((line) => line.head));
  const fleet = firstBytes(landed);
  const middle = median(fleet);
  return (
    <StatRow>
      <Stat
        label={S.heads}
        value={fmtInt(lines.length)}
        chart={<StackedBar parts={healthParts(lines.map((line) => line.attention.cause))} label={S.heads} legend format={fmtInt} />}
      />
      <Stat
        label={S.inflight}
        value={fmtInt(totals.inflight)}
        {...(totals.max === null ? {} : {
          unit: `/${fmtInt(totals.max)}`,
          chart: <Meter value={ratio(totals.inflight, totals.max)} label={S.inflight} />,
        })}
      />
      <Stat
        label={S.firstByte}
        value={middle === null ? ABSENT : fmtMs(middle)}
        {...(fleet.length === 0 ? {} : { chart: <Sparkline values={fleet} label={S.firstByte} format={fmtMs} /> })}
      />
      {limit === null ? <Stat label={S.nearestLimit} value={ABSENT} /> : (
        <Stat
          label={S.nearestLimit}
          value={`${Math.round(limit.pct)}%`}
          {...(limitTone(limit) === 'ok' ? {} : { tone: limitTone(limit) })}
          chart={<Meter value={limit.pct / 100} tone={limitTone(limit)} label={S.nearestLimit} />}
          sub={limitText(limit)}
        />
      )}
    </StatRow>
  );
}

/** Every fact an opened head carries. The dialect and port are here rather than in the table: they
 *  identify a head, they do not tell the operator whether it needs them. */
function headFacts(line: HeadLine, auth: AuthPayload | null, nowMs: number): [string, ReactNode][] {
  const latched = auth?.[line.head.key]?.refresh_latched ?? null;
  const middle = median(line.latency);
  return [
    [S.provider, familyName(line.head.authKind)],
    [S.port, String(line.head.port)],
    [S.dialect, line.dialect ?? ABSENT],
    [S.model, line.model ?? ABSENT],
    [S.version, line.head.version ?? ABSENT],
    [S.account, line.account ?? ABSENT],
    [S.inflight, <InFlight key="inflight" head={line.head} />],
    [S.window, <WindowFigure key="window" window={line.window} label={`${S.window} ${line.head.label}`} />],
    [S.firstByte, middle === null ? ABSENT : <Latency key="latency" line={line} />],
    [S.lastTurn, <LastTurnCell key="turn" last={line.last} nowMs={nowMs} />],
    ...(latched === null ? [] : [[S.refreshError, latched] as [string, ReactNode]]),
  ];
}

/** The opened head's account pool: the accounts entity filtered to this head, drawn as the rows the
 *  accounts page draws, so an exclusion, a window and the next target read the same in both. */
function Pool({ head, payload, nowMs }: { head: HeadStatus; payload: AccountsState | null; nowMs: number }) {
  if (payload === null) return <Blank strips={1} />;
  if ('pending' in payload) return <Empty text={EMPTIES.pool.text} source={EMPTIES.pool.source} />;
  const pool = poolOf(payload.accounts, head.key);
  if (pool.length === 0) {
    const empty = poolEmpty(head.authKind);
    return (
      <Empty
        text={empty.text}
        source={empty.source}
        {...(empty === EMPTIES.noAccounts ? { action: <KeyLink href="#/accounts">{S.signIn}</KeyLink> } : {})}
      />
    );
  }
  // The daemon's own next target, named once above the rows with the rule that chose it: the panel
  // is too narrow for a Next column, and one fact needs no column.
  const target = pool.find((account) => account.next_target === true) ?? null;
  return (
    <>
      {target === null ? null : (
        <KeyValue rows={[[S.next, <span key="next" className="myx-fl-next">{accountName(target)}<NextRule account={target} accounts={pool} /></span>]]} />
      )}
      {noneAvailable(pool) ? <Empty text={EMPTIES.noneAvailable.text} source={EMPTIES.noneAvailable.source} /> : null}
      <DataTable
        columns={accountColumns({ fields: ['account'], grouped: null, nowMs, accounts: pool, compact: true })}
        rows={pool}
        rowKey={accountKey}
        label={S.pool}
        rowTone={(account) => accountTone(account, nowMs)}
      />
    </>
  );
}

/** What the board reads besides the heads: each a store's data, handed in so a test can render the
 *  board without a store (a static render only ever sees a store's initial state). */
export interface FleetSources {
  auth: AuthPayload | null;
  usage: UsagePayload | null;
  accounts: AccountsState | null;
  accountsError?: string | null;
  accountsRead?: number | null;
  /** The topology file as the daemon reads it, or null while unread or not served. */
  topology: Record<string, unknown> | null;
  catalogs: readonly HeadCatalog[] | null;
  /** A daemon older than this console answered 404 for the topology or the model list. */
  fieldsPending: boolean;
  topologyStale: boolean;
  landed: readonly TurnRow[];
  /** Each head's newest turn, epoch ms, from the perf summary; a head it does not name is unknown. */
  lastTs: ReadonlyMap<string, number | null>;
  /** The settings the opened head overrides. */
  overrides: readonly KnobDisposition[];
  /** The opened head's config read that failed, in the daemon's words: its overrides are unknown. */
  overridesError?: string | null;
}

export function FleetBoard({ heads, error = null, lastRead = null, sources, openKey, onOpen, adding = false, onAdd = () => undefined, nowMs }: {
  /** Null while the first read is out. */
  heads: readonly HeadStatus[] | null;
  error?: string | null;
  lastRead?: number | null;
  sources: FleetSources;
  openKey: string | null;
  onOpen: (key: string | null) => void;
  /** The add form holds the detail panel; a head's detail and the add never share it. */
  adding?: boolean;
  onAdd?: (open: boolean) => void;
  nowMs: number;
}) {
  const { active } = useViews(PAGE_ID, DEFAULT_VIEWS);
  const accounts: readonly AccountRow[] = sources.accounts !== null && 'accounts' in sources.accounts ? sources.accounts.accounts : [];

  const signalsFor = (head: HeadStatus): HeadSignals => ({
    credentialPresent: sources.auth?.[head.key]?.present ?? null,
    refreshLatched: sources.auth?.[head.key]?.refresh_latched ?? null,
    // Until GET /api/accounts answers, `accounts` is empty and this is false: a head must not read
    // as excluded on a route nobody has read yet.
    accountExcluded: selectedExcluded(poolOf(accounts, head.key), nowMs),
    topologyStale: sources.topologyStale,
  });

  const lineOf = (head: HeadStatus): HeadLine => {
    const pinned = sources.catalogs?.find((entry) => entry.head === head.key)?.pinned_model ?? '';
    return {
      head,
      attention: headAttention(head, signalsFor(head)),
      window: headWindow(sources.usage, head.key, nowMs),
      account: sources.auth?.[head.key]?.account_id_masked ?? null,
      // The catalog writes "" for a head that pins no model: that is an absence, not a name.
      model: pinned === '' ? null : pinned,
      dialect: dialectOf(sources.topology, head.key),
      latency: firstBytes(sources.landed, head.key),
      last: lastTurnOf(head, sources.lastTs.has(head.key) ? sources.lastTs.get(head.key) : undefined),
    };
  };

  const all = heads ?? [];
  const lines = new Map(all.map((head) => [head.key, lineOf(head)]));
  const lineFor = (head: HeadStatus): HeadLine => lines.get(head.key) ?? lineOf(head);
  const groups: RowGroup<HeadLine>[] = arrangeHeads(all, active, signalsFor).map((group) => ({
    key: group.key === '' ? S.heads : group.key,
    title: group.key === '' ? S.heads : FAMILY_NAME[group.key],
    count: group.heads.length,
    rows: group.heads.map(lineFor),
  }));
  const opened = openKey === null ? null : lines.get(openKey) ?? null;
  const help = opened === null ? null : causeHelp(opened.head, opened.attention.cause, sources.auth?.[opened.head.key]);

  return (
    <div className="myx-fl">
      <PageHeader title={S.title} info={{ text: H.about, label: S.about }} actions={all.length === 0 ? undefined : <AddKey onAdd={() => onAdd(true)} />}>
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
      </PageHeader>

      {error === null ? null : <Fault message={error} lastRead={lastRead} />}

      <div className={opened === null && !adding ? 'myx-fl-board' : 'myx-fl-board myx-fl-board-open'}>
        <div className="myx-fl-main">
          {heads === null ? <Blank strips={4} /> : heads.length === 0 ? (
            <Empty text={EMPTIES.noHeads.text} source={EMPTIES.noHeads.source} action={<AddKey onAdd={() => onAdd(true)} />} />
          ) : (
            <>
              <Figures lines={[...lines.values()]} landed={sources.landed} limit={nearestLimit({ accounts, usage: sources.usage, auth: sources.auth }, nowMs)} />
              <Section title={S.heads} count={all.length} info={{ text: H.firstByte, label: S.aboutFirstByte }}>
                <DataTable
                  columns={headColumns(columnsOf(active, HEAD_FIELDS), active.group, nowMs, opened !== null || adding)}
                  {...(active.group === null ? { rows: groups.flatMap((group) => group.rows) } : { groups })}
                  rowKey={(line) => line.head.key}
                  label={S.heads}
                  onOpen={(line) => onOpen(openKey === line.head.key ? null : line.head.key)}
                  openLabel={(line) => `${S.openHead} ${line.head.label}`}
                  selectedKey={openKey}
                  rowTone={(line) => rowTone(line.attention.cause)}
                />
              </Section>
              {/* The two field sources, when a daemon older than them answered 404: named once here
                  rather than left as a bare absence in every row. Still loading, or a read that
                  failed, is not that answer (M4-06). */}
              {sources.fieldsPending ? <Empty text={EMPTIES.fields.text} source={EMPTIES.fields.source} /> : null}
            </>
          )}
        </div>

        {/* Unmounted at rest: no track and no empty panel until a head or the add is opened. */}
        {!adding || opened !== null ? null : (
          <DetailPanel title={S.addBackend} label={S.addBackend} onClose={() => onAdd(false)} closeLabel={S.close}>
            <AddBackend onDone={() => onAdd(false)} />
          </DetailPanel>
        )}
        {opened === null ? null : (
          <DetailPanel
            title={opened.head.label}
            label={S.detail}
            status={<StateBadge attention={opened.attention} />}
            onClose={() => onOpen(null)}
            closeLabel={S.close}
          >
            <CauseLine help={help} />
            <KeyValue rows={headFacts(opened, sources.auth, nowMs)} />
            <Section title={S.lifecycle}>
              <Lifecycle head={opened.head} />
              {/* The daemon-level restart (POST /api/daemon/restart), distinct from the head restart
                  above: it drains every head's turns and the daemon's supervisor brings it back. The
                  same control the doctor mounts. */}
              <DaemonRestart />
            </Section>
            {opened.head.running ? (
              <Section title={S.liveTurns} info={{ text: H.liveTurns, label: S.aboutLiveTurns }}>
                <LiveTurns key={opened.head.key} head={opened.head.key} />
              </Section>
            ) : null}
            {/* The count is the state: a head that overrides nothing is a 0, not a box saying so. */}
            <Section title={S.knobs} {...(sources.overridesError == null ? { count: sources.overrides.length } : {})}>
              {sources.overridesError == null ? null : <Fault message={sources.overridesError} />}
              {sources.overrides.map((knob) => <KnobReadout key={knob.key} knob={knob} />)}
            </Section>
            <Section title={S.pool}>
              {sources.accountsError === null || sources.accountsError === undefined
                ? null : <Fault message={sources.accountsError} lastRead={sources.accountsRead ?? null} />}
              <Pool head={opened.head} payload={sources.accounts} nowMs={nowMs} />
            </Section>
          </DetailPanel>
        )}
      </div>
    </div>
  );
}

/** The settings the opened head overrides, from the config read made for THAT head, and that read's
 *  failure (V4-304). */
export function overridesOf(config: Keyed<string | null, ConfigPayload>, openKey: string | null): { knobs: KnobDisposition[]; error: string | null } {
  if (openKey === null) return { knobs: [], error: null };
  const { data, error } = readFor(config, openKey);
  return { knobs: data === null ? [] : knobDispositions(data, openKey).filter((knob) => knob.provenance === 'head override'), error };
}

export function FleetPage() {
  const headsResource = useHeads((state) => state);
  const usageResource = useUsage((state) => state);
  const authResource = useAuth((state) => state);
  const configResource = useConfig((state) => state);
  const topologyResource = useTopology((state) => state);
  const modelsResource = useModels((state) => state);
  const accountsResource = useAccounts((state) => state);
  const turnsResource = usePerfTurns((state) => state);
  // Only for each head's last turn (`last_ts`), which the summary carries whatever its window.
  const summaryResource = usePerfSummary((state) => state);

  useEffect(() => {
    const stops = [
      startHeadsPolling(HEADS_MS),
      startUsagePolling(USAGE_MS),
      startAccountsPolling(POOL_MS),
      startAuthPolling(SLOW_MS),
      startTopologyPolling(SLOW_MS),
      startModelsPolling(SLOW_MS),
      startPerfTurnsPolling(undefined, TURNS_MS),
      // The summary is read only for each head's last turn, which prints to the minute, and one read
      // costs the daemon ~300ms (measured 2026-09-24, against 14ms for /api/heads): once a minute.
      startPerfSummaryPolling('24h', LAST_TURN_MS),
    ];
    return () => stops.forEach((stop) => stop());
  }, []);

  const [openKey, setOpenKey] = useOpen(useLinkedId());
  const [adding, setAdding] = useState(false);

  // /health's flag is the one part of the topology contract every daemon serves, so it is read from
  // the config entity's own pass-through rather than from GET /api/topology.
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

  const topology = topologyResource.data;
  const models = modelsResource.data;
  const turns = turnsResource.data;
  const overrides = overridesOf(configResource, openKey);

  return (
    <FleetBoard
      heads={headsResource.data}
      error={headsResource.error}
      lastRead={headsResource.lastUpdated}
      sources={{
        auth: authResource.data,
        usage: usageResource.data,
        accounts: accountsResource.data,
        accountsError: accountsResource.error,
        accountsRead: accountsResource.lastUpdated,
        topology: topology !== null && 'topology' in topology ? topology.topology : null,
        catalogs: models !== null && 'heads' in models ? models.heads : null,
        fieldsPending: (topology !== null && 'pending' in topology) || (models !== null && 'pending' in models),
        topologyStale,
        landed: turns !== null && 'landed' in turns ? turns.landed : [],
        lastTs: new Map((summaryResource.data?.heads ?? []).flatMap((row) => (row.last_ts === undefined ? [] : [[row.key, row.last_ts] as const]))),
        overrides: overrides.knobs,
        overridesError: overrides.error,
      }}
      openKey={openKey}
      onOpen={(key) => {
        setAdding(false);
        setOpenKey(key);
      }}
      adding={adding}
      onAdd={(open) => {
        setOpenKey(null);
        setAdding(open);
      }}
      // Read once per render: the heads poll re-renders this page every two seconds, which is finer
      // than any exclusion expiry or reset line it is compared against.
      nowMs={Date.now()}
    />
  );
}

export default FleetPage;
