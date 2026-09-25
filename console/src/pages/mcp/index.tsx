// MCP: the shared host's servers, drawn by state and load, and the four limits it runs under.
//
// A live list (docs/design/DESIGN.md section 7): the servers by state as one split bar, how many
// the host runs against its cap, the sessions and streams riding them, and one row per server with
// its sessions drawn against the busiest. Two things it refuses to do. It offers no restart control,
// because there is no restart route: /mcp/{name} is the JSON-RPC transport, and a console that
// POSTed there would speak initialize into the protocol. And it never restates the four host limits:
// they are runtime knobs read from the config payload, and a knob the running daemon does not carry
// says so rather than printing a default this page invented.
import { useEffect, useState } from 'react';
import { useLocation } from 'react-router';
import { fetchConfig, knobDispositions, useConfig } from '@entities/config';
import { startMcpPolling, useMcp } from '@entities/mcp';
import type { McpPayload, McpRow } from '@entities/mcp';
import { useViews, ViewTabs } from '@features/views';
import type { View } from '@features/views';
import { knobLabel } from '@widgets/knob-form';
import { Blank, Fault, KeyLink } from '@shared/controls';
import { ABSENT, fmtInt, ratio, timeAgo } from '@shared/lib';
import {
  Badge, DataTable, DetailPanel, Empty, InfoTip, KeyValue, Meter, PageHeader, Section, StackedBar, Stat, StatRow,
} from '@shared/ui';
import type { Column } from '@shared/ui';
import { dispositions } from './coverage';
import { arrangeServers, hosted, hostLimits, limitText, liveOf, maxServersOf, stateParts, stateText, totalsOf, TONE } from './model';
import type { HostLimit, McpTotals } from './model';
import { H, S, U } from './strings';
import './mcp.css';

export { dispositions };

const PAGE_ID = 'mcp';
const POLL_MS = 10000;

/** The name this page accepts in the hash query. Declared HERE, not in the fixture module: a
 *  static import of that module, even for one constant, is a real dependency edge, so the bundler
 *  would include the fixture and its strings would ship (CONTRACTS.md section 4). */
const FIXTURE = 'mcp';

/** Whether the address asks for THIS page's fixture, by that fixture's own file name. The capture
 *  marker rests on it (law 23): a name this page does not carry is not a fixture. */
export function wantsFixture(search: string): boolean {
  return import.meta.env.DEV && new URLSearchParams(search).get('fixture') === FIXTURE;
}

export const DEFAULT_VIEWS: readonly View[] = [
  { id: 'by-name', name: S.byName, layout: 'bay', filter: {}, sort: null, group: null, fields: [] },
  { id: 'hosted-first', name: S.hostedFirst, layout: 'bay', filter: {}, sort: { field: 'state', dir: 'desc' }, group: null, fields: [] },
];

const SETTINGS_HREF = '#/settings';

/** The figures the page leads with: every server by state, the hosted ones against the cap, and
 *  the load riding them. A restart is the only evidence a child died, so any restart tints its tile. */
function Figures({ totals, max }: { totals: McpTotals; max: number | null }) {
  const servers = totals.byState.hosted + totals.byState.idle + totals.byState.ineligible;
  const running = totals.byState.hosted;
  return (
    <StatRow>
      <Stat
        label={S.servers}
        value={fmtInt(servers)}
        chart={<StackedBar parts={stateParts(totals)} label={S.servers} legend format={fmtInt} />}
      />
      <Stat
        label={S.hosted}
        value={fmtInt(running)}
        {...(max === null ? {} : {
          unit: `${U.of} ${fmtInt(max)}`,
          chart: <Meter value={ratio(running, max)} tone={running >= max ? 'warn' : 'ok'} label={S.hosted} />,
        })}
      />
      <Stat label={S.sessions} value={fmtInt(totals.sessions)} />
      <Stat label={S.streams} value={fmtInt(totals.streams)} />
      <Stat label={S.restarts} value={fmtInt(totals.restarts)} {...(totals.restarts > 0 ? { tone: 'warn' as const } : {})} />
    </StatRow>
  );
}

