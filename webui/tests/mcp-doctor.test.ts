// WALLS for the MCP and doctor row. The two that matter most:
//
//   - `a leaked credential is refused, not rendered`. The doctor report walks the machine and can
//     carry a token it should have masked. A page that printed it into a strip would BE the leak,
//     so the gate is asserted on the payload the page actually gates on.
//   - `the playground writes no body anywhere`. "never recorded" is a promise the daemon keeps and
//     the console must not break. The reducer is the only holder of a request or a response, and
//     this proves it by running a full cycle against spied storage.
//
// A `.ts` test cannot hold JSX (TS1161), so elements are built with React.createElement and
// asserted against renderToStaticMarkup's string.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { checkFix, checkSection, isRedacted, leaksIn, leaksInText, upgradeVerdict } from '../src/entities/doctor';
import type { DoctorCheck, DoctorPayload } from '../src/entities/doctor';
import { MCP_HOST_KNOBS, MCP_RESTART, serverRows, upText } from '../src/entities/mcp';
import type { McpPayload } from '../src/entities/mcp';
import { budgetFor, budgetText, NO_BUDGET } from '../src/entities/budget';
import { canTest, desktopText, webhookText } from '../src/entities/alert';
import type { AlertSettings } from '../src/entities/alert';
import { CheckStrip } from '../src/pages/doctor';
import {
  EMPTIES as DOCTOR_EMPTIES,
  attentionCount,
  canSend,
  groupChecks,
  playgroundNext,
  statusEdge,
  wantsAttention,
  IDLE_PLAYGROUND,
} from '../src/pages/doctor/model';
import { EMPTIES as MCP_EMPTIES, arrangeServers, hostLimits, stateEdge, stateLabel } from '../src/pages/mcp/model';
import { dispositions as mcpDispositions } from '../src/pages/mcp/coverage';
import { dispositions as doctorDispositions } from '../src/pages/doctor/coverage';
import { Empty } from '../src/shared/ui';

const h = React.createElement;
const render = (el: React.ReactElement): string => renderToStaticMarkup(el);

/** The daemon's own separator: a space, U+2014, then " fix: " (DoctorReportShape.kt:59). */
const SEP = ` ${String.fromCharCode(0x2014)} fix: `;

function check(id: string, status: DoctorCheck['status'], detail: string): DoctorCheck {
  return { id, status, detail };
}

describe('the doctor report is gated on redaction', () => {
  function report(over: Partial<DoctorPayload> = {}): DoctorPayload {
    return {
      schema_version: 1,
      generated_at: '2026-09-18T00:00:00Z',
      splice: { version: '0.4.0' },
      claude_code: { version: '2.1.257' },
      os: { name: 'Linux', version: '6.17', arch: 'x86_64' },
      jvm: { version: '21', vendor: 'x' },
      topology: {},
      checks: [],
      accounts: {},
      perf: {},
      ...over,
    };
  }

  test('a clean report passes', () => {
    expect(isRedacted(report())).toBe(true);
    expect(leaksIn(report())).toEqual([]);
  });

  test('a bearer token that survived the CLI pass is found, by path and never by value', () => {
    const leaks = leaksIn(report({ logs: ['Authorization: Bearer sk-abcdefghijklmnop'] }));
    expect(leaks.map((leak) => leak.kind)).toContain('bearer');
    expect(leaks[0]?.where).toBe('logs[0]');
    // The reporter names WHERE, never what: a leak report that echoed the match would be the leak.
    expect(JSON.stringify(leaks)).not.toContain('sk-abcdefghijklmnop');
  });

  test('each shape the CLI masks is one this check also sees', () => {
    expect(leaksInText('eyJhbGciOi.eyJzdWIiOi.SflKxwRJSM')).toContain('jwt');
    expect(leaksInText('api_key = "abc123"')).toContain('key-value');
    expect(leaksInText('sk-abcdefghijklmnop')).toContain('provider-key');
    expect(leaksInText('someone@example.com')).toContain('email');
  });

  test('an empty array is not a pass: the gate must read the predicate, not the list', () => {
    // The trap this pins: `if (leaksIn(x))` is truthy for a clean report too, because an empty
    // array is truthy. A page gating on the array would refuse to render every clean report.
    expect([]).toBeTruthy();
    expect(isRedacted(report())).toBe(true);
  });
});

