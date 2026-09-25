// What the MCP page computes from GET /api/mcp and the config payload, as shapes rather than
// sentences: each server's state as a tone and a mark, the split of servers by state, the totals the
// figures lead with, and the four host limits read out of config.
import { MCP_HOST_KNOBS, serverRows } from '@entities/mcp';
import type { McpHostedServer, McpPayload, McpRow, McpState } from '@entities/mcp';
import type { KnobDisposition } from '@entities/config';
import { KNOB_META, unitText } from '@widgets/knob-form';
import { ABSENT, fmtInt } from '@shared/lib';
import type { BarPart, Mark, Tone } from '@shared/ui';
import { S } from './strings';

/** A state's badge tone and chart mark. Only a hosted server is green. An unused one is waiting and
 *  a direct one was declined by the planner; neither is a fault, so neither is warn or danger. */
export const TONE: Record<McpState, Tone> = { hosted: 'ok', idle: 'neutral', ineligible: 'neutral' };
export const MARK: Record<McpState, Mark> = { hosted: 'ok', idle: 'series-2', ineligible: 'series-3' };

/** The order states are drawn and counted in, hosted first. */
const STATES: readonly McpState[] = ['hosted', 'idle', 'ineligible'];

export function stateText(state: McpState): string {
  return S.stateName[state];
}

/** A hosted server as the live half of its row, or null when it is not the hosted variant. */
export function hosted(server: McpRow['server']): McpHostedServer | null {
  return server.eligible ? server : null;
}

/** The live half of a server that is running now, or null. A count on a server that is not running
 *  is an absence, never a zero: `0` sessions on a server that never started is not the same fact as
 *  `0` on one that has. */
export function liveOf(row: McpRow): McpHostedServer | null {
  const live = hosted(row.server);
  return live !== null && live.hosted ? live : null;
}

/**
 * The rows for one saved view.
 *
 * `By name` keeps name order. `Hosted first` puts the servers actually carrying sessions above the
 * waiting ones, which is the order an operator scanning for load wants. Both keep name order inside
 * a state so the table does not reshuffle between polls.
 */
export function arrangeServers(payload: McpPayload | null, view: { group: string | null; sort: { field: string; dir: 'asc' | 'desc' } | null }): McpRow[] {
  const rows = serverRows(payload);
  if (view.sort?.field !== 'state') return rows;
  const rank: Record<McpState, number> = { hosted: 2, idle: 1, ineligible: 0 };
  return [...rows].sort((l, r) => rank[r.state] - rank[l.state] || l.name.localeCompare(r.name));
}

/** What the figures lead with. Sessions, streams and restarts are summed over the eligible servers;
 *  a direct server has none of them, because splice does not run it. */
export interface McpTotals {
  byState: Record<McpState, number>;
  sessions: number;
  streams: number;
  restarts: number;
}

export function totalsOf(rows: readonly McpRow[]): McpTotals {
  const byState: Record<McpState, number> = { hosted: 0, idle: 0, ineligible: 0 };
  let sessions = 0;
  let streams = 0;
  let restarts = 0;
  for (const row of rows) {
    byState[row.state] += 1;
    const live = hosted(row.server);
    if (live === null) continue;
    sessions += live.sessions;
    streams += live.streams;
    restarts += live.restarts;
  }
  return { byState, sessions, streams, restarts };
}

/** The servers by state as bar parts, in the fixed state order so the colours never swap places. */
export function stateParts(totals: McpTotals): BarPart[] {
  return STATES.map((state) => ({ key: state, label: stateText(state), value: totals.byState[state], mark: MARK[state] }));
}

/**
 * The four host limits, read from the config payload rather than restated.
 *
 * They are runtime knobs (`mcpIdleTimeoutMs` and friends, Knob.kt:331-358). A knob the running
 * daemon does not carry is absent from `effective`, and the page then shows the knob's name with no
 * value rather than a default this console made up: the default lives in the daemon's enum.
 */
export interface HostLimit {
  key: string;
  knob: KnobDisposition | null;
}

export function hostLimits(dispositions: readonly KnobDisposition[]): HostLimit[] {
  const byKey = new Map(dispositions.map((knob) => [knob.key, knob]));
  return MCP_HOST_KNOBS.map((key) => ({ key, knob: byKey.get(key) ?? null }));
}

/** A limit's value as the settings page reads it: a duration in its largest units, a count as a
 *  number, and an unset knob as an absence rather than a zero. */
export function limitText(knob: KnobDisposition): string {
  if (knob.value === null || knob.value === '') return ABSENT;
  if (typeof knob.value !== 'number') return String(knob.value);
  const { suffix, readable } = unitText(KNOB_META[knob.key]?.unit, knob.value);
  if (readable !== null) return readable;
  return suffix === null ? fmtInt(knob.value) : `${fmtInt(knob.value)} ${suffix}`;
}

/** The most servers the host will run, when the daemon carries the knob and it holds a number. */
export function maxServersOf(limits: readonly HostLimit[]): number | null {
  const value = limits.find((limit) => limit.key === 'mcpMaxServers')?.knob?.value;
  return typeof value === 'number' && value > 0 ? value : null;
}
