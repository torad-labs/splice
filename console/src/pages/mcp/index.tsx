// The MCP page: the shared host's servers as a rack of strips.
//
// Two things it refuses to do. It never offers a restart control, because there is no restart
// route — a console that POSTed at /mcp/{name} would be speaking JSON-RPC initialize into the
// protocol endpoint, not restarting a child. And it never restates the four host limits: they are
// runtime knobs, so they are read from the config payload with their provenance like every other
// knob, and a knob the running daemon does not carry shows its name with no value rather than a
// default this page invented.
import { useEffect, useState } from 'react';
import { fetchConfig, knobDispositions, useConfig } from '@entities/config';
import { MCP_RESTART, startMcpPolling, useMcp } from '@entities/mcp';
import type { McpRow } from '@entities/mcp';
import { useViews, ViewTabs } from '@features/views';
import type { View } from '@features/views';
import { Bay, Empty, FieldBox, HolderEdge, Strip, StripField } from '@shared/ui';
import { Blank, Fault } from '@shared/controls';
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
/** Wide enough for the widest number these columns carry. It was measured at `not running`, which
 *  three cells printed before the rack cells moved to the absence glyph (m1 design review B8), so
 *  every column here is now wider than anything it prints — clipping is the failure mode, not
 *  slack. */
const NARROW = 13;
/** ---- THE REASON SPANS THE FOUR NARROW TRACKS, AND IT IS DERIVED RATHER THAN CHOSEN (M1-73) --
 *  It was 46, "wide enough for the planner's longest reason", measured on the live host. That is
 *  a good reason for the number to be at least 46 and it is not the number the grid needs.
 *  THE GRID BELONGS TO THE RACK, NOT THE ROW, and on this page that is load-bearing rather than
 *  stylistic, because `.myx-mcp-bays .myx-sfield { flex: 1 1 auto }` (mcp.css:56, M1-39's
 *  strip-fills-its-bay rule carried here by M1-44) turns every declared ch into a SHARE of the
 *  leftover -- and flex-grow: 1 shares it EQUALLY PER CELL. So the two row shapes on this page
 *  got different first tracks: hosted is WIDE|NARROW x4 (76ch over 5 cells) and barred is
 *  WIDE|REASON (70ch over 2 cells), and the first cell renders 24ch + slack/5 against
 *  24ch + slack/2 -- a measured 87px apart, x 488 against x 575, on rows that both declare
 *  w={WIDE}.
 *  THE FIX IS TO MAKE EVERY ROW SHAPE DECLARE THE SAME TOTAL. A row maps onto a SUBSET of the
 *  rack's tracks and empties the ones it has nothing for, so a cell's share is the same in every
 *  row by construction and the declared grid is the rendered grid. WIDE + NARROW*4 = 76ch is the
 *  rack; the reason row spans the four narrow tracks, so it is NARROW * 4 and not a number. */
const REASON = NARROW * 4;

/** A number field that prints an absence rather than a zero. `0` sessions on a server that has
 *  never started is not the same fact as `0` on one that has, and the caller decides which it is.
 *  The absence is the glyph (m1 design review B8): the word `not running` said the same thing the
 *  holder edge already prints as `not started`, in prose, in every one of the three cells. */
function countText(value: number | undefined, running: boolean): string {
  if (!running) return S.absent;
  return value === undefined ? S.absent : String(value);
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
      {/* The state field is gone: the holder edge above prints the identical word, 8 px away, on
          every strip in the rack (m1 design review B10). The edge is where a state belongs. */}
      {row.server.eligible ? (
        <>
          <StripField w={NARROW} label={S.pid} value={live?.pid === undefined ? S.absent : String(live.pid)} />
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
          {/* the box prints the knob's own restart verdict; the note speaks only for a knob the
              daemon does not carry, which has no verdict to print */}
          {knob === null ? <p className="myx-mcp-note">not carried by this daemon</p> : null}
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
      <header className="myx-page-head">
        <h1 className="myx-page-title">{S.title}</h1>
        <ViewTabs pageId={PAGE_ID} defaults={DEFAULT_VIEWS} />
      </header>

      {mcp.error === null ? null : <Fault message={mcp.error} />}
      {payload === null && mcp.error === null ? <Blank strips={3} /> : null}

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
                  {/* THE RACK'S FIRST TRACK, DRAWN EMPTY (M1-73). This row is pid|restarts and has
                      no name to print -- the opened server's name is on the line above it -- so it
                      began at the SECOND track and its first field ended NARROW ch from the left,
                      where every row above it ends WIDE (24). Measured: the first field edge spans
                      87px across 18 strips. THE GRID BELONGS TO THE RACK AND NOT TO THE ROW: a row
                      shape maps onto a SUBSET of the rack's tracks rather than declaring widths of
                      its own, so a track this row has nothing to put in is drawn empty rather than
                      skipped. That is what the comp of record does -- M1-12 measured its rules
                      continuing below the last strip at a 31-32px pitch, a grid that exists
                      independently of what is in it and keeps existing where there is nothing. */}
                  <StripField w={WIDE} value="" />
                  <StripField w={NARROW} label={S.pid} value={hosted(opened.server)?.pid === undefined ? S.absent : String(hosted(opened.server)?.pid)} />
                  <StripField w={NARROW} label={S.restarts} value={String(hosted(opened.server)?.restarts ?? 0)} />
                </Strip>
              </div>
              <p className="myx-mcp-note">
                {opened.server.eligible ? (hosted(opened.server)?.last_error ?? S.absent) : opened.server.reason}
              </p>
              {/* There is no restart route. Printed where a restart control would stand, as the
                  honest empty the contract asks for, rather than a control that would 404 or speak
                  JSON-RPC at the transport endpoint. It used to sit under the servers rack on every
                  visit, a permanent strip about a control nobody had reached for. */}
              <Empty text="restart not built" source={MCP_RESTART.pending} />
            </section>
          )}

          <HostLimits heads={limits} />
        </aside>
      </div>
    </div>
  );
}

export default McpPage;
