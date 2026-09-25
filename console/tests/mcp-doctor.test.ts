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
import { budgetFor } from '../src/entities/budget';
import { BudgetRefusals, BudgetsPanel, cellNote, parseUsd } from '../src/features/budgets';
import { canTest } from '../src/entities/alert';
import type { AlertSettings } from '../src/entities/alert';
import { DoctorBoard } from '../src/pages/doctor';
import {
  EMPTIES as DOCTOR_EMPTIES,
  MARK as DOCTOR_MARK,
  TONE as DOCTOR_TONE,
  attentionCount,
  attentionParts,
  canSend,
  claudeVersionText,
  collapseChecks,
  groupChecks,
  latestText,
  logsHeadOf,
  playgroundNext,
  rollbackText,
  statusParts,
  wantsAttention,
  IDLE_PLAYGROUND,
} from '../src/pages/doctor/model';
import { S as DOCTOR_WORDS } from '../src/pages/doctor/strings';
import { ABSENT } from '../src/shared/lib';
import type { PlaygroundState } from '../src/pages/doctor/model';
import { dispositions as mcpDispositions } from '../src/pages/mcp/coverage';
import { dispositions as doctorDispositions } from '../src/pages/doctor/coverage';
import { Empty } from '../src/shared/ui';
import { statOf } from './lib/markup';

const h = React.createElement;
const render = (el: React.ReactElement): string => renderToStaticMarkup(el);

/** The daemon's own separator: a space, U+2014, then " fix: " (DoctorReportShape.kt:59). */
const SEP = ` ${String.fromCharCode(0x2014)} fix: `;

