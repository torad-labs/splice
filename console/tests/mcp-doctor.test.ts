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
import type { UpgradePayload } from '../src/entities/doctor';
import type { DoctorCheck, DoctorPayload } from '../src/entities/doctor';
import { MCP_HOST_KNOBS, serverRows, upText } from '../src/entities/mcp';
import type { McpPayload } from '../src/entities/mcp';
import { budgetFor, budgetText, NO_BUDGET } from '../src/entities/budget';
import { canTest, desktopText, webhookText } from '../src/entities/alert';
import type { AlertSettings } from '../src/entities/alert';
import { CheckStrip } from '../src/pages/doctor';
import {
  EMPTIES as DOCTOR_EMPTIES,
  attentionCount,
  canSend,
  collapseChecks,
  groupChecks,
  logsHeadOf,
  playgroundNext,
  statusEdge,
  wantsAttention,
  IDLE_PLAYGROUND,
} from '../src/pages/doctor/model';
import type { PlaygroundState } from '../src/pages/doctor/model';
import { EMPTIES as MCP_EMPTIES, RESPAWN_NOTE, arrangeServers, hostLimits, stateEdge, stateLabel } from '../src/pages/mcp/model';
import { dispositions as mcpDispositions } from '../src/pages/mcp/coverage';
import { dispositions as doctorDispositions } from '../src/pages/doctor/coverage';
import { Empty } from '../src/shared/ui';

const h = React.createElement;
const render = (el: React.ReactElement): string => renderToStaticMarkup(el);

/** The daemon's own separator: a space, U+2014, then " fix: " (DoctorReportShape.kt:59). */
const SEP = ` ${String.fromCharCode(0x2014)} fix: `;

