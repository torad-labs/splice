// Ported from the old console's mcp.test.ts: only the assertions over lib/mcp (serverRows, upText,
// MCP_HOST_KNOBS). The rest of that file renders McpBoard or tests the page's own model (stateText,
// TONE, MARK, arrangeServers, liveOf, totalsOf, stateParts, hostLimits, limitText, maxServersOf) and
// is not ported.
import { describe, expect, test } from 'vitest';
import { MCP_HOST_KNOBS, serverRows, serverState, upText } from '../src/lib/mcp';
import type { McpPayload } from '../src/types/mcp';

const payload: McpPayload = {
  hosting: true,
  servers: {
    zebra: { eligible: true, hosted: true, pid: 42, sessions: 2, session_ids: ['a', 'b'], streams: 3, started_at: 1000, last_activity: 2000, restarts: 1 },
    alpha: { eligible: true, hosted: false, sessions: 0, session_ids: [], streams: 0, restarts: 0 },
    moot: { eligible: false, reason: 'excluded by [daemon] mcp_hosting_exclude' },
  },
};

describe('a server earns one state', () => {
  test('the three states are told apart, in name order', () => {
    expect(serverRows(payload).map((row) => [row.name, row.state])).toEqual([['alpha', 'idle'], ['moot', 'ineligible'], ['zebra', 'hosted']]);
  });

  test('no payload reads as no rows', () => {
    expect(serverRows(null)).toEqual([]);
    for (const row of serverRows(payload)) expect(row.state).toBe(serverState(row.server));
  });

  test('a server that is not running has no uptime, never a zero', () => {
    expect(upText(undefined, 5000)).toBeNull();
    expect(upText(1000, 4000)).toBe('up 3.0s');
  });
});

describe('the host limits', () => {
  test('the four knob wire keys, in the daemon order', () => {
    expect([...MCP_HOST_KNOBS]).toEqual(['mcpIdleTimeoutMs', 'mcpMaxServers', 'mcpRequestTimeoutMs', 'mcpInitializeTimeoutMs']);
  });
});
