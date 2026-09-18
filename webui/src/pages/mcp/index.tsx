// The MCP page: the shared host's servers as a rack of strips.
//
// Two things it refuses to do. It never offers a restart control, because there is no restart
// route — a console that POSTed at /mcp/{name} would be speaking JSON-RPC initialize into the
// protocol endpoint, not restarting a child. And it never restates the four host limits: they are
// runtime knobs, so they are read from the config payload with their provenance like every other
// knob, and a knob the running daemon does not carry shows its name with no value rather than a
// default this page invented.
import { useEffect, useState } from 'react';
import { dispositionText, fetchConfig, knobDispositions, useConfig } from '@entities/config';
import { MCP_RESTART, startMcpPolling, useMcp } from '@entities/mcp';
import type { McpRow } from '@entities/mcp';
import { useViews, ViewTabs } from '@features/views';
import type { View } from '@features/views';
import { Bay, Empty, ErrorNote, FieldBox, HolderEdge, SkeletonRows, Strip, StripField } from '@shared/ui';
import { EMPTIES, arrangeServers, hostLimits, stateEdge, stateLabel, hosted } from './model';
import { dispositions } from './coverage';
import { S } from './strings';
import './mcp.css';

export { dispositions };

const PAGE_ID = 'mcp';
const POLL_MS = 10000;

export const DEFAULT_VIEWS: readonly View[] = [
  { id: 'by-name', name: S.byName, layout: 'bay', filter: {}, sort: null, group: null, fields: [] },
  { id: 'hosted-first', name: S.hostedFirst, layout: 'bay', filter: {}, sort: { field: 'state', dir: 'desc' }, group: null, fields: [] },
];

const WIDE = 24;
/** Wide enough for the planner's longest reason, measured on the live host: "transport 'http'
 *  already serves many clients" is 44 characters. A truncated reason is the one string whose whole
 *  job is to explain why a server is not hosted. */
const REASON = 46;
/** Wide enough for `not running`, which three cells print when a child is down. */
const NARROW = 13;

/** A number field that prints an absence rather than a zero. `0` sessions on a server that has
 *  never started is not the same fact as `0` on one that has, and the caller decides which it is. */
function countText(value: number | undefined, running: boolean): string {
  if (!running) return S.notRunning;
  return value === undefined ? S.notRunning : String(value);
}

function ServerStrip({ row, selected, onOpen }: { row: McpRow; selected: boolean; onOpen: () => void }) {
  const live = hosted(row.server);
  const running = live !== null && live.hosted;

  return (
    <Strip
      edge={stateEdge(row.state)}
      edgeLabel={stateLabel(row.state)}
      cocked={row.state === 'idle'}
      selected={selected}
      onOpen={onOpen}
      ariaLabel={`${row.name} ${stateLabel(row.state)}`}
    >
      <StripField w={WIDE} label={S.name} value={row.name} mono={false} />
      <StripField w={NARROW} label={S.state} value={stateLabel(row.state)} mono={false} />
      {row.server.eligible ? (
        <>
          <StripField w={NARROW} label={S.pid} value={live?.pid === undefined ? S.notRunning : String(live.pid)} />
          <StripField w={NARROW} label={S.sessions} value={countText(live?.sessions, running)} />
          <StripField w={NARROW} label={S.streams} value={countText(live?.streams, running)} />
          <StripField w={NARROW} label={S.restarts} value={String(live?.restarts ?? 0)} />
        </>
      ) : (
        <StripField w={REASON} label={S.reason} value={row.server.reason} mono={false} />
      )}
    </Strip>
  );
}

/** The four host limits, read-only with their provenance. A knob the running daemon does not carry
 *  prints its name with no value: the default lives in the daemon's own enum, and this page is not
 *  a second place for it. */
