// NEEDS YOU (V4-219), held against Marlin's rule: every item comes from a measured signal, read
// through the definition its own page prints; an input nobody could read is an unknown, never
// silence; and "Nothing needs you" appears only when every input was read, with the time of the
// read. Each test builds the stores' contents as plain payloads (a static render never sees a store
// past its first state) and reads the list and its render back.
//
// A .ts file holds no JSX (CONTRACTS.md section 4), so the elements are built with createElement.
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import type { AccountRow } from '../src/entities/account';
import type { DoctorCheck, DoctorPayload } from '../src/entities/doctor';
import type { SessionRow } from '../src/entities/session';
import type { TeamRow, TeamSlot } from '../src/entities/team';
import type { GateSnapshot, HeadStatus, UsagePayload } from '../src/shared/api';
import { FixCell, NeedsYouBoard, needsOf, readingOf } from '../src/pages/needs-you';
import type { NeedInputs, Read } from '../src/pages/needs-you';
import { H, S } from '../src/pages/needs-you/strings';
import { clockText } from '../src/widgets/rule';

const NOW = 1_790_000_000_000;
const AT = NOW - 4_000;

const read = <T>(data: T, at = AT): Read<T> => ({ data, error: null, lastUpdated: at });

function gate(over: Partial<GateSnapshot> = {}): GateSnapshot {
  return { inflight: 0, queued: 0, max: 4, acquired: 0, released: 0, waited: 0, avg_wait_ms: 0, live: [], stream_idle_ms: 30_000, ...over };
}

function head(over: Partial<HeadStatus> = {}): HeadStatus {
  return {
    key: 'claudex', label: 'claudex', name: 'claudex', port: 3099, authKind: 'chatgpt-oauth', wantVersion: '0.4.0',
    running: true, healthy: true, version: '0.4.0', versionMatch: true, mode: null, gate: gate(), maxInflight: 4,
    health: { localOriginErrors: 0, providerErrors: 0 }, pids: [1],
    ...over,
  };
}

function account(over: Partial<AccountRow> = {}): AccountRow {
  return {
    kind: 'chatgpt-oauth', label: 'work', single_login: false, credential_path: null, plan: null, primary: false, selected: false,
    available: true, pinned: false, next_target: false, credential_present: true, auth_excluded_until_epoch_millis: null,
    auth_exclusion_reason: null, windows: [], heads: ['claudex'],
    ...over,
  };
}

function session(over: Partial<SessionRow> = {}): SessionRow {
  return {
    pid: 7, session_id: 'sess-1', name: 'implementer', kind: 'interactive', version: '2', cwd: '/repo', status: 'idle',
    status_updated_at: NOW, started_at: NOW - 3_600_000, updated_at: NOW - 45 * 60_000, address: null, head: 'claudex',
    availability: 'live',
    ...over,
  };
}

function slot(over: Partial<TeamSlot> = {}): TeamSlot {
  return {
    id: 's-1', role: 'builder', head: 'claudex', model: null, account: null, lead: false, instructions: null, session: 'sess-1',
    instructions_updated_epoch_millis: null, sessions_history: ['sess-1'],
    ...over,
  };
}

function team(over: Partial<TeamRow> = {}): TeamRow {
  return {
    id: 't-1', name: 'wire', goal: 'ship', features: [], repo: '/repo', archived: false, created_epoch_millis: 0, updated_epoch_millis: 0,
    slots: [slot()], idempotency_key: null, create_fingerprint: null,
    ...over,
  };
}

function doctor(checks: DoctorCheck[]): DoctorPayload {
  return {
    schema_version: 1, generated_at: '2026-09-25T00:00:00Z', splice: { version: '0.4.0' }, claude_code: { version: '2' },
    os: { name: 'linux', version: '6', arch: 'x64' }, jvm: { version: '21', vendor: 'x' }, checks,
  } as DoctorPayload;
}

const USAGE: UsagePayload = { window_hours: 24, warn_pct: 80, warn_tokens_5h: 0, heads: [] };

