// The MCP host's pure derivations: what state one server is in, and the four host knobs' wire keys.
import { fmtMs } from '@shared/lib';
import type { McpPayload, McpServer } from './types';

/**
 * The four host limits are runtime knobs, not payload fields (Knob.kt:331-358, under the wire keys
 * below). A page reads them from @entities/config with their provenance like every other knob;
 * restating their values here would be a second source for four numbers the operator can override.
 */
export const MCP_HOST_KNOBS = [
  'mcpIdleTimeoutMs',
  'mcpMaxServers',
  'mcpRequestTimeoutMs',
  'mcpInitializeTimeoutMs',
] as const;

export type McpHostKnob = (typeof MCP_HOST_KNOBS)[number];

/**
 * One server's state, from the planner's verdict and the child's own liveness.
 *
 * `idle` is not a failure: a server the planner WILL host but has not started yet is the normal
 * state of a server nothing has asked for. The page prints "not started" for it and never a zero
 * session count as if it had been running with nobody on it.
 */
export type McpState = 'hosted' | 'idle' | 'ineligible';

export function serverState(server: McpServer): McpState {
  if (!server.eligible) return 'ineligible';
  return server.hosted ? 'hosted' : 'idle';
}

export interface McpRow {
  name: string;
  server: McpServer;
  state: McpState;
}

/** The servers as rows, ordered by name. The payload is keyed by name because a name is the
 *  identity; an ordered array is what a rack renders, and sorting here keeps the order stable
 *  across polls rather than whatever the object's insertion order happens to be. */
export function serverRows(payload: McpPayload | null): McpRow[] {
  if (payload === null) return [];
  return Object.entries(payload.servers)
    .map(([name, server]) => ({ name, server, state: serverState(server) }))
    .sort((left, right) => left.name.localeCompare(right.name));
}

/**
 * How long a hosted child has been up, or null when it is not running. Null rather than "0s":
 * a server that never started has no uptime, and a zero would read as one that just did.
 */
export function upText(startedAt: number | undefined, nowMs: number): string | null {
  if (startedAt === undefined) return null;
  return `up ${fmtMs(Math.max(0, nowMs - startedAt))}`;
}
