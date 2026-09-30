// NEEDS YOU (V4-219), held against Marlin's rule: every item comes from a measured signal, read through the
// definition its own page prints; an input nobody could read is an unknown, never silence; and "Nothing needs
// you" is true only when every input was read, with the time of the read. Ported from the old console's
// needs-you, refused-account, v4347 and v4379 tests (their pure halves), then extended with the operator's
// rulings: a session needs a person only when it waits or is stuck, a stopped runtime is no item, and a head the
// provider refuses is one explicit out-of-quota item.
import { describe, expect, test } from 'vitest';
import { accountsFromWire } from '../src/lib/accounts';
import { localInstantText } from '../src/lib/heads';
import { hrefOf, INPUTS, needsOf, readingOf, SOURCE_ORDER } from '../src/lib/needs';
import { STUCK_IDLE_MS } from '../src/lib/sessions';
import type { TurnOf } from '../src/lib/sessions';
import { H, K, S, U } from '../src/lib/words-needs';
import type { AccountRow, AccountWire } from '../src/types/accounts';
import type { AuthPayload, GateSnapshot, HeadStatus, UsagePayload } from '../src/types/core';
import type { DoctorCheck, DoctorPayload, FixKind } from '../src/types/doctor';
import type { Need, NeedInputs, Read } from '../src/types/needs';
import type { SessionRow } from '../src/types/sessions';
import type { TeamRow, TeamSlot } from '../src/types/teams';
import type { LiveTurn } from '../src/types/turns';

const NOW = 1_790_000_000_000;
const AT = NOW - 4_000;
const MIN = 60_000;

const read = <T>(data: T, at = AT): Read<T> => ({ data, error: null, lastUpdated: at });
const unread: Read<never> = { data: null, error: null, lastUpdated: null };

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
    status_updated_at: NOW - 45 * MIN, started_at: NOW - 3_600_000, updated_at: NOW - 45 * MIN, address: null, head: 'claudex',
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
    os: { name: 'linux', version: '6', arch: 'x64' }, jvm: { version: '21', vendor: 'x' }, topology: null, checks,
    accounts: null, perf: null,
  };
}