/** Every input read, and nothing in any of them that needs the operator. */
function quiet(over: Partial<NeedInputs> = {}): NeedInputs {
  return {
    heads: read([head()]),
    auth: read({ claudex: { kind: 'chatgpt-oauth', login: 'x', present: true } }),
    accounts: read({ accounts: [account()] }),
    usage: read(USAGE),
    sessions: read({ note: '', sessions: [session()] }),
    teams: read({ teams: [team()] }),
    doctor: read(doctor([{ id: 'daemon/port', status: 'ok', detail: 'port 3099 answers' }])),
    topology: read(false),
    restartPending: [],
    ...over,
  };
}

const render = (inputs: NeedInputs): string => renderToStaticMarkup(createElement(NeedsYouBoard, { list: needsOf(inputs, NOW), now: NOW }));

describe('an input is read, still out, failed or not served, and only read counts', () => {
  test('each state from the store as it holds it', () => {
    expect(readingOf('heads', { data: [], error: null, lastUpdated: AT }).state).toBe('read');
    expect(readingOf('heads', { data: null, error: null, lastUpdated: null }).state).toBe('reading');
    // a poll that failed keeps the last answer, and is failed all the same: that answer is old
    expect(readingOf('heads', { data: [], error: 'HTTP 502', lastUpdated: AT })).toEqual({ input: 'heads', state: 'failed', at: AT, reason: 'HTTP 502' });
    expect(readingOf('teams', { data: { pending: 'V4-131' }, error: null, lastUpdated: AT })).toMatchObject({ state: 'unserved', reason: 'V4-131' });
  });
});

describe('"Nothing needs you" only when every input was read, with the time of the read', () => {
  test('an input still out is listed, and the answer is not all clear', () => {
    const html = render(quiet({ usage: { data: null, error: null, lastUpdated: null } }));
    expect(html).toContain(S.nothingYet);
    expect(html).not.toContain(S.nothing + '<');
    expect(html).toContain(`>${S.inputs.usage}<`);
    expect(html).toContain(`>${S.reading}<`);
  });

  test('every input read and nothing found: the quiet answer, as of the oldest read', () => {
    const inputs = quiet({ usage: read(USAGE, AT - 60_000) });
    const list = needsOf(inputs, NOW);
    expect(list.needs).toEqual([]);
    expect(list.readAt).toBe(AT - 60_000);
    const html = render(inputs);
    expect(html).toContain(`>${S.nothing}<`);
    expect(html).toContain(`Read ${clockText(AT - 60_000, false)}`);
    expect(html).not.toContain(S.unread);
  });

  test('a failed read prints its error and when it last answered, and blocks the quiet answer', () => {
    const inputs = quiet({ doctor: { data: null, error: 'HTTP 500: doctor timed out', lastUpdated: NOW - 120_000 } });
    const html = render(inputs);
    expect(needsOf(inputs, NOW).readAt).toBeNull();
    expect(html).toContain('HTTP 500: doctor timed out');
    expect(html).toContain(`>${S.failed}<`);
    expect(html).toContain('>2m ago<');
    expect(html).toContain(S.nothingYet);
  });
});

describe('each head, by the cause Fleet prints', () => {
  const needs = (heads: HeadStatus[], auth: NeedInputs['auth'] = read({})) => needsOf(quiet({ heads: read(heads), auth }), NOW).needs;

  test('a head that is down starts from here, and one that fails its health check restarts', () => {
    expect(needs([head({ running: false })])[0]).toMatchObject({ severity: 'danger', source: 'heads', fix: { kind: 'start', head: 'claudex' } });
    expect(needs([head({ healthy: false })])[0]).toMatchObject({ severity: 'danger', finding: H.unhealthy, fix: { kind: 'restart', head: 'claudex' } });
    expect(needs([head({ versionMatch: false, version: '0.3.9' })])[0]).toMatchObject({ severity: 'warn', finding: 'Runs 0.3.9, daemon wants 0.4.0' });
  });

  test('a missing login signs in, and a missing key is the command that sets it', () => {
    const out = needs([head()], read({ claudex: { kind: 'chatgpt-oauth', login: 'x', present: false } }));
    expect(out[0]).toMatchObject({ finding: H.signedOut, fix: { kind: 'open', href: '#/accounts', label: S.signIn } });
    const key = needs([head({ key: 'openrouter', label: 'openrouter', authKind: 'api-key' })], read({ openrouter: { kind: 'api-key', login: '', present: false, env_var: 'OPENROUTER_API_KEY' } }));
    expect(key[0]).toMatchObject({ finding: 'OPENROUTER_API_KEY not set', fix: { kind: 'copy', command: 'splice key set OPENROUTER_API_KEY' } });
  });

  test('sign-ins not read yet never read as signed out', () => {
    expect(needs([head()], { data: null, error: null, lastUpdated: null })).toEqual([]);
  });

  test('a healthy head needs nothing', () => {
    expect(needs([head(), head({ key: 'grok', label: 'grok' })])).toEqual([]);
  });
});