/** Every fact the opened server carries. A direct server carries only the planner's reason. */
function serverFacts(row: McpRow): [string, string][] {
  const live = hosted(row.server);
  if (live === null) return [[S.reason, row.server.eligible ? ABSENT : row.server.reason]];
  const at = (ms: number | undefined) => (ms === undefined ? ABSENT : timeAgo(ms));
  return [
    [S.pid, live.pid === undefined ? ABSENT : String(live.pid)],
    [S.sessions, live.hosted ? fmtInt(live.sessions) : ABSENT],
    [S.streams, live.hosted ? fmtInt(live.streams) : ABSENT],
    [S.restarts, fmtInt(live.restarts)],
    [S.started, at(live.started_at)],
    [S.lastCall, at(live.last_activity)],
    [S.lastError, live.last_error ?? ABSENT],
  ];
}

function ServersSection({ rows }: { rows: readonly McpRow[] }) {
  const [openKey, setOpenKey] = useState<string | null>(null);
  const open = rows.find((row) => row.name === openKey) ?? null;
  const busiest = Math.max(0, ...rows.map((row) => liveOf(row)?.sessions ?? 0));
  const columns: Column<McpRow>[] = [
    { key: 'server', label: S.server, width: '22%', mono: true, primary: true, cell: (row) => row.name },
    { key: 'state', label: S.state, width: '14%', cell: (row) => <Badge tone={TONE[row.state]} quiet>{stateText(row.state)}</Badge> },
    {
      key: 'sessions',
      label: S.sessions,
      width: '28%',
      cell: (row) => {
        const live = liveOf(row);
        if (live === null) return ABSENT;
        return <Meter value={ratio(live.sessions, busiest)} tone="neutral" label={`${S.sessions} ${row.name}`} figure={fmtInt(live.sessions)} />;
      },
    },
    {
      key: 'streams',
      label: S.streams,
      width: '11%',
      align: 'end',
      mono: true,
      cell: (row) => {
        const live = liveOf(row);
        return live === null ? ABSENT : fmtInt(live.streams);
      },
    },
    {
      key: 'restarts',
      label: S.restarts,
      width: '11%',
      align: 'end',
      mono: true,
      cell: (row) => {
        const live = hosted(row.server);
        return live === null ? ABSENT : fmtInt(live.restarts);
      },
    },
    {
      key: 'call',
      label: S.lastCall,
      align: 'end',
      mono: true,
      cell: (row) => {
        const at = hosted(row.server)?.last_activity;
        return at === undefined ? ABSENT : timeAgo(at);
      },
    },
  ];
  return (
    <Section title={S.servers} count={rows.length} info={{ text: H.restarts, label: S.aboutRestarts }}>
      <div className={open === null ? 'myx-mcp-board' : 'myx-mcp-board myx-mcp-board-open'}>
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(row) => row.name}
          label={S.servers}
          onOpen={(row) => setOpenKey(row.name === openKey ? null : row.name)}
          openLabel={(row) => `${S.open} ${row.name}`}
          selectedKey={openKey}
          rowTone={(row) => (hosted(row.server)?.last_error === undefined ? null : 'warn')}
        />
        {/* Unmounted at rest: no track and no empty panel until a server is opened. */}
        {open === null ? null : (
          <DetailPanel
            title={open.name}
            label={S.detail}
            status={(
              <>
                <Badge tone={TONE[open.state]}>{stateText(open.state)}</Badge>
                {open.state === 'ineligible' ? <InfoTip text={H.direct} label={S.aboutDirect} /> : null}
              </>
            )}
            onClose={() => setOpenKey(null)}
            closeLabel={S.close}
          >
            <KeyValue rows={serverFacts(open)} />
          </DetailPanel>
        )}
      </div>
    </Section>
  );
}

/** The four host limits, read-only. A knob the running daemon does not carry says so; the default
 *  lives in the daemon's own enum, and this page is not a second place for it. */