/** One check as the rack's row. */
function rowOf(one: DoctorCheck) {
  const [row] = collapseChecks([one]);
  if (row === undefined) throw new Error(`no row for ${one.id}`);
  return row;
}

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
    const out = render(h(CheckStrip, { row: rowOf(failing), selected: false, onOpen: () => undefined }));
    expect(out).toContain('daemon/port');
    expect(out).toContain('>fail<');
    expect(out).toContain('splice doctor --json');
    expect(out).toContain('myx-edge-red');
  });

  test('a check with no remedy prints the absence glyph rather than a blank cell', () => {
    // The sentence `no fix offered` moved to the opened check's own note, where a Doctor fix's
    // paragraph belongs; the rack cell carries the absence glyph (m1 design review B8).
    const out = render(h(CheckStrip, { row: rowOf(plain), selected: false, onOpen: () => undefined }));
    expect(out).toContain('>–<');
    expect(out).not.toContain('no fix offered');
  });

  test('the same finding on several heads is one row that counts them', () => {
    // Live 2026-09-24: eleven `configuration/system-prompt:<head>` warnings with one fix filled
    // the first screen of the rack.
    const fix = 'set system_prompt_mode = "append"';
    const rows = collapseChecks([
      check('configuration/system-prompt:claudex', 'warn', `head 'claudex' replaces${SEP}${fix}`),
      check('configuration/system-prompt:bonsai', 'warn', `head 'bonsai' replaces${SEP}${fix}`),
      check('configuration/topology', 'ok', 'fine'),
    ]);
    expect(rows.map((row) => [row.label, row.members.length])).toEqual([
      ['configuration/system-prompt (2)', 2], ['configuration/topology', 1],
    ]);
    expect(rows[0]?.key).toBe('configuration/system-prompt:claudex');
  });

  test('checks whose fixes differ stay their own rows', () => {
    const rows = collapseChecks([
      check('configuration/local:a', 'warn', `down${SEP}start it`),
      check('configuration/local:b', 'fail', `down${SEP}start it`),
      check('configuration/wire-tap:a', 'warn', `on${SEP}remove overrides.wireTap from [heads.a]`),
      check('configuration/wire-tap:b', 'warn', `on${SEP}remove overrides.wireTap from [heads.b]`),
    ]);
    expect(rows).toHaveLength(4);
  });

  test('a splice logs remedy names the head whose log the page can open', () => {
    expect(logsHeadOf('splice logs --head claude-kimi --tail 50')).toBe('claude-kimi');
    expect(logsHeadOf('splice restart')).toBeNull();
    expect(logsHeadOf(null)).toBeNull();
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
    state = playgroundNext(state, { kind: 'answered', run: state.run, request: { model: 'x' }, response: { text: 'hi' } });

    expect(state.step).toBe('answered');
    expect(state.response).toEqual({ text: 'hi' });
    expect(writes).toEqual([]);
  });

  /** A run that has been sent, so the answer events below have a run to belong to. */
  function sent(): PlaygroundState {
    let state = playgroundNext(IDLE_PLAYGROUND, { kind: 'head', value: 'claudex' });
    state = playgroundNext(state, { kind: 'prompt', value: 'say hi' });
    return playgroundNext(state, { kind: 'send' });
  }

  test('reset leaves no residue behind', () => {
    const running = sent();
    const answered = playgroundNext(running, { kind: 'answered', run: running.run, request: { secret: 'x' }, response: { text: 'hi' } });
    const cleared = playgroundNext(answered, { kind: 'reset' });
    expect({ ...cleared, run: IDLE_PLAYGROUND.run }).toEqual(IDLE_PLAYGROUND);
  });

  // THE SEND IS A REAL REQUEST NOW (M4-03), so an answer can arrive after the operator moved on. A
  // reply that landed after `clear` would put back on screen the very body the operator just
  // dropped, which is the storing this reducer exists to refuse.
  test('an answer that lands after a reset is dropped, not resurrected', () => {
    const running = sent();
    const cleared = playgroundNext(running, { kind: 'reset' });
    const late = playgroundNext(cleared, { kind: 'answered', run: running.run, request: { a: 1 }, response: { b: 2 } });
    expect(late.request).toBeNull();
    expect(late.response).toBeNull();
    expect(late.step).toBe('idle');
  });

  test('an answer for an earlier run never lands on a later one', () => {
    const first = sent();
    const second = playgroundNext(playgroundNext(first, { kind: 'reset' }), { kind: 'head', value: 'claudex' });
    const again = playgroundNext(playgroundNext(second, { kind: 'prompt', value: 'again' }), { kind: 'send' });
    expect(again.run).not.toBe(first.run);
    const stale = playgroundNext(again, { kind: 'answered', run: first.run, request: { old: 1 }, response: { old: 2 } });
    expect(stale.step).toBe('sending');
    expect(stale.response).toBeNull();
    const failedLate = playgroundNext(again, { kind: 'failed', run: first.run, note: 'old failure' });
    expect(failedLate.note).toBeNull();
  });

  test('a refusal prints the daemon\'s sentence and holds no body', () => {
    const running = sent();
    const failed = playgroundNext(running, { kind: 'failed', run: running.run, note: 'unknown head: nope' });
    expect(failed.step).toBe('failed');
    expect(failed.note).toBe('unknown head: nope');
    expect(failed.request).toBeNull();
    expect(failed.response).toBeNull();
  });

  test('a send with no head or no prompt does nothing at all', () => {
    expect(canSend(IDLE_PLAYGROUND)).toBe(false);
    expect(playgroundNext(IDLE_PLAYGROUND, { kind: 'send' })).toBe(IDLE_PLAYGROUND);
  });
});

