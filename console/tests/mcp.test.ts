// The MCP page on the kit: servers by state, the hosted ones against the cap, sessions drawn per
// server, a not-running server's counts as absences, the host limits read from config, and no
// restart control where no restart route exists.
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import type { KnobDisposition } from '../src/entities/config';
import { MCP_HOST_KNOBS, serverRows, upText } from '../src/entities/mcp';
import type { McpPayload } from '../src/entities/mcp';
import { ABSENT } from '../src/shared/lib';
import { McpBoard } from '../src/pages/mcp';
import { fixtureMcp } from '../src/pages/mcp/fixtures/mcp';
import {
  arrangeServers, hostLimits, limitText, liveOf, MARK, maxServersOf, stateParts, stateText, TONE, totalsOf,
} from '../src/pages/mcp/model';
import { H, S } from '../src/pages/mcp/strings';

const h = createElement;
const render = (element: Parameters<typeof renderToStaticMarkup>[0]): string => renderToStaticMarkup(element);

/** The column names of one labelled table, and its body rows. */
function table(markup: string, label: string): { names: string[]; rows: string[] } {
  const match = new RegExp(`<table[^>]*aria-label="${label}"[^>]*>([\\s\\S]*?)</table>`).exec(markup);
  const [head, body] = (match?.[1] ?? '').split('</thead>');
  return {
    names: [...(head ?? '').matchAll(/<th scope="col"[^>]*>([^<]*)</g)].map((m) => m[1] ?? ''),
    rows: (body ?? '').split('<tr').slice(1),
  };
}

function knob(key: string, value: KnobDisposition['value'], hot = true): KnobDisposition {
  return { key, value, provenance: 'default', hot, defaultValue: value, overriddenBy: [] };
}

const payload: McpPayload = {
  hosting: true,
  servers: {
    zebra: { eligible: true, hosted: true, pid: 42, sessions: 2, session_ids: ['a', 'b'], streams: 3, started_at: 1000, last_activity: 2000, restarts: 1 },
    alpha: { eligible: true, hosted: false, sessions: 0, session_ids: [], streams: 0, restarts: 0 },
    moot: { eligible: false, reason: 'excluded by [daemon] mcp_hosting_exclude' },
  },
};

describe('a server earns one state', () => {
  test('the three states are told apart, and only hosted is green', () => {
    expect(serverRows(payload).map((row) => [row.name, row.state])).toEqual([['alpha', 'idle'], ['moot', 'ineligible'], ['zebra', 'hosted']]);
    expect([stateText('hosted'), stateText('idle'), stateText('ineligible')]).toEqual(['Hosted', 'Unused', 'Direct']);
    expect(TONE.hosted).toBe('ok');
    expect(MARK.hosted).toBe('ok');
  });

  test('an unused or direct server is neither warn nor danger: waiting and a planner decision are not faults', () => {
    for (const state of ['idle', 'ineligible'] as const) {
      expect(TONE[state]).toBe('neutral');
      expect(['ok', 'warn', 'danger']).not.toContain(MARK[state]);
    }
  });

  test('rows keep name order, and hosted first puts the loaded servers above the waiting ones', () => {
    expect(arrangeServers(payload, { group: null, sort: null }).map((row) => row.name)).toEqual(['alpha', 'moot', 'zebra']);
    expect(arrangeServers(payload, { group: null, sort: { field: 'state', dir: 'desc' } }).map((row) => row.name)).toEqual(['zebra', 'alpha', 'moot']);
  });

  test('a server that is not running has no live half, so its counts are absences, never zeros', () => {
    const rows = serverRows(payload);
    expect(rows.map((row) => liveOf(row)?.sessions ?? null)).toEqual([null, null, 2]);
    expect(upText(undefined, 5000)).toBeNull();
    expect(upText(1000, 4000)).toBe('up 3.0s');
  });
});

describe('the figures', () => {
  test('load is summed over the eligible servers, and the state split keeps a fixed order', () => {
    const totals = totalsOf(serverRows(payload));
    expect(totals).toEqual({ byState: { hosted: 1, idle: 1, ineligible: 1 }, sessions: 2, streams: 3, restarts: 1 });
    expect(stateParts(totals).map((part) => [part.key, part.value])).toEqual([['hosted', 1], ['idle', 1], ['ineligible', 1]]);
  });

  test('the hosted tile reads against the cap, and a full host is warn', () => {
    const full = render(h(McpBoard, { payload, limits: hostLimits([knob('mcpMaxServers', 1)]) }));
    expect(full).toContain('>of 1<');
    expect(full).toContain('myx-meter-warn');
    const room = render(h(McpBoard, { payload, limits: hostLimits([knob('mcpMaxServers', 8)]) }));
    expect(room).toContain('>of 8<');
    expect(room).not.toContain('myx-meter-warn');
  });

  test('with no cap carried there is no meter against it, rather than one against a guess', () => {
    expect(maxServersOf(hostLimits([]))).toBeNull();
    expect(render(h(McpBoard, { payload }))).not.toContain('>of ');
  });

  test('any restart tints its tile, because a restart is the only evidence a child died', () => {
    expect(render(h(McpBoard, { payload }))).toContain('myx-stat-warn');
    const calm: McpPayload = { hosting: true, servers: { alpha: { eligible: true, hosted: false, sessions: 0, session_ids: [], streams: 0, restarts: 0 } } };
    expect(render(h(McpBoard, { payload: calm }))).not.toContain('myx-stat-warn');
  });
});