function LimitsSection({ limits }: { limits: readonly HostLimit[] }) {
  const columns: Column<HostLimit>[] = [
    { key: 'limit', label: S.limit, width: '40%', primary: true, cell: (limit) => knobLabel(limit.key) },
    {
      key: 'value',
      label: S.value,
      width: '30%',
      mono: true,
      cell: (limit) => (limit.knob === null ? <Badge tone="neutral" quiet>{S.notCarried}</Badge> : limitText(limit.knob)),
    },
    {
      key: 'applies',
      label: S.applies,
      cell: (limit) => (limit.knob === null ? ABSENT : <Badge tone="neutral" quiet>{limit.knob.hot ? S.live : S.restart}</Badge>),
    },
  ];
  return (
    <Section title={S.limits} actions={<KeyLink href={SETTINGS_HREF}>{S.editLimits}</KeyLink>}>
      <DataTable columns={columns} rows={limits} rowKey={(limit) => limit.key} label={S.limits} />
    </Section>
  );
}

/**
 * The board takes its payload as a prop rather than reading the store (CONTRACTS.md section 4): a
 * static render only ever sees a zustand store's initial state, so a board that read the store
 * could not be rendered from data by a test or a capture.
 */
export function McpBoard({ payload, limits = [], view = null, sample }: {
  payload: McpPayload | null;
  limits?: readonly HostLimit[];
  /** The active saved view; null is name order. */
  view?: Pick<View, 'group' | 'sort'> | null;
  /** The fixture's own file name when a fixture fed this board, undefined otherwise. */
  sample?: string | undefined;
}) {
  const rows = arrangeServers(payload, view ?? { group: null, sort: null });
  const body = payload === null ? <Blank strips={3} />
    : !payload.hosting ? (
      <Empty text={S.hostingOff} source={H.hostingOff} action={<KeyLink href={SETTINGS_HREF}>{S.openSettings}</KeyLink>} />
    )
    : rows.length === 0 ? <Empty text={S.noServers} source={H.noServers} />
    : (
      <>
        <Figures totals={totalsOf(rows)} max={maxServersOf(limits)} />
        <ServersSection rows={rows} />
      </>
    );
  return (
    <div className="myx-mcp" {...(import.meta.env.DEV && sample !== undefined ? { 'data-sample': sample } : {})}>
      {body}
      {limits.length === 0 ? null : <LimitsSection limits={limits} />}
    </div>
  );
}

export function McpPage() {
  const { search } = useLocation();
  const { active } = useViews(PAGE_ID, DEFAULT_VIEWS);
  const mcp = useMcp((state) => state);
  const config = useConfig((state) => state);

  useEffect(() => startMcpPolling(POLL_MS), []);
  useEffect(() => {
    const id = setInterval(() => { void fetchConfig(); }, POLL_MS * 3);
    void fetchConfig();
    return () => clearInterval(id);
  }, []);

  const [sample, setSample] = useState<{ name: string; payload: McpPayload } | null>(null);

  // The fixture loads through a DYNAMIC import inside the DEV branch, so a production build drops
  // the branch and the fixture is not a dependency of anything that ships. The specifier is built at
  // runtime: a literal import stays a dependency edge through the single-file build even when the
  // branch around it is dead (measured 2026-09-18).
  useEffect(() => {
    if (!wantsFixture(search)) {
      setSample(null);
      return;
    }
    void import(/* @vite-ignore */ `./fixtures/${FIXTURE}.ts`).then((module: { fixtureMcp?: McpPayload }) => {
      setSample(module.fixtureMcp === undefined ? null : { name: FIXTURE, payload: module.fixtureMcp });
    }).catch(() => undefined);
  }, [search]);

  const limits = hostLimits(config.data === null ? [] : knobDispositions(config.data));

  return (
    <>
      <PageHeader
        title={S.title}
        {...(sample === null ? {} : { actions: <Badge tone="neutral">{S.sample}</Badge> })}
      >
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
      </PageHeader>
      {mcp.error === null ? null : <Fault message={mcp.error} lastRead={sample === null ? mcp.lastUpdated : null} />}
      <McpBoard
        payload={sample === null ? mcp.data : sample.payload}
        limits={config.data === null ? [] : limits}
        view={active}
        {...(sample === null ? {} : { sample: sample.name })}
      />
    </>
  );
}

export default McpPage;