describe('pending routes render an empty naming their row', () => {
  // M4-07: GET /api/upgrade is served and the version strip reads it, so its `not built` empty is
  // gone (tests/doctor-gate.test.ts pins the absence).
  test('the doctor empties say what happened, never a row id or a route', () => {
    for (const empty of Object.values(DOCTOR_EMPTIES)) {
      expect(empty.source).not.toMatch(/V4-|\/api\//);
    }
    expect(render(h(Empty, DOCTOR_EMPTIES.noReport))).toContain('does not serve the doctor report');
  });

  // Body capture is served (/api/heads/{head}/capture, driven from the turns and logs drawers), so
  // the `body capture not built` empty the doctor still printed beside the restart was stale.
  test('body capture is a control on turns and logs, not an empty on the doctor', () => {
    expect(Object.keys(DOCTOR_EMPTIES)).not.toContain('capture');
  });

  // M4-03: POST /api/daemon/restart and POST /api/playground are served and the page drives both, so
  // the two empties that said they were not built are gone with the controls that replaced them.
  test('the restart and the playground are controls now, not empties', () => {
    expect(Object.keys(DOCTOR_EMPTIES)).not.toContain('restart');
    expect(Object.keys(DOCTOR_EMPTIES)).not.toContain('playground');
  });

  test('where a restart control would stand, the page says what the host does on its own', () => {
    // There is no restart route (/mcp/{name} is the JSON-RPC transport) and no CLI command restarts
    // one server, so the old `restart not built / no route; CLI only` sent the reader nowhere.
    // HostedServer.spawn respawns on the next call and backs off 5-60 s in a crash loop.
    expect(RESPAWN_NOTE).toContain('starts again on its next call');
    expect(RESPAWN_NOTE).not.toContain('CLI');
  });

  test('the hosting-off and no-servers empties say how to fill them, never a route', () => {
    expect(render(h(Empty, MCP_EMPTIES.hostingOff))).toContain('mcp_hosting = true under [daemon]');
    for (const empty of Object.values(MCP_EMPTIES)) expect(empty.source).not.toContain('/api/');
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
    // `unused` and not `not started`: the edge prints this word inside the contract's 6ch budget
    // (CONTRACTS.md section 2, m1 design review B10).
    expect(stateLabel('idle')).toBe('unused');
  });

  test('an ineligible server is grey, not red: a decision is not a fault', () => {
    // and its word says clients reach it themselves, not that something was refused
    expect(stateLabel('ineligible')).toBe('direct');
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
  // The payload ConsoleUpgradeStatus.json writes today: latest never checked, rollback measured.
  const payload = (over: Partial<UpgradePayload> = {}): UpgradePayload => ({
    installed: '0.4.0',
    latest: null,
    latest_basis: 'unavailable',
    latest_unavailable_reason: 'no upgrade check has succeeded on this daemon',
    rollback_target: null,
    rollback_basis: 'measured',
    rollback_unavailable_reason: null,
    checked_at_epoch_millis: null,
    ...over,
  });

  test('a check that never ran is unknown, never up to date', () => {
    expect(upgradeVerdict(payload())).toBe('unknown');
  });

  test('a check that looked and found nothing newer is current', () => {
    expect(upgradeVerdict(payload({ latest_basis: 'measured', latest: null }))).toBe('current');
  });

  test('a matching version is current and a different one is behind', () => {
    expect(upgradeVerdict(payload({ latest_basis: 'measured', latest: '0.4.0' }))).toBe('current');
    expect(upgradeVerdict(payload({ latest_basis: 'measured', latest: '0.5.0' }))).toBe('behind');
  });
});

describe('the coverage manifests', () => {
  test('the routes of this row are disposed exactly once, across the two files', () => {
    const names = [...mcpDispositions, ...doctorDispositions].map((entry) => entry.name);
    // This list is the pages' route INVENTORY and stays exact on purpose: a new fetch site with no
    // disposition should fail here by name. Four since M4-06. /api/heads/{head}/capture moved to the
    // turns page (M4-04: its request drawer's switch reads and writes it), and budgets, alerts and
    // the alerts test to the usage page (M4-06: it mounts those two panels); the coverage wall fails
    // if nothing declares them. (/api/alerts/test was added 2026-09-18, when M1-37's wire-check
    // found it fetched and disposed by nothing.)
    expect([...names].sort()).toEqual([
      '/api/doctor',
      '/api/mcp',
      '/api/playground',
      '/api/upgrade',
    ]);
    expect(new Set(names).size).toBe(names.length); // no name carries two page dispositions
  });

  // Was `the ONE pending entry`, pinning a set of size one. /api/alerts and /api/budgets were
  // disposed `editable` while control serves neither (grepped 2026-09-18: 0 occurrences of each),
  // so the count was one only because two entries were lying. Pinning the pending SET would make
  // this wall go red every time the daemon ships a route, which teaches the next reader to edit
  // the wall rather than read it — so the assertion is the property instead: a pending entry with
  // no row to point at is the defect, and the count is not. Since M4-06 the count is zero (every
  // route the two pages name is served, which the coverage wall's served check holds), so the
  // guard against a vacuous pass is a planted entry the same check must catch, not a count.
  test('every pending entry names the row that will replace it', () => {
    const rowless = (entries: readonly (typeof doctorDispositions)[number][]) =>
      entries.filter((entry) => entry.disposition === 'pending' && !/^V4-\d+$/.test(entry.where ?? ''));
    expect(rowless([...mcpDispositions, ...doctorDispositions])).toEqual([]);
    expect(rowless([{ kind: 'route', name: '/api/planted', disposition: 'pending' }])).toHaveLength(1);
  });
});