function HostLimits({ heads }: { heads: readonly { key: string; knob: { value: string | number | boolean | null; provenance: string; hot: boolean } | null }[] }) {
  return (
    <section className="myx-mcp-section">
      <h2 className="myx-mcp-section-title">{S.limits}</h2>
      {heads.map(({ key, knob }) => (
        <div key={key}>
          <FieldBox
            label={key}
            value={knob === null || knob.value === null ? '' : String(knob.value)}
            provenance={knob === null ? 'default' : (knob.provenance as never)}
            hot={knob?.hot ?? false}
          />
          <p className="myx-mcp-note">
            {knob === null ? 'not carried by this daemon' : dispositionText(knob.hot)}
          </p>
        </div>
      ))}
    </section>
  );
}

export function McpPage() {
  const views = useViews(PAGE_ID, DEFAULT_VIEWS);
  const active = views.active;
  const mcp = useMcp((state) => state);
  const config = useConfig((state) => state);

  useEffect(() => startMcpPolling(POLL_MS), []);
  useEffect(() => {
    const id = setInterval(() => { void fetchConfig(); }, POLL_MS * 3);
    void fetchConfig();
    return () => clearInterval(id);
  }, []);

  const [openKey, setOpenKey] = useState<string | null>(null);
  const toggle = (key: string) => setOpenKey((current) => (current === key ? null : key));

  const payload = mcp.data;
  const groups = arrangeServers(payload, active);
  const opened = groups.flatMap((group) => group.rows).find((row) => row.name === openKey) ?? null;
  const limits = hostLimits(config.data === null ? [] : knobDispositions(config.data));

  return (
    <div className="myx-mcp">
      <header className="myx-mcp-head">
        <h1 className="myx-mcp-title">{S.title}</h1>
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
      </header>

      {mcp.error === null ? null : <ErrorNote message={mcp.error} />}
      {payload === null && mcp.error === null ? <SkeletonRows rows={3} cols={6} /> : null}

      <div className="myx-mcp-body">
        <div className="myx-mcp-bays">
          {payload !== null && !payload.hosting ? (
            <Empty text={EMPTIES.hostingOff.text} source={EMPTIES.hostingOff.source} />
          ) : payload !== null && Object.keys(payload.servers).length === 0 ? (
            <Empty text={EMPTIES.noServers.text} source={EMPTIES.noServers.source} />
          ) : (
            groups.map((group) => (
              <Bay key={group.key === '' ? S.bay : group.key} label={group.key === '' ? S.bay : group.key} count={group.rows.length}>
                {group.rows.map((row) => (
                  <ServerStrip
                    key={row.name}
                    row={row}
                    selected={openKey === row.name}
                    onOpen={() => toggle(row.name)}
                  />
                ))}
              </Bay>
            ))
          )}

          {/* There is no restart route. Printed as the honest empty the contract asks for rather
              than a control that would 404, or worse, speak JSON-RPC at the transport endpoint. */}
          <Empty text="restart not built" source={MCP_RESTART.pending} />
        </div>

        <aside className="myx-mcp-detail" aria-label={S.detail}>
          {opened === null ? (
            <Empty text={EMPTIES.noOpened.text} source={EMPTIES.noOpened.source} />
          ) : (
            <section className="myx-mcp-section">
              <div className="myx-mcp-head">
                <HolderEdge state={stateEdge(opened.state)} label={stateLabel(opened.state)} />
                <span className="myx-mcp-note">{opened.name}</span>
              </div>
              <div className="myx-mcp-head">
                <Strip edge={stateEdge(opened.state)} edgeLabel={stateLabel(opened.state)} ariaLabel={opened.name}>
                  <StripField w={NARROW} label={S.pid} value={hosted(opened.server)?.pid === undefined ? S.none : String(hosted(opened.server)?.pid)} />
                  <StripField w={NARROW} label={S.restarts} value={String(hosted(opened.server)?.restarts ?? 0)} />
                </Strip>
              </div>
              <p className="myx-mcp-note">
                {opened.server.eligible ? (hosted(opened.server)?.last_error ?? S.none) : opened.server.reason}
              </p>
            </section>
          )}

          <HostLimits heads={limits} />
        </aside>
      </div>
    </div>
  );
}

export default McpPage;