describe('the servers table', () => {
  const markup = render(h(McpBoard, { payload: fixtureMcp }));
  const servers = table(markup, S.servers);

  test('names its columns once, in the head row, with one cell per column in every row', () => {
    expect(servers.names).toEqual([S.server, S.state, S.sessions, S.streams, S.restarts, S.lastCall]);
    expect(servers.rows).toHaveLength(Object.keys(fixtureMcp.servers).length);
    for (const row of servers.rows) expect([...row.matchAll(/<td/g)].length).toBe(servers.names.length);
  });

  test('sessions are drawn against the busiest server, and a server not running draws nothing', () => {
    const busiest = servers.rows.find((row) => row.includes('>context7<')) ?? '';
    expect(busiest).toContain('aria-valuenow="100"');
    const unused = servers.rows.find((row) => row.includes('>memory<')) ?? '';
    expect(unused).not.toContain('role="meter"');
    // the sessions and streams cells print the absence; restarts stays a count, and 0 is its fact
    const cells = unused.split('<td').slice(1).map((cell) => cell.replace(/<[^>]*>/g, '').replace(/^[^>]*>/, ''));
    const at = (name: string) => cells[servers.names.indexOf(name)];
    expect([at(S.sessions), at(S.streams), at(S.restarts)]).toEqual([ABSENT, ABSENT, '0']);
  });

  test('a direct server reads as a state, and the planner\'s reason waits in its detail', () => {
    const direct = servers.rows.find((row) => row.includes('>linear<')) ?? '';
    expect(direct).toContain(`>${S.stateName.ineligible}<`);
    expect(direct).not.toContain('already serves many clients');
  });

  test('a server with a last error takes the warn tint', () => {
    const errored = Object.values(fixtureMcp.servers).filter((server) => server.eligible && server.last_error !== undefined).length;
    expect(errored).toBeGreaterThan(0);
    expect((markup.match(/myx-dt-tone-warn/g) ?? []).length).toBe(errored);
  });

  test('no restart control stands where no restart route exists, and the respawn rule is behind an info mark', () => {
    // /mcp/{name} is the JSON-RPC transport and no CLI command restarts one server; HostedServer.spawn
    // respawns on the next call and backs off 5-60 s in a crash loop.
    expect(markup).not.toMatch(/<button[^>]*>[^<]*[Rr]estart/);
    expect(markup).toContain(`aria-label="${S.aboutRestarts}"`);
    expect(H.restarts).toContain('next call');
    expect(H.restarts).not.toContain('CLI');
  });

  test('a fixture-fed board carries the capture marker with the fixture name', () => {
    expect(render(h(McpBoard, { payload: fixtureMcp, sample: 'mcp' }))).toContain('data-sample="mcp"');
    expect(markup).not.toContain('data-sample');
  });
});

describe('the host limits', () => {
  test('the four knobs are read from config, and a knob the daemon lacks says so with no value', () => {
    const limits = hostLimits([knob('mcpIdleTimeoutMs', 1_800_000)]);
    expect(limits.map((limit) => limit.key)).toEqual([...MCP_HOST_KNOBS]);
    const markup = render(h(McpBoard, { payload, limits }));
    const rows = table(markup, S.limits).rows;
    expect(rows).toHaveLength(4);
    expect(rows.filter((row) => row.includes(`>${S.notCarried}<`))).toHaveLength(3);
    expect(markup).toContain('href="#/settings"');
  });

  test('a limit reads in its largest units, a count as a number, and an unset knob as an absence', () => {
    expect(limitText(knob('mcpIdleTimeoutMs', 1_800_000))).toBe('30 min');
    expect(limitText(knob('mcpRequestTimeoutMs', 500))).toBe('500 ms');
    expect(limitText(knob('mcpMaxServers', 12))).toBe('12');
    expect(limitText(knob('mcpMaxServers', null))).toBe(ABSENT);
  });

  test('no config read yet means no limits section, not four rows claiming nothing is carried', () => {
    expect(render(h(McpBoard, { payload }))).not.toContain(`aria-label="${S.limits}"`);
  });
});

describe('the empties', () => {
  test('hosting off is one line with its fix behind a mark and the settings link as its action', () => {
    const markup = render(h(McpBoard, { payload: { hosting: false, servers: {} } }));
    expect(markup).toContain(`>${S.hostingOff}<`);
    expect(markup).toContain('mcp_hosting = true under [daemon]');
    expect(markup).toContain(`>${S.openSettings}<`);
    expect(markup).not.toContain('myx-stat');
    expect(markup).not.toContain('<table');
  });

  test('no servers is one line, and no empty names a route', () => {
    const markup = render(h(McpBoard, { payload: { hosting: true, servers: {} } }));
    expect(markup).toContain(`>${S.noServers}<`);
    expect(markup).not.toContain('myx-stat');
    for (const help of Object.values(H)) expect(help).not.toContain('/api/');
  });
});