function turn(over: Partial<LiveTurn> = {}): LiveTurn {
  return { id: 't1', session: 'sess-1', model: 'm', compact: false, age_ms: 10 * MIN, stopped: false, ...over };
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

const signedOut = (): AuthPayload => ({ claudex: { kind: 'chatgpt-oauth', login: 'x', present: false } });
const needsIn = (over: Partial<NeedInputs>): Need[] => needsOf(quiet(over), NOW).needs;
const sessionItems = (over: Partial<NeedInputs>): Need[] => needsIn(over).filter((need) => need.source === 'sessions');

describe('an input is read, still out, failed or not served, and only read counts', () => {
  test('each state from the store as it holds it', () => {
    expect(readingOf('heads', { data: [], error: null, lastUpdated: AT }).state).toBe('read');
    expect(readingOf('heads', { data: null, error: null, lastUpdated: null }).state).toBe('reading');
    // a poll that failed keeps the last answer, and is failed all the same: that answer is old
    expect(readingOf('heads', { data: [], error: 'HTTP 502', lastUpdated: AT })).toEqual({ input: 'heads', state: 'failed', at: AT, reason: 'HTTP 502' });
    expect(readingOf('teams', { data: { pending: 'V4-131' }, error: null, lastUpdated: AT })).toMatchObject({ state: 'unserved', reason: 'V4-131' });
  });

  test('every input is read once, in the declared order', () => {
    expect(needsOf(quiet(), NOW).readings.map((reading) => reading.input)).toEqual([...INPUTS]);
  });
});

describe('"Nothing needs you" only when every input was read, with the time of the read', () => {
  test('an input still out blocks the quiet answer', () => {
    const list = needsOf(quiet({ usage: unread }), NOW);
    expect(list.readAt).toBeNull();
    expect(list.readings.find((reading) => reading.input === 'usage')).toMatchObject({ state: 'reading', at: null });
  });

  test('every input read and nothing found: quiet, as of the oldest read', () => {
    const list = needsOf(quiet({ usage: read(USAGE, AT - 60_000) }), NOW);
    expect(list.needs).toEqual([]);
    expect(list.readAt).toBe(AT - 60_000);
  });

  test('a failed read keeps its error and its last answer time, and blocks the quiet answer', () => {
    const list = needsOf(quiet({ doctor: { data: null, error: 'HTTP 500: doctor timed out', lastUpdated: NOW - 120_000 } }), NOW);
    expect(list.readAt).toBeNull();
    expect(list.readings.find((reading) => reading.input === 'doctor')).toEqual({
      input: 'doctor', state: 'failed', at: NOW - 120_000, reason: 'HTTP 500: doctor timed out',
    });
  });

  test('a route this daemon does not serve is unserved, is no data, and blocks the quiet answer', () => {
    const list = needsOf(quiet({ accounts: read({ pending: 'V4-9' }), teams: read({ pending: 'V4-131' }) }), NOW);
    expect(list.readAt).toBeNull();
    expect(list.needs).toEqual([]);
    expect(list.readings.filter((reading) => reading.state === 'unserved').map((reading) => reading.input)).toEqual(['accounts', 'teams']);
  });

  test('the first hour with zero connected heads lists nothing and is not an error', () => {
    const list = needsOf(quiet({ heads: read([]), auth: read({}), accounts: read({ accounts: [] }) }), NOW);
    expect(list.needs).toEqual([]);
    expect(list.readAt).toBe(AT);
  });

  test('an actual doctor failure remains listed with no heads connected', () => {
    const list = needsOf(quiet({
      heads: read([]), auth: read({}), accounts: read({ accounts: [] }),
      doctor: read(doctor([{ id: 'daemon/port', status: 'fail', detail: 'control port refused' }])),
    }), NOW);
    expect(list.needs.map((need) => [need.source, need.finding])).toEqual([['doctor', 'control port refused']]);
  });
});

describe('each head, by the cause Fleet prints', () => {
  const needs = (heads: HeadStatus[], auth: NeedInputs['auth'] = read({})) => needsIn({ heads: read(heads), auth });

  test('a head that is down starts from here, and one that fails its health check restarts', () => {
    expect(needs([head({ running: false })])[0]).toMatchObject({ severity: 'danger', source: 'heads', kind: K.failing, fix: { kind: 'start', head: 'claudex' } });
    expect(needs([head({ healthy: false })])[0]).toMatchObject({ severity: 'danger', kind: K.failing, finding: H.unhealthy, fix: { kind: 'restart', head: 'claudex' } });
    expect(needs([head({ versionMatch: false, version: '0.3.9' })])[0]).toMatchObject({
      severity: 'warn', kind: K.version, finding: 'Runs 0.3.9, splice wants 0.4.0',
    });
  });

  test('a missing login signs in, and a missing key is the command that sets it', () => {
    const out = needs([head()], read(signedOut()));
    expect(out[0]).toMatchObject({ kind: K.signedOut, finding: H.signedOut, fix: { kind: 'login', head: 'claudex' } });
    const key = needs([head({ key: 'openrouter', label: 'openrouter', authKind: 'api-key' })], read({ openrouter: { kind: 'api-key', login: '', present: false, env_var: 'OPENROUTER_API_KEY' } }));
    expect(key[0]).toMatchObject({ finding: 'OPENROUTER_API_KEY not set', fix: { kind: 'copy', command: 'splice key set OPENROUTER_API_KEY' } });
  });

  test('a login whose refresh is latched reads as signed out and signs in again', () => {
    const out = needs([head()], read({ claudex: { kind: 'chatgpt-oauth', login: 'x', present: true, refresh_latched: 'refresh rejected' } }));
    expect(out[0]).toMatchObject({ kind: K.signedOut, finding: H.loginExpired, fix: { kind: 'login', head: 'claudex' } });
  });

  test('a head with every slot busy and a full queue says so and opens Turns', () => {
    const full = head({ gate: gate({ inflight: 4, queued: 4 }) });
    expect(needs([full])[0]).toMatchObject({ kind: K.queue, finding: H.queueFull, fix: { kind: 'open', href: '#/turns' } });
  });

  test('sign-ins not read yet never read as signed out', () => {
    expect(needs([head()], unread)).toEqual([]);
  });

  test('a healthy head needs nothing', () => {
    expect(needs([head(), head({ key: 'grok', label: 'grok' })])).toEqual([]);
  });

  test('a head whose local runtime is not answering is a Fleet state, never an item (operator ruling)', () => {
    const silent = head({ key: 'bonsai', label: 'bonsai', authKind: 'api-key', runtimeNotAnswering: ':8099' });
    expect(needs([head(), silent])).toEqual([]);
    // a stopped head is still a Start item: only the answering-runtime cause is retired
    expect(needs([silent, head({ key: 'stopped', label: 'stopped', running: false })]).map((need) => need.head)).toEqual(['stopped']);
  });
});

describe('a head the provider refuses is one out-of-quota item, with the instant it lifts', () => {
  const UNTIL = NOW / 1000 + 3 * 3600;
  const refused = (over: Partial<HeadStatus> = {}): HeadStatus => head({ quotaResetAtEpochSeconds: UNTIL, ...over });
  const items = (over: Partial<NeedInputs>) => needsIn(over).filter((need) => need.kind === K.quota);

  test('the item names the head, the reset instant and opens the head on Fleet', () => {
    expect(items({ heads: read([refused()]) })).toEqual([{
      key: 'heads:claudex', severity: 'warn', source: 'heads', kind: 'Out of quota', head: 'claudex', subject: 'claudex',
      finding: `Out of quota until ${localInstantText(UNTIL)}`,
      fix: { kind: 'open', href: '#/fleet/claudex', label: 'See the command' },
      at: '#/fleet/claudex',
    }]);
  });

  test('an OAuth head that rides a pool of several logins offers Switch account; a single login or a key head does not', () => {
    const pool = [account({ label: 'a' }), account({ label: 'b' })];
    expect(items({ heads: read([refused()]), accounts: read({ accounts: pool }) })[0]?.fix).toMatchObject({ label: 'Switch account', href: '#/fleet/claudex' });
    expect(items({ heads: read([refused()]), accounts: read({ accounts: [account({ label: 'a' })] }) })[0]?.fix).toMatchObject({ label: 'See the command' });
    const key = refused({ key: 'or', label: 'or', authKind: 'api-key' });
    const keyPool = pool.map((row) => ({ ...row, heads: ['or'] }));
    expect(items({ heads: read([key]), accounts: read({ accounts: keyPool }), auth: read({}) })[0]?.fix).toMatchObject({ label: 'See the command' });
  });

  test('a refusal whose reset has passed is over: no item', () => {
    expect(items({ heads: read([refused({ quotaResetAtEpochSeconds: NOW / 1000 - 1 })]) })).toEqual([]);
  });

  test('a stopped head is its Start item, not a quota one', () => {
    expect(needsIn({ heads: read([refused({ running: false })]) }).map((need) => need.kind)).toEqual([K.failing]);
  });

  test('the nearest limit is not said twice for the same head, and still is for another', () => {
    const full = account({ windows: [{ seconds: 18_000, used_percent: 100, reset_epoch_seconds: null }] });
    const plans = (heads: HeadStatus[]) => needsIn({ heads: read(heads), accounts: read({ accounts: [full] }) }).filter((need) => need.source === 'plans');
    expect(plans([head()])).toHaveLength(1);
    expect(plans([refused()])).toEqual([]);
    expect(plans([refused({ key: 'other', label: 'other' }), head()])).toHaveLength(1);
  });
});

describe('the daemon, the plans and the accounts', () => {
  test('a changed config file and settings waiting are one restart, said once', () => {
    const both = needsIn({ topology: read(true), restartPending: ['a', 'b'] });
    expect(both).toHaveLength(1);
    expect(both[0]).toMatchObject({ source: 'daemon', kind: K.restart, at: null, finding: `${H.configChanged} 2 settings waiting`, fix: { kind: 'restart-daemon' } });
    expect(needsIn({ topology: read(false) })).toEqual([]);
  });

  test('a single key reads as one setting waiting, and an unread boot never clears it', () => {
    const one = needsOf(quiet({ heads: read([]), topology: read(false), restartPending: ['trace'] }), NOW).needs;
    expect(one.find((need) => need.key === 'daemon')?.finding).toContain('1 setting waiting');
    expect(needsOf(quiet({ topology: unread, restartPending: ['trace'] }), NOW).needs.filter((need) => need.key === 'daemon')).toHaveLength(1);
    expect(needsIn({ restartPending: [] })).toEqual([]);
  });

  test('the nearest limit past the daemon\'s critical line, as Usage prints it', () => {
    const full = account({ windows: [{ seconds: 18_000, used_percent: 99, reset_epoch_seconds: null }] });
    const out = needsIn({ accounts: read({ accounts: [full] }) });
    expect(out.find((need) => need.source === 'plans')).toMatchObject({
      severity: 'danger', kind: K.plan, subject: 'work', finding: '5h at 99%', at: '#/usage', fix: { kind: 'open', href: '#/usage' },
    });
  });

  test('a window the daemon calls not current is no limit, however full and however far its reset (V4-408)', () => {
    const old = account({ windows: [{ seconds: 604_800, used_percent: 100, reset_epoch_seconds: NOW / 1000 + 6 * 86_400, current: false }] });
    expect(needsIn({ accounts: read({ accounts: [old] }) }).find((need) => need.source === 'plans')).toBeUndefined();
    const held = account({ windows: [{ seconds: 604_800, used_percent: 100, reset_epoch_seconds: NOW / 1000 + 6 * 86_400, current: true }] });
    expect(needsIn({ accounts: read({ accounts: [held] }) }).find((need) => need.source === 'plans'))
      .toMatchObject({ severity: 'danger', finding: expect.stringContaining('100%') });
  });

  test('a future reset still leaves a near limit visible beside a spare head', () => {
    const near = account({ label: 'near', heads: ['e2e-codex'], windows: [{ seconds: 18_000, used_percent: 99, reset_epoch_seconds: NOW / 1000 + 3600 }] });
    const spare = account({ label: 'spare', heads: ['e2e-codex-solo'], windows: [{ seconds: 18_000, used_percent: 20, reset_epoch_seconds: NOW / 1000 + 3600 }] });
    const plans = needsIn({ accounts: read({ accounts: [near, spare] }) }).find((need) => need.source === 'plans');
    expect(plans).toMatchObject({ finding: '5h at 99%, resets in 1h 0m', fix: { kind: 'open', href: '#/usage' } });
  });

  test('a pooled login gone and an excluded account are their own items; a single login gone is its head\'s', () => {
    const accounts = [
      account({ label: 'spare', credential_present: false }),
      account({ label: 'main', auth_excluded_until_epoch_millis: NOW + 60_000, auth_exclusion_reason: 'refresh rejected' }),
      account({ label: null, single_login: true, credential_present: false }),
    ];
    const out = needsIn({ accounts: read({ accounts }) }).filter((need) => need.source === 'accounts');
    expect(out.map((need) => [need.subject, need.finding])).toEqual([['spare', H.accountSignedOut], ['main', 'refresh rejected']]);
    expect(out[0]?.fix).toEqual({ kind: 'login', head: 'claudex', label: 'spare' });
    expect(out[0]?.finding).toContain('this label');
    expect(out[0]?.finding).not.toContain('new label');
    expect(out[0]).toMatchObject({ kind: K.account, head: 'claudex', at: '#/fleet/claudex' });
    expect(out[1]?.fix).toEqual({ kind: 'open', href: '#/fleet', label: S.openFleet });
  });

  test('a single login whose credential is gone has no account item and no window is invented for it', () => {
    const lone = account({ label: null, single_login: true, credential_present: false });
    expect(needsIn({ accounts: read({ accounts: [lone] }) }).filter((need) => need.source === 'accounts')).toEqual([]);
  });
});

describe('a refused credential says why and offers no renewal (V4-410)', () => {
  const REASON = 'is a symbolic link, and splice does not load a linked credential';
  const SENTENCE = `'linked' ${REASON}; remove the link and sign in again, or sign in under a different label`;
  const refusedRow = (): AccountRow => account({ label: 'linked', credential_present: false, available: false, refusal: SENTENCE });
  const orphan = (): AccountRow => account({ label: 'lost', credential_present: false, available: false });

  function wireRow(over: Partial<AccountWire> = {}): AccountWire {
    return {
      credential_path: '/pool/linked.json.refused', kind: 'chatgpt-oauth', label: 'linked', primary: false, single_login: false,
      plan: null, five_hour_used_percent: null, five_hour_reset_epoch_seconds: null, five_hour_window_seconds: null,
      seven_day_used_percent: null, seven_day_reset_epoch_seconds: null, seven_day_window_seconds: null, available: false,
      credential_present: false, auth_excluded_until_epoch_millis: null, auth_exclusion_reason: null, selected: false,
      pinned: false, next_target: false, heads: ['claudex'], observed_at_epoch_seconds: null,
      five_hour_current: false, seven_day_current: false,
      ...over,
    };
  }

  const accountNeeds = (accounts: AccountRow[]) => needsIn({ accounts: read({ accounts }) }).filter((need) => need.source === 'accounts');

  test('a refusal on the wire reaches the row, and a daemon without one reads as none', () => {
    const [carried] = accountsFromWire({ accounts: [wireRow({ refusal: SENTENCE })] }).accounts;
    const [older] = accountsFromWire({ accounts: [wireRow()] }).accounts;
    expect(carried?.refusal).toBe(SENTENCE);
    expect(older?.refusal).toBeNull();
  });

  test('a refused account says why and opens Fleet, and a gone one still offers the renewal', () => {
    const items = accountNeeds([orphan(), refusedRow()]);
    expect(items.find((need) => need.subject === 'lost')).toMatchObject({ finding: H.accountSignedOut, fix: { kind: 'login', head: 'claudex', label: 'lost' } });
    expect(items.find((need) => need.subject === 'linked')).toMatchObject({ finding: SENTENCE, fix: { kind: 'open', href: '#/fleet' } });
  });

  test('the refusal outranks the missing credential and any exclusion it carries too', () => {
    const excluded = account({ ...refusedRow(), auth_excluded_until_epoch_millis: NOW + 60_000, auth_exclusion_reason: 'cooling down' });
    expect(accountNeeds([excluded])[0]).toMatchObject({ finding: SENTENCE, fix: { kind: 'open' } });
  });

  test('a blank refusal is no refusal', () => {
    expect(accountNeeds([account({ credential_present: false, refusal: '  ' })])[0]).toMatchObject({ finding: H.accountSignedOut, fix: { kind: 'login' } });
  });
});

describe('turns and team seats', () => {
  test('a live turn idle past its head\'s own limit is stalled; one within it is working', () => {
    const live = [
      { label: 'impl', compact: false, phase: 'streaming', age_ms: 90_000, idle_ms: 45_000 },
      { label: 'rev', compact: false, phase: 'streaming', age_ms: 9_000, idle_ms: 200 },
    ];
    const out = needsIn({ heads: read([head({ gate: gate({ inflight: 2, live }) })]) });
    expect(out.map((need) => [need.source, need.kind, need.subject, need.finding, need.at])).toEqual([
      ['turns', K.turn, 'impl', 'Idle 45.0s, limit 30.0s', '#/turns'],
    ]);
  });

  test('a live team\'s seat whose session ended or left the registry; an archived team is quiet', () => {
    const teams = [
      team({ slots: [slot({ id: 'gone', session: 'sess-gone' }), slot({ id: 'lost', role: 'reviewer', session: 'sess-lost' }), slot({ id: 'ok' })] }),
      team({ id: 't-2', archived: true, slots: [slot({ session: 'sess-lost' })] }),
    ];
    const sessions = [session(), session({ session_id: 'sess-gone', availability: 'gone' })];
    const out = needsIn({ teams: read({ teams }), sessions: read({ note: '', sessions }) }).filter((need) => need.source === 'teams');
    expect(out.map((need) => [need.subject, need.kind, need.finding])).toEqual([
      ['wire builder seat', K.seat, H.seatEnded], ['wire reviewer seat', K.seat, H.seatUnlisted],
    ]);
    expect(out[0]).toMatchObject({ at: '#/sessions?group=team', fix: { kind: 'open', href: '#/sessions?group=team' } });
  });

  test('with the registry unread, no seat reads as ended', () => {
    const teams = [team({ slots: [slot({ session: 'sess-lost' })] })];
    expect(needsIn({ teams: read({ teams }), sessions: { data: null, error: 'HTTP 503', lastUpdated: null } })).toEqual([]);
  });
});

describe('a session needs a person only when it waits or is stuck (operator ruling)', () => {
  const byId = (turns: Record<string, LiveTurn | null>): TurnOf => (row) => (row.session_id === null ? undefined : turns[row.session_id]);

  test('a session waiting for an answer needs the operator, with how long, and opens its own page', () => {
    const [item, ...rest] = sessionItems({ sessions: read({ note: '', sessions: [session({ status: 'waiting' })] }) });
    expect(rest).toEqual([]);
    expect(item).toEqual({
      key: 'sessions:sess-1', severity: 'warn', source: 'sessions', kind: 'Waiting on you', state: 'waiting', head: 'claudex',
      subject: 'implementer', finding: 'Waiting for your answer for 45 min', session: { id: 'sess-1', said: null, repo: 'repo' },
      fix: { kind: 'open', href: '#/sessions/sess-1', label: 'Open the session' }, at: '#/sessions/sess-1',
    });
  });

  test('a waiting session carries the newest thing it said, so the card can quote its question', () => {
    const asked = session({ status: 'waiting', last: { role: 'assistant', tool: null, text: 'Run npm run migrate -- --apply?', ts: NOW } });
    expect(sessionItems({ sessions: read({ note: '', sessions: [asked] }) })[0]?.session).toEqual({ id: 'sess-1', said: 'Run npm run migrate -- --apply?', repo: 'repo' });
  });

  test('a notice that rides in a message is not a waiting session\'s question, so nothing is quoted', () => {
    const notice = session({ status: 'waiting', last: { role: 'user', tool: null, text: '<system-reminder>A process claiming the address uds:/run/x.sock asked to be told when this session is next idle</system-reminder>', ts: NOW } });
    expect(sessionItems({ sessions: read({ note: '', sessions: [notice] }) })[0]?.session?.said).toBeNull();
  });

  test('only a question the session asked is quoted: a statement, a cut-off sentence or a call is not, and the last question wins', () => {
    const said = (role: 'assistant' | 'user', text: string, tool: string | null = null) => sessionItems({ sessions: read({ note: '', sessions: [session({ status: 'waiting', last: { role, tool, text, ts: NOW } })] }) })[0]?.session?.said;
    expect(said('assistant', 'The box run failed and used up the credit, my mistake. I picked an offer whose download rate was $0.051/GB (the earlier boxes pa')).toBeNull();
    expect(said('assistant', 'Run it?')).toBe('Run it?');
    expect(said('assistant', 'The build is green. Do you want it pushed? Or should I hold the review?')).toBe('Or should I hold the review?');
    expect(said('assistant', 'Is the build green', 'Bash')).toBeNull();
    expect(said('assistant', 'Which plan takes the session?', 'AskUserQuestion')).toBe('Which plan takes the session?');
  });

  test('a busy session with no live turn is running a tool: no item, whether the head says none or was not read', () => {
    const busy = session({ status: 'busy', status_updated_at: NOW - 8 * 60 * MIN });
    expect(sessionItems({ sessions: read({ note: '', sessions: [busy] }), turnOf: byId({ 'sess-1': null }) })).toEqual([]);
    expect(sessionItems({ sessions: read({ note: '', sessions: [busy] }) })).toEqual([]);
    expect(sessionItems({ sessions: read({ note: '', sessions: [busy] }), turnOf: () => undefined })).toEqual([]);
  });

  test('a busy session is stuck only when its live turn idles past STUCK_IDLE_MS, and stop-turn is its fix', () => {
    const busy = session({ status: 'busy' });
    const stuck = (turns: LiveTurn | null) => sessionItems({ sessions: read({ note: '', sessions: [busy] }), turnOf: byId({ 'sess-1': turns }) });
    expect(stuck(turn({ idle_ms: 7 * MIN }))).toEqual([{
      key: 'sessions:sess-1', severity: 'warn', source: 'sessions', kind: 'Stuck', state: 'stuck', head: 'claudex',
      subject: 'implementer', finding: 'Quiet for 7 min', session: { id: 'sess-1', said: null, repo: 'repo' },
      fix: { kind: 'stop-turn', head: 'claudex', session: 'sess-1' }, at: '#/sessions/sess-1',
    }]);
    // at the limit is not past it; a daemon that sends no idle_ms claims nothing; a stopped turn is already ending
    expect(stuck(turn({ idle_ms: STUCK_IDLE_MS }))).toEqual([]);
    expect(stuck(turn({ idle_ms: STUCK_IDLE_MS + 1 }))).toHaveLength(1);
    expect(stuck(turn())).toEqual([]);
    expect(stuck(turn({ idle_ms: 9 * MIN, stopped: true }))).toEqual([]);
  });

  test('a stuck session with no known head opens its page instead of offering a stop', () => {
    const unknown = session({ status: 'busy', head: 'unknown head' });
    const [item] = sessionItems({ sessions: read({ note: '', sessions: [unknown] }), turnOf: byId({ 'sess-1': turn({ idle_ms: 9 * MIN }) }) });
    expect(item).toMatchObject({ kind: K.stuck, head: null, fix: { kind: 'open', href: '#/sessions/sess-1' } });
  });

  test('an idle session, however stale, and a gone one need no one', () => {
    const rows = [
      session({ session_id: 'a', availability: 'stale' }),
      session({ session_id: 'b' }),
      session({ session_id: 'c', availability: 'gone', status: 'waiting' }),
      session({ session_id: 'd', availability: 'stale', status: 'busy' }),
    ];
    expect(sessionItems({ sessions: read({ note: '', sessions: rows }), turnOf: byId({ d: null }) })).toEqual([]);
  });

  test('the machine\'s shape: six busy seats with old timestamps and four idle stale ones make no session item', () => {
    // measured 2026-09-29: six busy seats, every one working, all with an old timestamp
    const busy = Array.from({ length: 6 }, (_, at) => session({ session_id: `busy-${at}`, name: `seat ${at}`, status: 'busy', status_updated_at: NOW - (5 + at) * 60 * MIN, updated_at: NOW - (5 + at) * 60 * MIN }));
    const idle = Array.from({ length: 4 }, (_, at) => session({ session_id: `idle-${at}`, name: `old ${at}`, status: 'idle', availability: 'stale', updated_at: NOW - (9 + at) * 60 * MIN }));
    const sessions = read({ note: '', sessions: [...busy, ...idle] });
    const teams = read({ teams: [] });
    expect(needsOf(quiet({ sessions, teams }), NOW).needs).toEqual([]);
    const noTurn: TurnOf = () => null;
    expect(needsOf(quiet({ sessions, teams, turnOf: noTurn }), NOW).needs).toEqual([]);
  });

  test('a session with no session id opens by its pid, and keys are encoded into the address', () => {
    const rows = [
      session({ session_id: null, pid: 42, name: 'unbound', status: 'waiting' }),
      session({ session_id: 'a/b', status: 'waiting' }),
    ];
    expect(sessionItems({ sessions: read({ note: '', sessions: rows }) }).map((need) => [need.key, need.at])).toEqual([
      ['sessions:42', '#/sessions/pid%3A42'], ['sessions:a/b', '#/sessions/a%2Fb'],
    ]);
  });
});

describe('one changed splice.toml, one Needs you item', () => {
  test('a pending trace joins the daemon restart without borrowing Doctor present-tense words', () => {
    const pending: DoctorCheck = {
      id: 'configuration/trace:local', status: 'warn', detail: 'WRONG: local writes its FULL request/response trace',
      fix: 'splice restart', pending_restart: true,
    };
    const out = needsIn({ topology: read(true), doctor: read(doctor([pending])) });
    expect(out).toHaveLength(1);
    expect(out[0]?.source).toBe('daemon');
    expect(out[0]?.finding).toContain('splice.toml');
    expect(out[0]?.finding).toContain('Trace for local');
    expect(out[0]?.finding).toContain('applies after restart');
    expect(out[0]?.finding).not.toContain('WRONG');
    expect(needsIn({ topology: read(false), doctor: read(doctor([pending])) }).map((need) => need.source)).toEqual(['daemon']);
    const running = { ...pending, pending_restart: false };
    expect(needsIn({ doctor: read(doctor([running])) }).map((need) => need.source)).toEqual(['doctor']);
  });

  test('a topology warning stays on Doctor when health does not report staleness', () => {
    const checks: DoctorCheck[] = [{ id: 'daemon/topology', status: 'warn', detail: 'splice.toml changed since the daemon booted', fix: 'splice restart' }];
    const out = needsIn({ topology: read(false), doctor: read(doctor(checks)) });
    expect(out.map((need) => need.source)).toEqual(['doctor']);
    expect(out[0]?.finding).toContain('splice.toml');
  });

  test('the health flag and Doctor topology warning share one restart item', () => {
    const checks: DoctorCheck[] = [{ id: 'daemon/topology', status: 'warn', detail: 'splice.toml changed since the daemon booted', fix: 'splice restart' }];
    const out = needsIn({ topology: read(true), doctor: read(doctor(checks)) });
    expect(out).toHaveLength(1);
    expect(out[0]).toMatchObject({ source: 'daemon', subject: 'splice', fix: { kind: 'restart-daemon' } });
    expect(out[0]?.finding).toContain('splice.toml');
    expect(out[0]?.finding).not.toContain('the config file');
  });
});

describe('the doctor', () => {
  test('a failing check is danger with its own remedy to copy; warn is warn; ok and info are quiet', () => {
    const checks: DoctorCheck[] = [
      { id: 'wrapper/claudex', status: 'fail', detail: 'not linked', fix: 'splice install --all', fix_kind: 'command' },
      { id: 'daemon/disk', status: 'warn', detail: 'disk 91% full' },
      { id: 'daemon/port', status: 'ok', detail: 'fine' },
      { id: 'daemon/jvm', status: 'info', detail: 'jvm 21' },
    ];
    const out = needsIn({ doctor: read(doctor(checks)) });
    expect(out.map((need) => [need.severity, need.kind, need.subject, need.finding])).toEqual([
      ['danger', K.doctor, 'Claudex', 'not linked'],
      ['warn', K.doctor, 'Disk', 'disk 91% full'],
    ]);
    expect(out[0]?.fix).toEqual({ kind: 'copy', command: 'splice install --all' });
    expect(out[1]?.fix).toMatchObject({ kind: 'open', href: '#/settings/health' });
  });

  test('a remedy the report masked is printed with why, never offered to copy (Marlin, 2026-09-25)', () => {
    const checks: DoctorCheck[] = [{ id: 'installation/PATH', status: 'fail', detail: '~/.local/bin is not on PATH', fix: 'add to your shell rc: export PATH="<redacted:path>"' }];
    const [need] = needsIn({ doctor: read(doctor(checks)) });
    expect(need?.fix).toEqual({ kind: 'masked', command: 'add to your shell rc: export PATH="<redacted:path>"' });
  });

  test('a fix the daemon can run itself is run from the item, its command kept as the fallback', () => {
    const checks: DoctorCheck[] = [{ id: 'installation/wrapper', status: 'fail', detail: "'claudex' missing", fix: 'splice install --all', fix_id: 'install_all' }];
    const [need] = needsIn({ doctor: read(doctor(checks)) });
    expect(need?.fix).toEqual({ kind: 'doctor-fix', id: 'install_all', fallback: 'splice install --all' });
  });

  test('a remedy without a console action keeps its honest CLI fallback', () => {
    const checks: DoctorCheck[] = [{ id: 'daemon/manual', status: 'warn', detail: 'manual repair needed', fix: 'repair by hand', fix_kind: 'advice' }];
    expect(needsIn({ doctor: read(doctor(checks)) })[0]?.fix).toEqual({ kind: 'open', href: '#/settings/health', label: 'Open doctor', fallback: 'repair by hand' });
  });

  test('only a fix the daemon marked a command is offered to paste; advice, and a fix with no kind, are printed beside Open doctor', () => {
    const fixOf = (fix: string, fix_kind?: FixKind | null) => needsIn({ doctor: read(doctor([{ id: 'daemon/x', status: 'warn', detail: 'd', fix, ...(fix_kind === undefined ? {} : { fix_kind }) }])) })[0]?.fix;
    expect(fixOf('rm ~/.local/bin/claudeor', 'command')).toEqual({ kind: 'copy', command: 'rm ~/.local/bin/claudeor' });
    expect(fixOf('set system_prompt_mode = "append" to add your text', 'advice')).toMatchObject({ kind: 'open', fallback: 'set system_prompt_mode = "append" to add your text' });
    // a daemon older than the field says nothing, and a first word is not evidence: no Copy
    expect(fixOf('rm ~/.local/bin/claudeor')).toMatchObject({ kind: 'open', fallback: 'rm ~/.local/bin/claudeor' });
    expect(fixOf('splice restart', 'command')).toEqual({ kind: 'restart-daemon' });
  });

  test('a disclosure the daemon reports at info is not an item, and the same row failing is', () => {
    const row = (status: DoctorCheck['status']): DoctorCheck => ({ id: 'configuration/system-prompt:claudex', status, detail: 'd', fix: 'advice', fix_kind: 'advice' });
    expect(needsIn({ doctor: read(doctor([row('info')])) })).toEqual([]);
    expect(needsIn({ doctor: read(doctor([row('fail')])) })).toHaveLength(1);
  });

  test('a splice logs remedy opens the requested head and tail instead of copying the command, and restart restarts', () => {
    const command = 'splice logs --head codex --tail 50';
    const logs: DoctorCheck[] = [{ id: 'daemon/logs', status: 'warn', detail: 'look at the head log', fix: command }];
    expect(needsIn({ doctor: read(doctor(logs)) })[0]?.fix).toEqual({ kind: 'open', href: '#/fleet/codex?tab=log&tail=50', label: S.openLog, fallback: command });
    const restart: DoctorCheck[] = [{ id: 'daemon/x', status: 'warn', detail: 'stale', fix: ' splice restart ' }];
    expect(needsIn({ doctor: read(doctor(restart)) })[0]?.fix).toEqual({ kind: 'restart-daemon' });
  });

  test('four unlinked launchers are one item', () => {
    const checks: DoctorCheck[] = ['a', 'b', 'c', 'd'].map((name) => ({ id: `installation/wrapper:${name}`, status: 'fail' as const, detail: `'${name}' missing`, fix: 'splice install --all' }));
    const out = needsIn({ doctor: read(doctor(checks)) });
    expect(out).toHaveLength(1);
    expect(out[0]).toMatchObject({ subject: 'Launcher (4)', finding: "'a' missing; 'b' missing; 'c' missing; 'd' missing" });
  });

  test('a head\'s sign-in check is said once, on the head', () => {
    const checks: DoctorCheck[] = [{ id: 'auth/claudex', status: 'fail', detail: 'not signed in', fix: 'claudex login' }];
    expect(needsIn({ doctor: read(doctor(checks)), auth: read(signedOut()) }).map((need) => need.source)).toEqual(['heads']);
  });

  test('what Doctor found follows the head\'s finding as its own sentence - V4-333', () => {
    // V4-331's render read "ANTHROPIC_API_KEY not set Doctor: ANTHROPIC_API_KEY is not set".
    const inputs = {
      heads: read([head({ key: 'openrouter', label: 'openrouter', authKind: 'api-key' })]),
      auth: read({ openrouter: { kind: 'api-key', login: '', present: false, env_var: 'OPENROUTER_API_KEY' } }),
    };
    const [alone] = needsIn(inputs);
    const checks: DoctorCheck[] = [{ id: 'auth/openrouter', status: 'fail', detail: 'OPENROUTER_API_KEY is not set' }];
    const [folded] = needsIn({ ...inputs, doctor: read(doctor(checks)) });
    expect(alone?.finding).toBe('OPENROUTER_API_KEY not set');
    // The same fact said twice is said once; what Doctor adds beyond it still follows as its own sentence.
    expect(folded?.finding).toBe('OPENROUTER_API_KEY not set');
    const [more] = needsIn({ ...inputs, doctor: read(doctor([{ id: 'auth/openrouter', status: 'fail', detail: 'OPENROUTER_API_KEY is not set' }, { id: 'auth/openrouter', status: 'warn', detail: 'the key store is locked' }])) });
    expect(more?.finding).toBe(`OPENROUTER_API_KEY not set. ${U.doctor} the key store is locked`);
  });

  // V4-333: V4-331's render listed one stopped head three times: its own item, the daemon's
  // "still converging" count and its port's "not listening" (DoctorHeadChecks), each with a fix.
  const stopped = (): Partial<NeedInputs> => ({
    heads: read([head(), head({ key: 'mockchat2', label: 'mockchat2', port: 54791, running: false, healthy: false })]),
    doctor: read(doctor([
      { id: 'daemon/heads', status: 'warn', detail: 'still converging: 1 ready + 0 failed of 2' },
      { id: 'daemon/head claudex', status: 'info', detail: ':3099 listening' },
      { id: 'daemon/head mockchat2', status: 'warn', detail: ':54791 not listening' },
    ])),
  });

  test('a stopped head is one item, its Start fix kept and what Doctor found said on it - V4-333', () => {
    const out = needsIn(stopped());
    expect(out.map((need) => [need.source, need.subject, need.finding, need.fix])).toEqual([
      ['heads', 'mockchat2', `${H.down} ${U.doctor} still converging: 1 ready + 0 failed of 2; :54791 not listening`, { kind: 'start', head: 'mockchat2' }],
    ]);
  });

  test('a doctor check about no head with an item stays its own item - V4-333', () => {
    // Every head up, one of them with an item that is not about being down: the count and the port
    // are the daemon's to explain, not that head's.
    const up = { ...stopped(), heads: read([head({ versionMatch: false }), head({ key: 'mockchat2', label: 'mockchat2', port: 54791 })]) };
    expect(needsIn(up).map((need) => `${need.source}:${need.subject}`)).toEqual(['heads:claudex', 'doctor:Commands starting', 'doctor:mockchat2 port']);
    // A head that failed to start carries the daemon's own remedy, which Start is not.
    const failed = { ...stopped(), doctor: read(doctor([{ id: 'daemon/heads', status: 'fail' as const, detail: '1 of 2 head(s) FAILED to start', fix: 'splice restart' }])) };
    expect(needsIn(failed).map((need) => `${need.source}:${need.subject}`)).toEqual(['heads:mockchat2', 'doctor:Commands starting']);
  });
});

describe('the list ranks worst first', () => {
  test('SOURCE_ORDER is the printed order of sources within a severity', () => {
    expect(SOURCE_ORDER).toEqual(['heads', 'daemon', 'plans', 'accounts', 'turns', 'sessions', 'teams', 'doctor']);
  });

  test('danger before warn, and within each, sources in SOURCE_ORDER', () => {
    // Concatenation order puts warn heads before danger heads and the daemon's warn before the plan's
    // danger, so a list that was not sorted could not pass this.
    const live = [{ label: 'impl', compact: false, phase: 'streaming', age_ms: 90_000, idle_ms: 45_000 }];
    const near = account({ label: 'near', windows: [{ seconds: 18_000, used_percent: 99, reset_epoch_seconds: null }] });
    const excluded = account({ label: 'main', auth_excluded_until_epoch_millis: NOW + 60_000, auth_exclusion_reason: 'refresh rejected' });
    const inputs = quiet({
      heads: read([head({ versionMatch: false, gate: gate({ inflight: 1, live }) }), head({ key: 'down', label: 'down', running: false })]),
      accounts: read({ accounts: [near, excluded] }),
      topology: read(true),
      sessions: read({ note: '', sessions: [session({ status: 'waiting' })] }),
      teams: read({ teams: [team({ slots: [slot({ session: 'sess-lost' })] })] }),
      doctor: read(doctor([
        { id: 'daemon/disk', status: 'warn', detail: 'disk 91% full' },
        { id: 'daemon/x', status: 'fail', detail: 'broken' },
      ])),
    });
    expect(needsOf(inputs, NOW).needs.map((need) => `${need.severity}:${need.source}`)).toEqual([
      'danger:heads', 'danger:plans', 'danger:doctor',
      'warn:heads', 'warn:daemon', 'warn:accounts', 'warn:turns', 'warn:sessions', 'warn:teams', 'warn:doctor',
    ]);
  });
});

describe('each item opens itself on its page, at the console-next routes', () => {
  test('hrefOf: a head on Fleet, a session\'s own page, and the pages the rest live on; the daemon has none', () => {
    expect(hrefOf('heads', 'claudex')).toBe('#/fleet/claudex');
    expect(hrefOf('heads')).toBe('#/fleet');
    expect(hrefOf('accounts', 'a b')).toBe('#/fleet/a%20b');
    expect(hrefOf('accounts')).toBe('#/fleet');
    expect(hrefOf('plans')).toBe('#/usage');
    expect(hrefOf('turns')).toBe('#/turns');
    expect(hrefOf('sessions')).toBe('#/sessions');
    expect(hrefOf('sessions', 'pid:42')).toBe('#/sessions/pid%3A42');
    expect(hrefOf('teams')).toBe('#/sessions?group=team');
    expect(hrefOf('doctor')).toBe('#/settings/health');
    expect(hrefOf('daemon')).toBeNull();
  });

  test('every source\'s item carries its own address', () => {
    const live = [{ label: 'impl', compact: false, phase: 'streaming', age_ms: 90_000, idle_ms: 45_000 }];
    const near = account({ label: 'near', windows: [{ seconds: 18_000, used_percent: 99, reset_epoch_seconds: null }] });
    const excluded = account({ label: 'main', auth_excluded_until_epoch_millis: NOW + 60_000, auth_exclusion_reason: 'refresh rejected' });
    const out = needsIn({
      heads: read([head({ gate: gate({ inflight: 1, live }) }), head({ key: 'down', label: 'down', running: false })]),
      accounts: read({ accounts: [near, excluded] }),
      sessions: read({ note: '', sessions: [session({ status: 'waiting' })] }),
      teams: read({ teams: [team({ slots: [slot({ session: 'sess-lost' })] })] }),
      doctor: read(doctor([{ id: 'daemon/disk', status: 'warn', detail: 'disk 91% full' }])),
      topology: read(true),
    });
    const at = (source: string) => out.filter((need) => need.source === source).map((need) => need.at);
    expect(at('heads')).toEqual(['#/fleet/down']);
    expect(at('daemon')).toEqual([null]);
    expect(at('plans')).toEqual(['#/usage']);
    expect(at('accounts')).toEqual(['#/fleet/claudex']);
    expect(at('turns')).toEqual(['#/turns']);
    expect(at('sessions')).toEqual(['#/sessions/sess-1']);
    expect(at('teams')).toEqual(['#/sessions?group=team']);
    expect(at('doctor')).toEqual(['#/settings/health']);
  });
});
