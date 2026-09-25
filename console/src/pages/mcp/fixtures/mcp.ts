// A sample MCP host for captures and tests, loaded only through `?fixture=mcp` in a DEV build. The
// ineligible reasons are the planner's own sentences (McpSharing.kt:112-137), so the detail reads
// as the daemon would write it.
import type { McpPayload } from '@entities/mcp';

const NOW = Date.now();
const MIN = 60_000;

export const fixtureMcp: McpPayload = {
  hosting: true,
  servers: {
    context7: {
      eligible: true, hosted: true, pid: 48211, sessions: 6, session_ids: ['s1', 's2', 's3', 's4', 's5', 's6'], streams: 4,
      started_at: NOW - 184 * MIN, last_activity: NOW - 0.4 * MIN, restarts: 0,
    },
    github: {
      eligible: true, hosted: true, pid: 48230, sessions: 4, session_ids: ['s1', 's2', 's3', 's4'], streams: 4,
      started_at: NOW - 41 * MIN, last_activity: NOW - 2 * MIN, restarts: 2, last_error: 'exited with status 1 after 3.2s',
    },
    'sequential-thinking': {
      eligible: true, hosted: true, pid: 48244, sessions: 2, session_ids: ['s2', 's5'], streams: 1,
      started_at: NOW - 97 * MIN, last_activity: NOW - 11 * MIN, restarts: 0,
    },
    memory: { eligible: true, hosted: false, sessions: 0, session_ids: [], streams: 0, restarts: 0 },
    'ast-grep': { eligible: true, hosted: false, sessions: 0, session_ids: [], streams: 0, restarts: 1 },
    linear: { eligible: false, reason: "transport 'http' already serves many clients" },
    serena: { eligible: false, reason: 'has a cwd (session-scoped)' },
    playwright: { eligible: false, reason: 'excluded by [daemon] mcp_hosting_exclude' },
  },
};
