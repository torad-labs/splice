// Walls for the doctor entity's pure logic (src/lib/doctor.ts), ported from the old console's
// mcp-doctor.test.ts, doctor-gate.test.ts, entities-sessions.test.ts ("doctor") and
// daemon-upgrade.test.ts (the three wire tests).
//
//   - `a leaked credential is refused, not rendered`. The doctor report walks the machine and can
//     carry a token it should have masked; the gate is asserted on the payload the page gates on.
//   - `the CLI's own mask is not a leak`. The redaction mirror matched the CLI's own output
//     (`NAME_KEY=<redacted>` is still a key, a separator and a value), so a report the CLI had
//     cleaned was refused for the CLI having done its job.
//
// SKIPPED, being renders of pages/widgets (the pages are not ported) or fetches:
//   mcp-doctor: "the row prints the check id, its status as a badge and its fix, tinted by the status";
//     "a check with no remedy prints the absence glyph rather than a blank cell"; "the status tones
//     never paint a statement green"; "the checks by status are one split bar in a fixed order, a zero
//     kept in its place"; "the installed figure names what is unknown ..."; "attention first lists every
//     check that wants the operator ..."; "Claude Code's version drops the product name ..."; "a probe
//     that read no version is unknown ..."; the playground, pending-route, budgets, alerts and coverage
//     manifest blocks.
//   doctor-gate: "the secret planted in a fix / a detail never reaches the markup", "no string of the
//     refused report reaches the markup", "the control shows the same surfaces are printed", "a refused
//     report is not described as one with no fixes", "the masked report renders its checks", "the
//     upgrade section prints the live strip only", "an opened check stays open while its status moves",
//     "the opened check prints a masked fix with why" (all DoctorBoard / FixLine renders).
//   daemon-upgrade: everything but the three wire tests (renders, fetches).
//   doctor-fix.test.ts, needs-you.test.ts, v4347-console-actions.test.ts: page and store tests.
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';
import {
  checkFinding,
  checkFix,
  checkSection,
  collapseChecks,
  fixMasked,
  isRedacted,
  leaksIn,
  leaksInText,
  logsHrefOf,
  logsTargetOf,
  upgradeVerdict,
  wantsAttention,
} from '../src/lib/doctor';
import { UPGRADE_RUN_STATES } from '../src/types/doctor';
import type { DoctorCheck, DoctorPayload, UpgradeAsk, UpgradePayload, UpgradeRun } from '../src/types/doctor';

function check(id: string, status: DoctorCheck['status'], detail: string, fix?: string): DoctorCheck {
  return fix === undefined ? { id, status, detail } : { id, status, detail, fix };
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

  // entities-sessions.test.ts, "doctor"
  test('a clean report passes and a credential shape fails, by path and never by value', () => {
    expect(isRedacted({ checks: [{ id: 'a/b', status: 'ok', detail: 'nothing to report' }] })).toBe(true);
    const leaks = leaksIn({ checks: [{ id: 'a/b', status: 'warn', detail: 'token=abc123' }] });
    expect(leaks).toEqual([{ kind: 'key-value', where: 'checks[0].detail' }]);
    expect(JSON.stringify(leaks)).not.toContain('abc123');
  });

  test('recognizes the shapes the CLI masks', () => {
    expect(leaksInText('sk-abcdefghijklmnop')).toEqual(['provider-key']);
    expect(leaksInText('person@example.com')).toEqual(['email']);
    expect(leaksInText('4e22c196-64b7-4a64-9972-5ddd337dec15')).toEqual(['uuid']);
    expect(leaksInText('all quiet')).toEqual([]);
    expect(leaksInText(`Authorization: Bearer ${'a'.repeat(50)}`)).toContain('bearer');
    expect(leaksInText(`Authorization: Bearer ${'a'.repeat(50)}`)).toContain('opaque');
  });
});

describe('the report accessors', () => {
  test('legacy error events never manufacture failed turns or cooldown totals', () => {
    const finding = checkFinding(check('runtime/head synthetic errors', 'warn', '3 provider / 7 local error(s) since last restart'));
    expect(finding).toBe('synthetic: 3 errors at the provider and 7 inside splice since the restart');
    expect(finding).not.toContain('turns failed');
    expect(finding).not.toContain('unavailable');
  });

  // entities-sessions.test.ts, "doctor"
  test('reads the section and the remedy the report carries beside the detail', () => {
    expect(checkSection({ id: 'daemon/port', status: 'ok', detail: 'x' })).toBe('daemon');
    expect(checkSection({ id: 'bare', status: 'ok', detail: 'x' })).toBe('bare');
    expect(checkFix({ id: 'a/b', status: 'fail', detail: 'no daemon', fix: 'splice restart' })).toBe('splice restart');
    expect(checkFix({ id: 'a/b', status: 'ok', detail: 'all good', fix: null })).toBeNull();
    expect(checkFix({ id: 'a/b', status: 'ok', detail: 'all good' })).toBeNull();
  });
});

