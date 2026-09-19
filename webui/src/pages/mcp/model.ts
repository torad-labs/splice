// The MCP page's pure half: how one hosted server reads, and how the four host knobs are read out
// of the config payload.
import { MCP_HOST_KNOBS, serverRows } from '@entities/mcp';
import type { McpHostedServer, McpPayload, McpRow, McpState } from '@entities/mcp';
import type { KnobDisposition } from '@entities/config';
import type { Edge } from '@shared/ui';

/** The holder edge for a server's state. An ineligible server is grey rather than red: the planner
 *  declining to host something is a decision, not a fault, and its reason is printed beside it. */
export function stateEdge(state: McpState): Edge {
  if (state === 'hosted') return 'green';
  if (state === 'idle') return 'amber';
  return 'grey';
}

/** The state's printed label. Every word is inside the contract's 6ch edge budget (CONTRACTS.md
 *  section 2): a holder edge that clips its own state is worse than a shorter state.
 *
 *  `unused` and not `idle`, because nothing has asked for this server yet and "idle" would sound
 *  like it had run and stopped. `barred` and not `ineligible`, because the planner declining to
 *  host something is a decision by rule, not a fault — and its reason is printed beside it. */
export function stateLabel(state: McpState): string {
  if (state === 'hosted') return 'hosted';
  if (state === 'idle') return 'unused';
  return 'barred';
}

/** A hosted server as the live half of its row, or null when it is not the hosted variant. */
export function hosted(server: McpRow['server']): McpHostedServer | null {
  return server.eligible ? server : null;
}

export interface McpGroup {
  key: string;
  rows: McpRow[];
}

/**
 * The bay layout for one saved view.
 *
 * `by name` is one rack in name order; `hosted first` puts the servers actually carrying sessions
 * above the ones waiting, which is the order an operator scanning for load wants. Both keep the
 * name order inside a group so the rack does not reshuffle between polls.
 */
export function arrangeServers(payload: McpPayload | null, view: { group: string | null; sort: { field: string; dir: 'asc' | 'desc' } | null }): McpGroup[] {
  const rows = serverRows(payload);
  if (view.sort?.field !== 'state') return [{ key: '', rows }];
  const rank: Record<McpState, number> = { hosted: 2, idle: 1, ineligible: 0 };
  return [{ key: '', rows: [...rows].sort((l, r) => rank[r.state] - rank[l.state] || l.name.localeCompare(r.name)) }];
}

/**
 * The four host limits, read from the config payload rather than restated.
 *
 * They are runtime knobs (`mcpIdleTimeoutMs` and friends, Knob.kt:331-358), so they carry the
 * provenance and hot/restart verdict of every other knob. A knob the running daemon does not carry
 * is simply absent from `effective`; the page then shows the knob's NAME with no value rather than
 * a default this console made up, because the default lives in the daemon's enum and nowhere else.
 */
export function hostLimits(dispositions: readonly KnobDisposition[]): { key: string; knob: KnobDisposition | null }[] {
  const byKey = new Map(dispositions.map((knob) => [knob.key, knob]));
  return MCP_HOST_KNOBS.map((key) => ({ key, knob: byKey.get(key) ?? null }));
}

/**
 * The page's honest empties, as data rather than inline JSX, so a test can assert each names its
 * source (CONTRACTS.md section 8).
 */
export const EMPTIES = {
  hostingOff: { text: 'shared hosting off', source: '[daemon] mcp_hosting' },
  noServers: { text: 'no mcp servers', source: 'GET /api/mcp' },
  noOpened: { text: 'no server opened', source: 'click a strip' },
} as const;