describe('the daemon, the plans and the accounts', () => {
  test('a changed config file and settings waiting are one restart, said once', () => {
    const both = needsOf(quiet({ topology: read(true), restartPending: ['a', 'b'] }), NOW).needs;
    expect(both).toHaveLength(1);
    expect(both[0]).toMatchObject({ source: 'daemon', finding: `${H.configChanged} 2 settings waiting`, fix: { kind: 'restart-daemon' } });
    expect(needsOf(quiet({ topology: read(false) }), NOW).needs).toEqual([]);
  });

  test('the nearest limit past the daemon\'s critical line, as Accounts prints it', () => {
    const full = account({ windows: [{ seconds: 18_000, used_percent: 99, reset_epoch_seconds: null }] });
    const out = needsOf(quiet({ accounts: read({ accounts: [full] }) }), NOW).needs;
    expect(out.find((need) => need.source === 'plans')).toMatchObject({ severity: 'danger', subject: 'work', finding: '5h at 99%' });
  });

  test('a pooled login gone and an excluded account are their own items; a single login gone is its head\'s', () => {
    const accounts = [
      account({ label: 'spare', credential_present: false }),
      account({ label: 'main', auth_excluded_until_epoch_millis: NOW + 60_000, auth_exclusion_reason: 'refresh rejected' }),
      account({ label: null, single_login: true, credential_present: false }),
    ];
    const out = needsOf(quiet({ accounts: read({ accounts }) }), NOW).needs.filter((need) => need.source === 'accounts');
    expect(out.map((need) => [need.subject, need.finding])).toEqual([['spare', H.accountSignedOut], ['main', 'refresh rejected']]);
  });
});

describe('turns, sessions and team seats', () => {
  test('a live turn idle past its head\'s own limit is stalled; one within it is working', () => {
    const live = [
      { label: 'impl', compact: false, phase: 'streaming', age_ms: 90_000, idle_ms: 45_000 },
      { label: 'rev', compact: false, phase: 'streaming', age_ms: 9_000, idle_ms: 200 },
    ];
    const out = needsOf(quiet({ heads: read([head({ gate: gate({ inflight: 2, live }) })]) }), NOW).needs;
    expect(out.map((need) => [need.source, need.subject, need.finding])).toEqual([['turns', 'impl', 'Idle 45.0s, limit 30.0s']]);
  });

  test('a stale session needs the operator; a live or gone one does not', () => {
    const sessions = [session({ session_id: 'a', availability: 'stale' }), session({ session_id: 'b' }), session({ session_id: 'c', availability: 'gone' })];
    const out = needsOf(quiet({ sessions: read({ note: '', sessions }) }), NOW).needs.filter((need) => need.source === 'sessions');
    expect(out.map((need) => [need.subject, need.finding])).toEqual([['implementer', 'Last heard 45m ago']]);
  });

  test('a live team\'s seat whose session ended or left the registry; an archived team is quiet', () => {
    const teams = [
      team({ slots: [slot({ id: 'gone', session: 'sess-gone' }), slot({ id: 'lost', role: 'reviewer', session: 'sess-lost' }), slot({ id: 'ok' })] }),
      team({ id: 't-2', archived: true, slots: [slot({ session: 'sess-lost' })] }),
    ];
    const sessions = [session(), session({ session_id: 'sess-gone', availability: 'gone' })];
    const out = needsOf(quiet({ teams: read({ teams }), sessions: read({ note: '', sessions }) }), NOW).needs.filter((need) => need.source === 'teams');
    expect(out.map((need) => [need.subject, need.finding])).toEqual([['wire builder seat', H.seatEnded], ['wire reviewer seat', H.seatUnlisted]]);
  });

  test('with the registry unread, no seat reads as ended', () => {
    const teams = [team({ slots: [slot({ session: 'sess-lost' })] })];
    expect(needsOf(quiet({ teams: read({ teams }), sessions: { data: null, error: 'HTTP 503', lastUpdated: null } }), NOW).needs).toEqual([]);
  });
});