describe('every failing check carries its fix', () => {
  const failing = check('daemon/port', 'fail', 'port 3096 is held', 'splice doctor --json');
  const plain = check('env/path', 'info', 'claude on PATH resolves to the versioned binary');

  test('the remedy is the report\'s fix key', () => {
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

  test('the same finding on several heads is one row that counts them', () => {
    // Live 2026-09-24: eleven `configuration/system-prompt:<head>` warnings with one fix filled
    // the first screen of the rack.
    const fix = 'set system_prompt_mode = "append"';
    const rows = collapseChecks([
      check('configuration/system-prompt:claudex', 'warn', `head 'claudex' replaces`, fix),
      check('configuration/system-prompt:bonsai', 'warn', `head 'bonsai' replaces`, fix),
      check('configuration/topology', 'ok', 'fine'),
    ]);
    expect(rows.map((row) => [row.label, row.members.length])).toEqual([
      ['System prompt (2)', 2], ['Command file', 1],
    ]);
    expect(new Set(rows.map((row) => row.key)).size).toBe(rows.length);
  });

  test('a head\'s error and turn counts are said in words, not in the daemon\'s counters', () => {
    const said = (id: string, detail: string) => checkFinding(check(id, 'warn', detail));
    expect(said('runtime/head claudex errors', '0 provider / 2 local error(s) since last restart')).toBe('claudex: 2 errors inside splice since the restart');
    expect(said('runtime/head claudex errors', '1 provider / 0 local error(s) since last restart')).toBe('claudex: 1 error at the provider since the restart');
    expect(said('runtime/head claudex errors', '3 provider / 1 local error(s) since last restart')).toBe('claudex: 3 errors at the provider and 1 inside splice since the restart');
    expect(said('runtime/head claudex errors', '0 provider / 1 local error(s) since last restart')).toBe('claudex: 1 error inside splice since the restart');
    expect(said('runtime/head claudex turns', '2 of last 5 turn(s) failed; last failure: 4m ago (error:upstream-failed)')).toBe('claudex: 2 of its last 5 turns failed; the latest, 4m ago, was Provider failed');
    expect(said('runtime/head claudex turns', '1 of last 1 turn(s) failed; last failure: 9s ago (?)')).toBe('claudex: 1 of its last 1 turn failed; the latest, 9s ago, was Unknown');
    expect(said('runtime/head claudex turns', 'perf file could not be read: denied')).toBe('perf file could not be read: denied');
    expect(said('daemon/turn path', 'WEDGED on claudex')).toBe('WEDGED on claudex');
  });

  test('a refused runtime port survives the doctor tail while old resets stay generic', () => {
    expect(checkFinding(check('runtime/head synthetic turns', 'warn',
      "1 of last 3 turn(s) failed; last failure: 4m ago (error:conn-reset); couldn't reach its runtime on :8123")))
      .toContain("Couldn't reach its runtime on :8123");
    expect(checkFinding(check('runtime/head synthetic turns', 'warn',
      '1 of last 3 turn(s) failed; last failure: 4m ago (error:conn-reset)')))
      .toContain('Connection lost');
  });

  test('finding instants use the viewer zone, across daylight saving, with no CT suffix', () => {
    const module = fileURLToPath(new URL('../src/lib/doctor.ts', import.meta.url));
    const result = execFileSync('bun', ['-e', `
      import { checkFinding } from ${JSON.stringify(module)};
      const dates = ['2026-10-01T16:18:56.973Z', '2026-01-01T16:18:56Z'];
      console.log(JSON.stringify(dates.map((date) => checkFinding({
        id: 'auth/claude-splice', status: 'warn', detail: 'Login refused at ' + date,
      }))));
    `], { env: { ...process.env, TZ: 'America/Los_Angeles' }, encoding: 'utf8' });
    expect(JSON.parse(result)).toEqual(['Login refused at Oct 1, 9:18 AM', 'Login refused at Jan 1, 8:18 AM']);
  });

  test('a stale launcher finding uses words while its original diagnostic and remedy remain available', () => {
    const raw = check('installation/shim', 'warn',
      'stale (installed=shim-10, expected=shim-11). Run ./install.sh from a checkout, or re-run the release installer.',
      "reinstall splice's launcher");
    expect(checkFinding(raw)).toBe('splice’s launcher does not match this version. Reinstall it.');
    expect(checkFinding({ ...raw, detail: 'stale (installed=shim-12, expected=shim-11).' }))
      .toBe('splice’s launcher does not match this version. Reinstall it.');
    expect(raw.detail).toContain('installed=shim-10');
    expect(checkFix(raw)).toBe("reinstall splice's launcher");
  });

  test('a check is titled for what it is about, never by the daemon\'s id', () => {
    const title = (id: string) => collapseChecks([check(id, 'warn', 'x')])[0]?.label;
    expect(title('runtime/head claudex errors')).toBe('claudex errors');
    expect(title('runtime/head claude-grok turns')).toBe('claude-grok turns');
    expect(title('auth/claude-kimi')).toBe('claude-kimi sign-in');
    expect(title('daemon/head bonsai')).toBe('bonsai port');
    expect(title('daemon/heads')).toBe('Commands starting');
    expect(title('daemon/turn path')).toBe('Turn path');
    expect(title('configuration/local:bonsai')).toBe('Local runtime · bonsai');
    expect(title('prerequisites/java')).toBe('java');
    // an id nobody has titled yet prints as its name, never with the section path in front
    expect(title('somewhere/new thing')).toBe('New thing');
    expect(title('bare')).toBe('Bare');
    expect(title('trace:e2e-codex')).toBe('Trace · e2e-codex');
  });

  test('checks sharing an id with no colon are one row that keeps every member', () => {
    // Live 2026-09-24: eleven `installation/wrapper` checks, one per launcher. Keyed by id alone,
    // ten were overwritten and the rack showed 1 row for 11 checks (walkthrough B1).
    const checks = [
      check('installation/wrapper', 'fail', `'claudex' missing`, 'splice install'),
      check('installation/wrapper', 'fail', `'claude-grok' missing`, 'splice install'),
      check('installation/wrapper', 'fail', `'claude-kimi' missing`, 'splice install'),
      check('installation/wrapper', 'ok', `'splice' present`),
    ];
    const rows = collapseChecks(checks);
    expect(rows.map((row) => [row.label, row.members.length])).toEqual([
      ['Launcher (3)', 3], ['Launcher', 1],
    ]);
    expect(rows.reduce((total, row) => total + row.members.length, 0)).toBe(checks.length);
    expect(new Set(rows.map((row) => row.key)).size).toBe(rows.length);
  });

  // The pure half of "a daemon-run fix uses its action, while an unsupported port remedy keeps Copy":
  // the collapsed row's fixId. Its FixLine render half is skipped (a page widget).
  test('a daemon-run fix keeps its fix_id on the collapsed row, and a text remedy has none', () => {
    // V4-220 item 4: `fix_id` names a fix POST /api/doctor/fix/{id} runs (install_all today).
    const [wrappers, port] = collapseChecks([
      { ...check('installation/wrapper', 'fail', `'claudex' missing`, 'splice install --all'), fix_id: 'install_all' },
      { ...check('installation/wrapper', 'fail', `'claude-grok' missing`, 'splice install --all'), fix_id: 'install_all' },
      check('daemon/port', 'warn', 'taken', 'lsof -iTCP:3099 -sTCP:LISTEN'),
    ]);
    expect([wrappers?.fixId, port?.fixId]).toEqual(['install_all', null]);
  });

  test('checks whose fixes differ stay their own rows', () => {
    const rows = collapseChecks([
      check('configuration/local:a', 'warn', 'down', 'start it'),
      check('configuration/local:b', 'fail', 'down', 'start it'),
      check('configuration/wire-tap:a', 'warn', 'on', 'remove overrides.wireTap from [heads.a]'),
      check('configuration/wire-tap:b', 'warn', 'on', 'remove overrides.wireTap from [heads.b]'),
    ]);
    expect(rows).toHaveLength(4);
  });

  test('a splice logs remedy names its head and exact bounded tail for both console pages', () => {
    expect(logsTargetOf('splice logs --head claude-kimi --tail 50')).toEqual({ head: 'claude-kimi', tail: 50 });
    expect(logsHrefOf('splice logs --head claude-kimi --tail 50')).toBe('#/fleet/claude-kimi?tab=log&tail=50');
    expect(logsTargetOf('splice restart')).toBeNull();
    expect(logsTargetOf('splice logs --head claude-kimi --tail 1500')).toEqual({ head: 'claude-kimi', tail: 1500 });
    expect(logsTargetOf('splice logs --head claude-kimi --tail 50000')).toBeNull();
    expect(logsTargetOf('splice logs --head claude-kimi --tail 5')).toBeNull();
    expect(logsTargetOf('splice logs --head <redacted:path> --tail 50')).toBeNull();
    expect(logsTargetOf(null)).toBeNull();
  });

  test('warn and fail cock the strip; ok and info do not', () => {
    expect(wantsAttention('fail')).toBe(true);
    expect(wantsAttention('warn')).toBe(true);
    expect(wantsAttention('ok')).toBe(false);
    expect(wantsAttention('info')).toBe(false);
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

describe('a refused report names where and which shape, and the CLI\'s own mask is not a leak', () => {
  const FIX_SECRET = 'sk-live-PLANTEDFIXSECRET0001';
  const DETAIL_SECRET = 'PLANTEDDETAILSECRET0002';

  /** A report whose every string is distinctive. */
  function report(checks: DoctorCheck[]): DoctorPayload {
    return {
      schema_version: 1,
      generated_at: '2026-01-02T03:04:05Z',
      splice: { version: '0.0.0-planted' },
      claude_code: { version: '9.9.9-planted' },
      os: { name: 'PlantedOS', version: 'planted-os-1', arch: 'planted-arch' },
      jvm: { version: 'planted-jvm-21', vendor: 'Planted Vendor' },
      topology: { note: 'planted-topology' },
      checks,
      accounts: { note: 'planted-accounts' },
      perf: { note: 'planted-perf' },
    };
  }

  const ids = ['auth/planted-codex', 'env/planted-proxy', 'daemon/planted-port'];
  const planted = report([
    check(ids[0] ?? '', 'fail', 'the codex key was rejected', `export OPENAI_API_KEY=${FIX_SECRET}`),
    check(ids[1] ?? '', 'warn', `Authorization: Bearer ${DETAIL_SECRET} was found in the environment`),
    check(ids[2] ?? '', 'ok', 'control plane is listening on 3096'),
  ]);
  // The same report with both secrets gone: the control.
  const clean = report([
    check(ids[0] ?? '', 'fail', 'the codex key was rejected', 'splice login planted-codex'),
    check(ids[1] ?? '', 'warn', 'a proxy variable was found in the environment'),
    check(ids[2] ?? '', 'ok', 'control plane is listening on 3096'),
  ]);

  test('the planted report is refused and the control is not', () => {
    expect(isRedacted(planted)).toBe(false);
    expect(isRedacted(clean)).toBe(true);
  });

  // The pure half of "the refusal names where and which shape, never what" (its markup half is skipped).
  test('the refusal names where and which shape, never what', () => {
    const where = leaksIn(planted).map((leak) => `${leak.kind} at ${leak.where}`);
    expect(where).toEqual(expect.arrayContaining(['key-value at checks[0].fix', 'bearer at checks[1].detail']));
    expect(JSON.stringify(leaksIn(planted))).not.toContain(FIX_SECRET);
    expect(JSON.stringify(leaksIn(planted))).not.toContain(DETAIL_SECRET);
  });

  // The stack's api-key head, exactly as the daemon serves it: the verdict's sentence and fix
  // (DoctorAuthVerdict.kt:18-20), the fix in its own key (DoctorReportShape.checks), and the CLI's
  // key=value pass masking the fix's value (DoctorRedaction.kt:65).
  const STACK_DETAIL = 'CONSOLE_E2E_NO_SUCH_KEY is not set';
  const STACK_FIX = 'export CONSOLE_E2E_NO_SUCH_KEY=<redacted>   then: splice restart';

  test('the stack\'s masked check passes the gate', () => {
    expect(leaksInText(STACK_FIX)).toEqual([]);
    expect(isRedacted(report([check('auth/e2e-openrouter', 'fail', STACK_DETAIL, STACK_FIX)]))).toBe(true);
  });

  test('every form the CLI writes its mask in passes', () => {
    for (const text of [
      'api_key=<redacted>',
      'API_KEY=<redacted> is the value',
      '{"refresh_token": "<redacted>", "id": 3}',
      'Bearer <redacted>',
      // "Authorization: Bearer x" after both passes: bearer masks the token, then key=value masks
      // the word Bearer that now stands where the value was.
      'Authorization: <redacted> <redacted>',
    ]) {
      expect(leaksInText(text), text).toEqual([]);
    }
  });

  test('a real value beside the mask still refuses', () => {
    expect(leaksInText('A_KEY=<redacted> B_TOKEN=hunter2')).toContain('key-value');
    // Written flush against the mask, so it rides inside the greedy match the mask excused.
    expect(leaksInText('A_KEY=<redacted>"B_TOKEN=hunter2')).toContain('key-value');
    expect(leaksInText('Bearer <redacted> then Bearer abc123')).toContain('bearer');
    expect(isRedacted(report([check('auth/e2e-openrouter', 'fail', STACK_DETAIL, `${STACK_FIX} OPENAI_API_KEY=hunter2`)]))).toBe(false);
  });

  test('only the exact mask is excused', () => {
    expect(leaksInText('api_key=<REDACTED>')).toContain('key-value');
    expect(leaksInText('api_key=<redacted>tail')).toContain('key-value');
    expect(leaksInText('api_key=<redacted>,B_TOKEN=hunter2')).toContain('key-value');
    expect(leaksInText('api_key=redacted')).toContain('key-value');
    expect(leaksInText('Bearer <redacted>x')).toContain('bearer');
  });
});

describe('a fix the report masked is never offered to copy', () => {
  // Marlin, 2026-09-25: `export PATH="<redacted:path>"` sat beside a Copy button, which copies a
  // line that runs nothing. The daemon's own masks mark it; anything else is a real command.
  test('the daemon\'s masks mark a fix; a real command does not', () => {
    expect(fixMasked('add to your shell rc: export PATH="<redacted:path>"')).toBe(true);
    expect(fixMasked('export OPENROUTER_API_KEY=<redacted>   then: splice restart')).toBe(true);
    expect(fixMasked('splice install --all')).toBe(false);
    expect(fixMasked('export PATH="$HOME/.local/bin:$PATH"')).toBe(false);
  });
});

describe('the upgrade run\'s wire is UpgradeRunRoutes\' own', () => {
  // THE WIRE IS READ FROM THE KOTLIN: a run exists only after a POST starts `splice upgrade` for
  // real, so a type written from a plan would agree with every fixture written from it.
  const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
  const UPGRADE = path.join(repoRoot, 'features/lifecycle/src/main/kotlin/splice/lifecycle/upgrade');
  const ROUTES = readFileSync(path.join(UPGRADE, 'UpgradeRunRoutes.kt'), 'utf8');
  const RUNS = readFileSync(path.join(UPGRADE, 'UpgradeRuns.kt'), 'utf8');

  /** The keys one member of UpgradeRunRoutes puts: `put` and `putJsonArray` in its body. */
  function keysOf(signature: string): string[] {
    const head = ROUTES.indexOf(`fun ${signature}`);
    if (head < 0) throw new Error(`no fun ${signature} in UpgradeRunRoutes.kt`);
    const body = ROUTES.slice(head, ROUTES.indexOf('\n    }', head));
    return [...new Set([...body.matchAll(/put(?:JsonArray)?\("([a-z_]+)"/g)].flatMap(([, key]) => (key === undefined ? [] : [key])))].sort();
  }

  // `Required` holds each object to its whole interface, so a key a type gains or loses moves here.
  const RUN: Required<UpgradeRun> = {
    id: '0001790000000000-ab12', args: ['upgrade', '--to', 'v0.4.1'], state: 'running',
    started_at_epoch_millis: 1_790_000_000_000, exit_code: null, output: ['splice upgrade: 0.4.0 -> 0.4.1', 'fetching splice-0.4.1.jar'],
  };
  const ASK: Required<UpgradeAsk> = { to: 'v0.4.1', rollback: true };

  test('a run view puts exactly UpgradeRun\'s keys', () => {
    expect(keysOf('view(')).toEqual(Object.keys(RUN).sort());
  });

  test('a run\'s state is one of the words UpgradeRunState writes', () => {
    const words = [...RUNS.matchAll(/^\s+[A-Z]+\("([a-z]+)"\),?$/gm)].flatMap(([, word]) => (word === undefined ? [] : [word])).sort();
    expect(words).toEqual([...UPGRADE_RUN_STATES].sort());
  });

  test('the start reads exactly the fields an ask can carry', () => {
    const read = [...ROUTES.matchAll(/body\["([a-z_]+)"\]/g)].flatMap(([, key]) => (key === undefined ? [] : [key])).sort();
    expect(read).toEqual(Object.keys(ASK).sort());
  });
});
