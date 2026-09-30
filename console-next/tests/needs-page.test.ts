// What the Needs-you page says about its list: tone, summary, the calm figures, and the inputs it could not read.
import { describe, expect, test } from 'vitest';
import { hrefOf } from '../src/lib/needs';
import { asOfText, calmOf, ledeOf, listText, routeOf, toneOf, unreadOf } from '../src/lib/needs-page';
import type { TurnOf } from '../src/lib/sessions';
import { INPUTS } from '../src/types/needs';
import type { Need, NeedsList, Reading } from '../src/types/needs';
import type { HeadStatus } from '../src/types/core';
import type { SessionRow } from '../src/types/sessions';
import { K } from '../src/lib/words-needs';

const need = (over: Partial<Need> = {}): Need => ({
  key: 'heads:claudex', severity: 'warn', source: 'heads', kind: K.signedOut, head: 'claudex', subject: 'claudex', finding: 'x',
  fix: { kind: 'restart-daemon' }, at: null, ...over,
});
const allRead: Reading[] = INPUTS.map((input) => ({ input, state: 'read', at: 1, reason: null }));
const list = (needs: Need[], over: Partial<NeedsList> = {}): NeedsList => ({ needs, readings: allRead, readAt: 1_790_000_000_000, ...over });

const head = (key: string, over: Partial<HeadStatus> = {}): HeadStatus => ({
  key, label: key, name: key, port: 1, authKind: 'chatgpt-oauth', wantVersion: '0.4.0', running: true, healthy: true, version: '0.4.0',
  versionMatch: true, mode: null, gate: null, maxInflight: null, health: { localOriginErrors: 0, providerErrors: 0 }, pids: [1], ...over,
});
const session = (over: Partial<SessionRow> = {}): SessionRow => ({
  head: 'claudex', session_id: 's1', status: 'busy', availability: 'live', status_updated_at: 1, ...over,
}) as SessionRow;

describe('tone', () => {
  test('a session waiting for an answer waits; a quota refusal is its own colour', () => {
    expect(toneOf(need({ kind: K.waiting }))).toBe('wait');
    expect(toneOf(need({ kind: K.quota }))).toBe('quota');
  });
  test('a head that is down, a stuck session and a sign-in that lapsed are stuck; the rest waits for the operator', () => {
    expect(toneOf(need({ kind: K.failing, severity: 'danger' }))).toBe('stuck');
    expect(toneOf(need({ kind: K.stuck }))).toBe('stuck');
    expect(toneOf(need({ kind: K.signedOut }))).toBe('stuck');
    expect(toneOf(need({ kind: K.restart }))).toBe('wait');
    expect(toneOf(need({ kind: K.plan }))).toBe('wait');
  });
});

describe('the summary', () => {
  test('it says everything else is running only when every input was read', () => {
    expect(ledeOf(list([need(), need({ key: 'b' })]))).toBe('Two things a person has to do. Everything else is running.');
    expect(ledeOf(list([need()], { readAt: null }))).toContain('could not be read');
    expect(ledeOf(list([need()], { readAt: null }))).not.toContain('Everything else is running');
  });
  test('one thing is one thing, and past ten it is a numeral', () => {
    expect(ledeOf(list([need()]))).toMatch(/^One thing a person/);
    expect(ledeOf(list(Array.from({ length: 12 }, (_, i) => need({ key: `k${i}` }))))).toMatch(/^12 things/);
  });
  test('nothing is claimed as of a moment only once everything was read', () => {
    expect(ledeOf(list([]))).toBe('Nothing needs you. Everything is running.');
    expect(asOfText(list([]))).toMatch(/^As of /);
    expect(ledeOf(list([], { readAt: null }))).not.toBe('Nothing needs you. Everything is running.');
    expect(asOfText(list([], { readAt: null }))).toBeNull();
  });
});

describe('what could not be read', () => {
  test('a failed input names its reason, an unserved one the version, a pending one that it is still reading; a read one is silent', () => {
    const readings: Reading[] = [
      { input: 'heads', state: 'failed', at: null, reason: 'connection refused' },
      { input: 'teams', state: 'unserved', at: 1, reason: 'V4-9' },
      { input: 'doctor', state: 'reading', at: null, reason: null },
      { input: 'usage', state: 'read', at: 1, reason: null },
    ];
    const out = unreadOf(list([], { readings, readAt: null }));
    expect(out.map((row) => row.input)).toEqual(['heads', 'teams', 'doctor']);
    expect(out[0]?.text).toBe('Could not read the plans: connection refused');
    expect(out[1]?.text).toBe('This version of splice does not serve the teams.');
    expect(out[2]?.text).toBe('Still reading the doctor checks…');
  });
});

describe('the calm figures', () => {
  const none: TurnOf = () => undefined;
  test('serving counts the heads that answer and have no item, by name', () => {
    const calm = calmOf(list([need({ head: 'b' })]), [], none, [head('a'), head('b'), head('c', { running: false }), head('d', { healthy: false })]);
    expect(calm.serving).toEqual(['a']);
  });
  test('a plan whose local runtime is not answering is not serving, whatever its own health says', () => {
    const calm = calmOf(list([]), [], none, [head('a'), head('b', { runtimeNotAnswering: ':8099' })]);
    expect(calm.serving).toEqual(['a']);
  });
  test('working counts busy sessions, with or without a live turn (a tool running), but not a stuck, idle or gone one', () => {
    const turn = (idle: number) => ({ id: 't', session: 's1', model: 'm', compact: false, age_ms: 1, stopped: false, idle_ms: idle }) as never;
    const live: TurnOf = (row) => (row.session_id === 's1' ? turn(1_000) : row.session_id === 's3' ? turn(6 * 60_000) : null);
    const rows = [session(), session({ session_id: 's2' }), session({ session_id: 's3' }), session({ session_id: 's4', status: 'idle' }), session({ session_id: 's5', availability: 'gone' })];
    expect(calmOf(list([]), rows, live, []).working).toBe(2);
  });
  test('names read as a list', () => {
    expect(listText([])).toBe('');
    expect(listText(['Claude'])).toBe('Claude');
    expect(listText(['Claude', 'Grok'])).toBe('Claude and Grok');
    expect(listText(['Claude', 'Grok', 'Kimi'])).toBe('Claude, Grok and Kimi');
  });
});

describe('addresses', () => {
  test('every address the derivation makes is a router path once its hash is dropped', () => {
    expect(routeOf('#/fleet/claudex')).toBe('/fleet/claudex');
    expect(routeOf('/sessions')).toBe('/sessions');
    expect(routeOf(hrefOf('heads', 'a b'))).toBe('/fleet/a%20b');
    expect(routeOf(hrefOf('sessions', 's1'))).toBe('/sessions/s1');
    expect(routeOf(hrefOf('doctor'))).toBe('/settings/health');
  });
});