describe('the doctor', () => {
  const SEP = ` ${String.fromCharCode(0x2014)} fix: `;

  test('a failing check is danger with its own remedy to copy; warn is warn; ok and info are quiet', () => {
    const checks: DoctorCheck[] = [
      { id: 'wrapper/claudex', status: 'fail', detail: `not linked${SEP}splice install --all` },
      { id: 'daemon/disk', status: 'warn', detail: 'disk 91% full' },
      { id: 'daemon/port', status: 'ok', detail: 'fine' },
      { id: 'daemon/jvm', status: 'info', detail: 'jvm 21' },
    ];
    const out = needsOf(quiet({ doctor: read(doctor(checks)) }), NOW).needs;
    expect(out.map((need) => [need.severity, need.subject, need.finding])).toEqual([
      ['danger', 'wrapper/claudex', 'not linked'],
      ['warn', 'daemon/disk', 'disk 91% full'],
    ]);
    expect(out[0].fix).toEqual({ kind: 'copy', command: 'splice install --all' });
    expect(out[1].fix).toMatchObject({ kind: 'open', href: '#/doctor' });
  });

  test('a head\'s sign-in check is said once, on the head', () => {
    const checks: DoctorCheck[] = [{ id: 'auth/claudex', status: 'fail', detail: `not signed in${SEP}claudex login` }];
    const out = needsOf(quiet({
      doctor: read(doctor(checks)),
      auth: read({ claudex: { kind: 'chatgpt-oauth', login: 'x', present: false } }),
    }), NOW).needs;
    expect(out.map((need) => need.source)).toEqual(['heads']);
  });
});

describe('the list ranks worst first', () => {
  test('danger before warn, and within each, heads, daemon, plans, accounts, turns, sessions, teams, doctor', () => {
    const inputs = quiet({
      heads: read([head({ versionMatch: false }), head({ key: 'down', label: 'down', running: false })]),
      topology: read(true),
      sessions: read({ note: '', sessions: [session({ availability: 'stale' })] }),
      doctor: read(doctor([{ id: 'daemon/x', status: 'fail', detail: 'broken' }])),
    });
    expect(needsOf(inputs, NOW).needs.map((need) => `${need.severity}:${need.source}`)).toEqual([
      'danger:heads', 'danger:doctor', 'warn:heads', 'warn:daemon', 'warn:sessions',
    ]);
  });
});

describe('each fix is the control that makes it', () => {
  const html = (fix: Parameters<typeof FixCell>[0]['fix']) => renderToStaticMarkup(createElement(FixCell, { fix }));

  test('start is a key, restart arms first, a command is printed beside its copy, open is a link', () => {
    expect(html({ kind: 'start', head: 'claudex' })).toContain(`>${S.start}<`);
    expect(html({ kind: 'restart', head: 'claudex' })).toContain(`>${S.restart}<`);
    const copy = html({ kind: 'copy', command: 'splice key set X' });
    expect(copy).toContain('>splice key set X</code>');
    expect(copy).toContain('Copy');
    expect(html({ kind: 'open', href: '#/turns', label: S.openTurns })).toMatch(/href="#\/turns"[^>]*>.*Open turns/);
  });
});