describe('every failing check carries its fix', () => {
  const failing = check('daemon/port', 'fail', `port 3096 is held${SEP}splice doctor --json`);
  const plain = check('env/path', 'info', 'claude on PATH resolves to the versioned binary');

  test('the remedy is the tail after the daemon separator', () => {
    expect(checkFix(failing)).toBe('splice doctor --json');
  });

  test('a check with no remedy offers none, which is null and not an empty command', () => {
    expect(checkFix(plain)).toBeNull();
    expect(checkFix(plain)).not.toBe('');
  });

  test('the section is the id before the slash', () => {
    expect(checkSection(failing)).toBe('daemon');
    expect(checkSection(check('port', 'ok', 'x'))).toBe('port');
  });

  test('the strip prints the check id, its status and its fix', () => {
    const out = render(h(CheckStrip, { check: failing, selected: false, onOpen: () => undefined }));
    expect(out).toContain('daemon/port');
    expect(out).toContain('>fail<');
    expect(out).toContain('splice doctor --json');
    expect(out).toContain('myx-edge-red');
  });

  test('a check with no remedy says so rather than printing a blank cell', () => {
    const out = render(h(CheckStrip, { check: plain, selected: false, onOpen: () => undefined }));
    expect(out).toContain('no fix offered');
  });

  test('warn and fail cock the strip; ok and info do not', () => {
    expect(wantsAttention('fail')).toBe(true);
    expect(wantsAttention('warn')).toBe(true);
    expect(wantsAttention('ok')).toBe(false);
    expect(wantsAttention('info')).toBe(false);
  });

  test('the status edges never paint a statement green', () => {
    expect(statusEdge('ok')).toBe('green');
    expect(statusEdge('info')).toBe('grey');
    expect(statusEdge('warn')).toBe('amber');
    expect(statusEdge('fail')).toBe('red');
  });

  test('attention first puts the worst section at the top, by section keeps the alphabet', () => {
    const checks = [
      check('alpha/one', 'ok', 'fine'),
      check('zeta/two', 'fail', `broken${SEP}splice restart`),
    ];
    const attention = groupChecks(checks, { sort: { field: 'status' } });
    const alpha = groupChecks(checks, { sort: null });
    expect(attention[0]?.key).toBe('zeta');
    expect(alpha[0]?.key).toBe('alpha');
    expect(attentionCount(checks)).toBe(1);
  });
});

describe('the playground never persists a body', () => {
  const writes: string[] = [];

  afterEach(() => {
    writes.length = 0;
    vi.unstubAllGlobals();
  });

  function spyStorage() {
    vi.stubGlobal('localStorage', {
      getItem: () => null,
      setItem: (key: string) => { writes.push(`local:${key}`); },
      removeItem: () => undefined,
    });
    vi.stubGlobal('sessionStorage', {
      getItem: () => null,
      setItem: (key: string) => { writes.push(`session:${key}`); },
      removeItem: () => undefined,
    });
  }

  test('a full send cycle writes nothing to storage and holds the bodies in one state', () => {
    spyStorage();
    let state = IDLE_PLAYGROUND;
    state = playgroundNext(state, { kind: 'head', value: 'claudex' });
    state = playgroundNext(state, { kind: 'prompt', value: 'say hi' });
    expect(canSend(state)).toBe(true);
    state = playgroundNext(state, { kind: 'send' });
    expect(state.step).toBe('sending');
    // The send drops the previous run's bodies at the only moment a new one begins: a console that
    // kept them would be a body store the operator never asked for.
    expect(state.request).toBeNull();
    expect(state.response).toBeNull();
    state = playgroundNext(state, { kind: 'answered', request: { model: 'x' }, response: { text: 'hi' } });

    expect(state.step).toBe('answered');
    expect(state.response).toEqual({ text: 'hi' });
    expect(writes).toEqual([]);
  });

  test('reset leaves no residue behind', () => {
    const answered = playgroundNext(IDLE_PLAYGROUND, {
      kind: 'answered', request: { secret: 'x' }, response: { text: 'hi' },
    });
    expect(playgroundNext(answered, { kind: 'reset' })).toEqual(IDLE_PLAYGROUND);
  });

  test('a pending route clears the bodies and names its row, so nothing is left on screen', () => {
    const answered = playgroundNext(IDLE_PLAYGROUND, { kind: 'answered', request: { a: 1 }, response: { b: 2 } });
    const pending = playgroundNext(answered, { kind: 'pending', row: 'V4-133' });
    expect(pending.request).toBeNull();
    expect(pending.response).toBeNull();
    expect(pending.note).toBe('V4-133');
  });

  test('a send with no head or no prompt does nothing at all', () => {
    expect(canSend(IDLE_PLAYGROUND)).toBe(false);
    expect(playgroundNext(IDLE_PLAYGROUND, { kind: 'send' })).toBe(IDLE_PLAYGROUND);
  });
});

describe('pending routes render an empty naming their row', () => {
  test('the doctor empties name V4-127, V4-74 and V4-133', () => {
    expect(render(h(Empty, DOCTOR_EMPTIES.noReport))).toContain('V4-127');
    expect(render(h(Empty, DOCTOR_EMPTIES.upgrade))).toContain('V4-127');
    expect(render(h(Empty, DOCTOR_EMPTIES.restart))).toContain('V4-74');
    expect(render(h(Empty, DOCTOR_EMPTIES.playground))).toContain('V4-133');
  });

  test('the mcp restart empty names the fact that there is no route, not a row', () => {
    // There is no restart route to wait for: /mcp/{name} is the JSON-RPC transport, not a control.
    // So the empty names the CLI, which is the only way to restart a hosted server today.
    expect(MCP_RESTART.pending).toBe('no route; CLI only');
    const out = render(h(Empty, { text: 'restart not built', source: MCP_RESTART.pending }));
    expect(out).toContain('restart not built');
    expect(out).toContain('CLI only');
  });

  test('the hosting-off and no-servers empties name their sources', () => {
    expect(render(h(Empty, MCP_EMPTIES.hostingOff))).toContain('mcp_hosting');
    expect(render(h(Empty, MCP_EMPTIES.noServers))).toContain('GET /api/mcp');
  });
});

