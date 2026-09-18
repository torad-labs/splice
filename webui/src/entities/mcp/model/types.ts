// The shared MCP host, typed from the daemon that serves it: GET /api/mcp EXISTS
// (ControlServer.kt:161 -> McpHost.statusJson -> McpStatus.json). Eligibility comes straight from
// the planner, so the console shows the same reasons the materializer acted on
// (McpStatus.kt:1-3), and the live half is the state of each hosted child process.
import type { PendingRoute } from '@shared/api';

/** A server the planner will host. Everything past `eligible` is live state, and each live field is
 *  absent while the child is not running: a server that is eligible but not started reports
 *  `hosted: false`, no pid, no started_at and no last_activity, and the page prints "not started"
 *  rather than a zero. */
export interface McpHostedServer {
  eligible: true;
  hosted: boolean;
  pid?: number;
  /** Open client sessions right now. */
  sessions: number;
  /** Their ids, so a page can link a session row to the hosted server it rides. */
  session_ids: string[];
  /** Open server-to-client notification streams across those sessions. */
  streams: number;
  started_at?: number;
  last_activity?: number;
  /** Times the child was restarted. A COUNT, never a last-restart time: there is no route that
   *  restarts a server, and this counter is the only evidence a child died and came back. */
  restarts: number;
  last_error?: string;
}

/** A server the planner will NOT host, with the planner's own reason (an exclusion list entry, a
 *  dialect that cannot host, and so on). The reason is the daemon's sentence, reproduced as is. */
export interface McpIneligibleServer {
  eligible: false;
  reason: string;
}

export type McpServer = McpHostedServer | McpIneligibleServer;

export interface McpPayload {
  /** Whether shared MCP hosting is on at all ([daemon] mcp_hosting). Off means every server below
   *  is ineligible, and the page says so once instead of once per row. */
  hosting: boolean;
  /** Keyed by the server name the operator configured: it is the identity, not an index. */
  servers: Record<string, McpServer>;
}

/** The four host limits (idle timeout, max servers, request timeout, initialize timeout) are NOT in
 *  this payload: they are runtime knobs (ControlPlane.kt:135-146 reads Knob.MCP_IDLE_TIMEOUT_MS,
 *  MCP_MAX_SERVERS, MCP_REQUEST_TIMEOUT_MS, MCP_INITIALIZE_TIMEOUT_MS), so a page reads them from
 *  @entities/config with their provenance like every other knob. Restating them here as constants
 *  would be a second source for four values that can be overridden. */

/**
 * Restarting a hosted server, as FEATURES.md 4.11 would have it. There is NO route: measured
 * 2026-09-18, ControlServer.kt:162-164 are the MCP Streamable HTTP transport (POST/GET/DELETE on
 * /mcp/{name}), and FEATURES.md 2.8 reports restarts as the counter above. So the entity exposes
 * restart as this pending state and never calls one - a console that issued a POST at /mcp/{name}
 * would be speaking JSON-RPC initialize into the protocol endpoint, not restarting a child.
 */
export const MCP_RESTART: PendingRoute = { pending: 'no route; CLI only' };
