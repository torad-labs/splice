import { describe, expect, test } from 'vitest';
import { fleetCard, fleetLede, startCommandOf } from '../src/lib/fleet';
import type { FleetCard, FleetStanding } from '../src/lib/fleet';
import type { FleetInputs } from '../src/lib/fleet';
import type { AccountRow } from '../src/types/accounts';
import type { HeadStatus, UsagePayload } from '../src/types/core';

const NOW = Date.parse('2026-09-29T18:00:00Z');
const head = (over: Partial<HeadStatus> = {}): HeadStatus => ({
  key: 'claude-grok', label: 'claude-grok', name: 'grok', port: 1, authKind: 'grok-oauth', wantVersion: '1', running: true, healthy: true,
  version: '1', versionMatch: true, mode: null, gate: null, maxInflight: null, health: {} as HeadStatus['health'], pids: [], ...over,
});
const usage = (pct: number, resetsAt: number | null = NOW / 1000 + 3600): UsagePayload => ({
  window_hours: 1, warn_pct: 80, warn_tokens_5h: 0,
  heads: [{ key: 'claude-grok', label: 'claude-grok', usage: { warn: { pct: 0, level: 'ok', source: 'none', reset: null }, quota: { five_hour: { used_pct: pct, resets_at: resetsAt } } } as never }],
});
const inputs = (over: Partial<FleetInputs> = {}): FleetInputs => ({ usage: usage(41), auth: null, accounts: [], sessions: new Map([['claude-grok', 2]]), topologyStale: false, now: NOW, ...over });
const account = (over: Partial<AccountRow> = {}): AccountRow => ({ heads: ['claude-grok'], label: 'Ava’s Grok', selected: null, ...over }) as AccountRow;

describe('a fleet card', () => {
  test('a ready head shows its tightest window and one quiet line, and no act', () => {
    const card = fleetCard(head(), inputs({ accounts: [account()] }));
    expect(card).toMatchObject({ state: 'Ready', tone: 'work', attention: false, fix: null });
    expect(card.line).toMatchObject({ kind: 'gauge', name: '5 hours', pct: 41, full: false });
    expect(card.meta).toEqual(['grok', 'Ava’s Grok', '2 sessions']);
  });
  test('a head near its warn share says so, without needing a person', () => {
    const card = fleetCard(head(), inputs({ usage: usage(90) }));
    expect(card).toMatchObject({ state: 'Near its limit', tone: 'quota', attention: false });
  });
  test('a provider refusal is out of quota with its reset, and a pool offers to switch account', () => {
    const until = NOW / 1000 + 7200;
    const card = fleetCard(head({ quotaResetAtEpochSeconds: until }), inputs({ accounts: [account(), account({ label: 'Second' })] }));
    expect(card.state).toMatch(/^Out of quota until /);
    expect(card).toMatchObject({ tone: 'quota', attention: true, fix: 'switch' });
    expect(card.line).toMatchObject({ kind: 'gauge', full: true });
    expect(card.meta).toContain('Pool · 2 accounts');
  });
  test('a single login out of quota has no account to switch to', () => {
    expect(fleetCard(head({ quotaResetAtEpochSeconds: NOW / 1000 + 60 }), inputs({ accounts: [account()] })).fix).toBeNull();
  });
  test('no credential on an oauth head is signed out with a sign-in; an api-key head says key missing and offers none', () => {
    const missing = { 'claude-grok': { kind: 'grok-oauth', login: '', present: false } };
    expect(fleetCard(head(), inputs({ auth: missing }))).toMatchObject({ state: 'Signed out', tone: 'stuck', attention: true, fix: 'sign-in' });
    expect(fleetCard(head({ authKind: 'api-key' }), inputs({ auth: { 'claude-grok': { kind: 'api-key', login: '', present: false } } }))).toMatchObject({ state: 'Key missing', fix: null });
  });
  test('a latched refresh is an expired sign-in', () => {
    expect(fleetCard(head(), inputs({ auth: { 'claude-grok': { kind: 'grok-oauth', login: '', present: true, refresh_latched: 'invalid_grant' } } })).state).toBe('Sign-in expired');
  });
  test('a stopped head is stopped and can be started; a silent local runtime is off, never an item, and is copied not started', () => {
    expect(fleetCard(head({ running: false }), inputs())).toMatchObject({ state: 'Stopped', attention: false, fix: 'start' });
    const off = fleetCard(head({ authKind: 'local', runtimeNotAnswering: ':8099' }), inputs());
    expect(off).toMatchObject({ state: 'Runtime off', tone: 'idle', attention: false, fix: 'copy-start' });
    expect(off.line).toEqual({ kind: 'note', text: 'The runtime is not answering on :8099.' });
    expect(off.meta[0]).toBe('local');
    expect(startCommandOf(head({ key: 'bonsai' }))).toBe('rig up bonsai');
  });
  test('an unhealthy head fails and can be restarted', () => {
    expect(fleetCard(head({ healthy: false }), inputs())).toMatchObject({ state: 'Failing', attention: true, fix: 'restart' });
  });
  test('a head with no window and no trouble says so and never draws a bar', () => {
    const card = fleetCard(head(), inputs({ usage: null }));
    expect(card.line).toBeNull();
    expect(card.state).toBe('Ready');
  });
  test('a window that reset since it was read is not the tightest', () => {
    const card = fleetCard(head(), inputs({ usage: usage(99, NOW / 1000 - 60) }));
    expect(card.line).toBeNull();
  });
  test('a fact already said is not said twice', () => {
    expect(fleetCard(head({ authKind: 'api-key' }), inputs({ usage: null })).meta).toEqual(['api key', '2 sessions']);
  });
  test('one session reads in the singular and none as "no sessions"', () => {
    expect(fleetCard(head(), inputs({ sessions: new Map([['claude-grok', 1]]) })).meta.at(-1)).toBe('1 session');
    expect(fleetCard(head(), inputs({ sessions: new Map() })).meta.at(-1)).toBe('no sessions');
  });
});

describe('the fleet sentence', () => {
  const stand = (...standings: FleetStanding[]): FleetCard[] => standings.map((standing) => ({ standing }) as FleetCard);
  test('counts each standing in order and ends with how to arrange the cards', () => {
    expect(fleetLede(stand('ready', 'quota', 'ready', 'off', 'near', 'signed-out'))).toBe(
      'Six plans: two ready, one near its limit, one out of quota, one signed out, one switched off. Drag a card to put it where you want it; Sessions follows.',
    );
  });
  test('one plan is singular and a standing nobody has is left out', () => {
    expect(fleetLede(stand('other'))).toBe('One plan: one in need of a look. Drag a card to put it where you want it; Sessions follows.');
  });
});