describe('the mcp host', () => {
  const payload: McpPayload = {
    hosting: true,
    servers: {
      zebra: { eligible: true, hosted: true, pid: 42, sessions: 2, session_ids: ['a', 'b'], streams: 3, started_at: 1000, last_activity: 2000, restarts: 1 },
      alpha: { eligible: true, hosted: false, sessions: 0, session_ids: [], streams: 0, restarts: 0 },
      moot: { eligible: false, reason: 'excluded by mcp_hosting_exclude' },
    },
  };

  test('the three states are told apart, and idle is not a failure', () => {
    const states = serverRows(payload).map((row) => [row.name, row.state]);
    expect(states).toEqual([['alpha', 'idle'], ['moot', 'ineligible'], ['zebra', 'hosted']]);
    expect(stateLabel('idle')).toBe('not started');
  });

  test('an ineligible server is grey, not red: a decision is not a fault', () => {
    expect(stateEdge('ineligible')).toBe('grey');
    expect(stateEdge('hosted')).toBe('green');
  });

  test('rows are ordered by name, so the rack does not reshuffle between polls', () => {
    expect(serverRows(payload).map((row) => row.name)).toEqual(['alpha', 'moot', 'zebra']);
  });

  test('hosted first puts the loaded servers above the waiting ones', () => {
    const rows = arrangeServers(payload, { group: null, sort: { field: 'state', dir: 'desc' } })[0]?.rows;
    expect(rows?.map((row) => row.name)).toEqual(['zebra', 'alpha', 'moot']);
  });

  test('uptime is null for a server that never started, never a zero', () => {
    expect(upText(undefined, 5000)).toBeNull();
    expect(upText(1000, 4000)).toBe('up 3.0s');
  });

  test('the four host knobs are read from config, and a knob the daemon lacks has no value', () => {
    const limits = hostLimits([]);
    expect(limits.map((limit) => limit.key)).toEqual([...MCP_HOST_KNOBS]);
    expect(limits.every((limit) => limit.knob === null)).toBe(true);
  });
});

describe('budgets and alerts', () => {
  test('no budget is a state, never $0.00', () => {
    expect(budgetText(null)).toBe(NO_BUDGET);
    expect(budgetText({ head: 'a', daily_usd: null, action: 'warn' })).toBe(NO_BUDGET);
    expect(budgetText({ head: 'a', daily_usd: 4, action: 'warn' })).toBe('$4.00/day');
  });

  test('a head absent from the payload has no budget', () => {
    expect(budgetFor({ budgets: [] }, 'a')).toBeNull();
    expect(budgetFor(null, 'a')).toBeNull();
  });

  test('a webhook that is absent is no webhook, not an empty link', () => {
    const off: AlertSettings = { desktop: false, webhook_url: null };
    expect(webhookText(off)).toBe('no webhook');
    expect(desktopText(off)).toBe('desktop off');
    expect(canTest(off)).toBe(false);
  });

  test('a test send needs somewhere to send to', () => {
    expect(canTest({ desktop: true, webhook_url: null })).toBe(true);
    expect(canTest({ desktop: false, webhook_url: 'https://example.test/hook' })).toBe(true);
  });
});

describe('the upgrade verdict', () => {
  test('an unreachable registry is unknown, never up to date', () => {
    expect(upgradeVerdict({ installed: '0.4.0', latest: null, rollback_available: false })).toBe('unknown');
  });

  test('a matching version is current and a different one is behind', () => {
    expect(upgradeVerdict({ installed: '0.4.0', latest: '0.4.0', rollback_available: true })).toBe('current');
    expect(upgradeVerdict({ installed: '0.4.0', latest: '0.5.0', rollback_available: true })).toBe('behind');
  });
});

describe('the coverage manifests', () => {
  test('the seven routes of this row are disposed exactly once, across the two files', () => {
    const names = [...mcpDispositions, ...doctorDispositions].map((entry) => entry.name);
    expect([...names].sort()).toEqual([
      '/api/alerts',
      '/api/budgets',
      '/api/doctor',
      '/api/heads/{head}/capture',
      '/api/mcp',
      '/api/playground',
      '/api/upgrade',
    ]);
    expect(new Set(names).size).toBe(names.length); // no name carries two page dispositions
  });

  test('the one pending entry names the row that will replace it', () => {
    const pending = [...mcpDispositions, ...doctorDispositions].filter((entry) => entry.disposition === 'pending');
    expect(pending.map((entry) => entry.name)).toEqual(['/api/heads/{head}/capture']);
    expect(pending[0]?.where).toBe('V4-133');
  });
});