/** One check as the checks table's row, rendered by the board from a report holding only it. */
function rowOf(one: DoctorCheck): string {
  const payload: DoctorPayload = {
    schema_version: 1,
    generated_at: '2026-09-18T00:00:00Z',
    splice: { version: '0.4.0' },
    claude_code: { version: '2.1.257' },
    os: { name: 'Linux', version: '6.17', arch: 'x86_64' },
    jvm: { version: '21', vendor: 'x' },
    topology: {},
    checks: [one],
    accounts: {},
    perf: {},
  };
  const out = render(h(DoctorBoard, { report: payload, onToggle: () => undefined }));
  const table = /<table[^>]*aria-label="Checks"[^>]*>([\s\S]*?)<\/table>/.exec(out)?.[1] ?? '';
  return table.split('<tr').filter((row) => row.includes('<td')).join('');
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

  test('the row prints the check id, its status as a badge and its fix, tinted by the status', () => {
    const out = rowOf(failing);
    expect(out).toContain('daemon/port');
    expect(out).toContain(`>${DOCTOR_WORDS.statusName.fail}<`);
    expect(out).toContain('splice doctor --json');
    expect(out).toContain('myx-dt-tone-danger');
  });

  test('a check with no remedy prints the absence glyph rather than a blank cell', () => {
    // `No fix offered` is the opened check's own line, where a Doctor fix's paragraph belongs; the
    // table cell carries the absence glyph (m1 design review B8).
    const out = rowOf(plain);
    expect(out).toContain(`>${ABSENT}<`);
    expect(out).not.toContain(DOCTOR_WORDS.noFix);
    expect(out).not.toContain('myx-dt-tone');
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
    expect(new Set(rows.map((row) => row.key)).size).toBe(rows.length);
  });

  test('checks sharing an id with no colon are one row that keeps every member', () => {
    // Live 2026-09-24: eleven `installation/wrapper` checks, one per launcher. Keyed by id alone,
    // ten were overwritten and the rack showed 1 row for 11 checks (walkthrough B1).
    const checks = [
      check('installation/wrapper', 'fail', `'claudex' missing${SEP}splice install`),
      check('installation/wrapper', 'fail', `'claude-grok' missing${SEP}splice install`),
      check('installation/wrapper', 'fail', `'claude-kimi' missing${SEP}splice install`),
      check('installation/wrapper', 'ok', `'splice' present`),
    ];
    const rows = collapseChecks(checks);
    expect(rows.map((row) => [row.label, row.members.length])).toEqual([
      ['installation/wrapper (3)', 3], ['installation/wrapper', 1],
    ]);
    expect(rows.reduce((total, row) => total + row.members.length, 0)).toBe(checks.length);
    expect(new Set(rows.map((row) => row.key)).size).toBe(rows.length);
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

  test('the status tones never paint a statement green', () => {
    expect(DOCTOR_TONE).toEqual({ ok: 'ok', info: 'neutral', warn: 'warn', fail: 'danger' });
    expect(DOCTOR_MARK.info).not.toBe('ok');
  });

  test('the checks by status are one split bar in a fixed order, a zero kept in its place', () => {
    const parts = statusParts([check('a/x', 'fail', 'x'), check('a/y', 'ok', 'y'), check('b/z', 'ok', 'z')]);
    expect(parts.map((part) => [part.key, part.value])).toEqual([['ok', 2], ['info', 0], ['warn', 0], ['fail', 1]]);
  });

  test('the upgrade says none only when it looked, and the absence when it did not', () => {
    const upgrade = {
      installed: '0.4.0', latest: null, latest_basis: 'measured' as const,
      rollback_target: null, rollback_basis: 'unavailable' as const, rollback_unavailable_reason: 'no releases dir',
      checked_at_epoch_millis: null,
    };
    expect(latestText(upgrade)).toBe(DOCTOR_WORDS.none);
    expect(latestText({ ...upgrade, latest: '0.4.1' })).toBe('0.4.1');
    expect(latestText({ ...upgrade, latest_basis: 'unavailable' })).toBe(ABSENT);
    expect(rollbackText(upgrade)).toBe(ABSENT);
    expect(rollbackText({ ...upgrade, rollback_basis: 'measured', rollback_target: '0.3.9' })).toBe('0.3.9');
    expect(latestText(null)).toBe(ABSENT);
  });

  test('Claude Code\'s version drops the product name its tile already prints', () => {
    expect(claudeVersionText('2.1.282 (Claude Code)')).toBe('2.1.282');
    expect(claudeVersionText('2.1.282')).toBe('2.1.282');
  });

  test('attention first lists every check that wants the operator before any that does not', () => {
    // Walkthrough S14: sorted inside each section, configuration's ok rows sat above runtime's warns.
    const checks = [
      check('configuration/a', 'warn', 'w'),
      check('configuration/b', 'ok', 'fine'),
      check('runtime/c', 'warn', 'w'),
      check('alpha/one', 'ok', 'fine'),
      check('zeta/two', 'fail', `broken${SEP}splice restart`),
    ];
    const order = groupChecks(checks, { sort: { field: 'status' } }).flatMap((group) => group.checks.map((one) => one.id));
    expect(order).toEqual(['zeta/two', 'configuration/a', 'runtime/c', 'alpha/one', 'configuration/b']);
    const alpha = groupChecks(checks, { sort: null });
    expect(alpha.map((group) => group.key)).toEqual(['alpha', 'configuration', 'runtime', 'zeta']);
    expect(attentionCount(checks)).toBe(3);
    // the count names its parts, worst first, and only the statuses it counts (splice-lead, 2026-09-25)
    expect(attentionParts(checks)).toBe('1 Fail · 2 Warn');
    expect(attentionParts(checks.filter((one) => one.status === 'ok'))).toBeNull();
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
});

describe('budgets and alerts', () => {
  test('no budget is a state, never $0.00: an empty box that says no limit', () => {
    const out = renderToStaticMarkup(h(BudgetsPanel, { heads: ['a'] }));
    expect(out).toContain('placeholder="No limit"');
    expect(out).toContain('value=""');
    expect(out).not.toContain('0.00');
  });

  test('a refusal prints whole on its own line under the table; the save cell holds only Saved', () => {
    // The review capture of 2026-09-25 cut the daemon's refusal to "daily_usd 90…" in the cell.
    const reason = 'daily_usd 900 for claudex is past the 500 cap in splice.toml';
    expect(cellNote('Saved')).toBe('Saved');
    expect(cellNote(reason)).toBeNull();
    expect(cellNote(undefined)).toBeNull();
    const out = renderToStaticMarkup(h(BudgetRefusals, { heads: ['claudex', 'claude-grok'], notes: { claudex: reason, 'claude-grok': 'Saved' } }));
    expect(out.match(/class="myx-bud-refusal" role="status"/g)?.length).toBe(1);
    expect(out).toContain(`<span>${reason}</span>`);
    expect(out).toContain('claudex');
    expect(out).not.toContain('Saved');
    // a box as wide as its placeholder and its padding: w is the box's width in ch, padding included
    const panel = renderToStaticMarkup(h(BudgetsPanel, { heads: ['a'] }));
    expect(panel).toContain('width:12ch');
    // no column shares: in a narrow panel every split cut one column or another
    expect(panel).toContain('<colgroup>');
    expect(panel).not.toMatch(/<col[^>]*width/);
  });

  test('only an empty box clears a budget; a typo is refused and saves nothing', () => {
    expect(parseUsd('')).toEqual({ ok: true, value: null });
    expect(parseUsd('  ')).toEqual({ ok: true, value: null });
    expect(parseUsd('$5')).toEqual({ ok: true, value: 5 });
    expect(parseUsd('5.50')).toEqual({ ok: true, value: 5.5 });
    expect(parseUsd('0')).toEqual({ ok: true, value: 0 });
    // Walkthrough B2: `5$/day` read as "no budget" and deleted a $5 budget.
    expect(parseUsd('5$/day')).toEqual({ ok: false });
    expect(parseUsd('-3')).toEqual({ ok: false });
    expect(parseUsd('$')).toEqual({ ok: false });
    // Number() reads these as amounts; none is one a person typed as dollars
    for (const typo of ['0x10', '0b11', '0o7', '1e3', 'Infinity', '5.5.5']) expect(parseUsd(typo), typo).toEqual({ ok: false });
    expect(parseUsd('.5')).toEqual({ ok: true, value: 0.5 });
    expect(parseUsd('12.')).toEqual({ ok: true, value: 12 });
  });

  test('a head absent from the payload has no budget', () => {
    expect(budgetFor({ budgets: [] }, 'a')).toBeNull();
    expect(budgetFor(null, 'a')).toBeNull();
  });

  test('a test send needs a saved webhook: the daemon answers 409 without one', () => {
    const off: AlertSettings = { desktop: false, webhook_url: null };
    expect(canTest(off)).toBe(false);
    expect(canTest(null)).toBe(false);
    // desktop is no destination: the daemon delivers nothing to one (AlertDelivery.kt)
    expect(canTest({ desktop: true, webhook_url: null })).toBe(false);
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

  test('the installed figure names what is unknown: the latest release, not the version beside it', () => {
    const report: DoctorPayload = {
      schema_version: 1, generated_at: '2026-09-18T00:00:00Z', splice: { version: '0.4.0' }, claude_code: { version: '2.1.257' },
      os: { name: 'Linux', version: '6.17', arch: 'x86_64' }, jvm: { version: '21', vendor: 'x' }, topology: {}, checks: [], accounts: {}, perf: {},
    };
    const out = render(h(DoctorBoard, { report, upgrade: payload(), onToggle: () => undefined }));
    expect(statOf(out, DOCTOR_WORDS.installed)?.sub).toBe('Latest unknown');
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
    // Five since V4-220 item 4: the doctor page's Fix button posts /api/doctor/fix/{id}. Six with the
    // upgrade's run, which POST /api/upgrade starts from the version strip (V4-220 item 4).
    expect([...names].sort()).toEqual([
      '/api/doctor',
      '/api/doctor/fix/{id}',
      '/api/mcp',
      '/api/playground',
      '/api/upgrade',
      '/api/upgrade/run',
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
