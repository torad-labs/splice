import { describe, expect, test } from 'vitest';
import { fleetCard, fleetLede, startCommandOf } from '../src/lib/fleet';
import { localInstantText, localZonedInstantText } from '../src/lib/heads';
import type { FleetCard, FleetStanding } from '../src/lib/fleet';
import type { FleetInputs } from '../src/lib/fleet';
import type { AccountRow } from '../src/types/accounts';
import type { HeadStatus, UsagePayload } from '../src/types/core';

const NOW = Date.parse('2026-09-29T18:00:00Z');
const head = (over: Partial<HeadStatus> = {}): HeadStatus => ({
  key: 'claude-grok', label: 'claude-grok', name: 'grok', port: 1, authKind: 'grok-oauth', wantVersion: '1', running: true, healthy: true,
  version: '1', versionMatch: true, mode: null, gate: null, maxInflight: null, health: {} as HeadStatus['health'], pids: [],
  last_provider_answer: { status: 200, observed_at_epoch_ms: NOW - 60_000, accepted: true }, ...over,
});
const usage = (pct: number, resetsAt: number | null = NOW / 1000 + 3600): UsagePayload => ({
  window_hours: 1, warn_pct: 80, warn_tokens_5h: 0,
  heads: [{ key: 'claude-grok', label: 'claude-grok', usage: { warn: { pct: 0, level: 'ok', source: 'none', reset: null }, quota: { five_hour: { used_pct: pct, resets_at: resetsAt } } } as never }],
});
const inputs = (over: Partial<FleetInputs> = {}): FleetInputs => ({ usage: usage(41), auth: null, accounts: [], sessions: new Map([['claude-grok', 2]]), topologyStale: false, family: 'xai', keys: null, now: NOW, ...over });
const account = (over: Partial<AccountRow> = {}): AccountRow => ({ heads: ['claude-grok'], label: 'Ava’s Grok', selected: null, ...over }) as AccountRow;

describe('a fleet card', () => {
  test.each([['kimi-oauth', 403, 'Access refused'], ['muse-oauth', 429, 'Rate limited']] as const)('the last %s provider answer overrides daemon liveness and old gauges', (authKind, status, state) => {
    const refused = head({ authKind, last_provider_answer: { status, observed_at_epoch_ms: NOW - 3_600_000, accepted: false } });
    const card = fleetCard(refused, inputs());
    expect(card.state).toBe(state);
    expect(card.standing).not.toBe('ready');
    expect(card.line?.kind).toBe('note');
    expect(card.none).not.toBe('No reading yet');
    expect(card.providerAnswer).toContain(`HTTP ${status}`);
    expect(card.providerAnswer).toContain('1h ago');
    expect(fleetCard({ ...refused, last_provider_answer: { status: 200, observed_at_epoch_ms: NOW, accepted: true } }, inputs()).state).toBe('Ready');
  });

  test.each([null, undefined])('a provider with no observed answer %s never reads Ready', last_provider_answer => {
    const unobserved = head({ last_provider_answer: null });
    if (last_provider_answer === undefined) delete unobserved.last_provider_answer;
    const card = fleetCard(unobserved, inputs());
    expect(card.state).toBe('Readiness unknown');
    expect(card.standing).not.toBe('ready');
    expect(card.attention).toBe(false);
  });

  test('a refusal still suppresses No reading yet when a missing credential supplies the stronger fix', () => {
    const denied = head({ last_provider_answer: { status: 403, observed_at_epoch_ms: NOW, accepted: false } });
    const card = fleetCard(denied, inputs({ usage: null, auth: { 'claude-grok': { kind: 'grok-oauth', login: '', present: false } } }));
    expect(card.state).toBe('Signed out');
    expect(card.none).toBeNull();
    expect(card.providerAnswer).toContain('HTTP 403');
  });

  test('unknown readiness preserves an independently current quota reading without treating it as acceptance', () => {
    const card = fleetCard(head({ last_provider_answer: null }), inputs());
    expect(card.state).toBe('Readiness unknown');
    expect(card.line).toMatchObject({ kind: 'gauge', name: '5 hours', pct: 41 });
  });

  test('an accepted WebSocket response is proof without a fabricated HTTP status', () => {
    const card = fleetCard(head({ last_provider_answer: { status: null, observed_at_epoch_ms: NOW - 60_000, accepted: true } }), inputs());
    expect(card.state).toBe('Ready');
    expect(card.providerAnswer).toContain('streamed request');
    expect(card.providerAnswer).not.toContain('HTTP');
  });
  test('the daemon family names a local runtime even when it has a key, and a remote head stays remote without one', () => {
    const local = head({ key: 'bonsai', label: 'bonsai', authKind: 'api-key' });
    const keyed = { path: '', keys: [{ name: 'BONSAI_API_KEY', stored: true, heads: [{ head: 'bonsai', source: 'store' }] }] } as never;
    const card = fleetCard(local, inputs({ family: 'local', keys: keyed, sessions: new Map() }));
    expect(card.meta).toEqual(['this computer', 'no sessions']);
    expect(card.colour).toBe('local');
    const remote = head({ key: 'openrouter', label: 'openrouter', authKind: 'api-key' });
    expect(fleetCard(remote, inputs({ family: 'openrouter', keys: keyed, sessions: new Map() })).meta).toEqual(['api key', 'no sessions']);
    expect(fleetCard(remote, inputs({ family: null, keys: keyed, sessions: new Map() })).meta).toEqual(['api key', 'no sessions']);
  });
  test.each(['bearer', 'local'])('a remote daemon family never reads as local with auth kind %s', (authKind) => {
    const remote = head({ key: 'openrouter', authKind });
    const card = fleetCard(remote, inputs({ family: 'openrouter', keys: { path: '', keys: [] }, usage: null, sessions: new Map() }));
    expect(card.meta).toEqual(['api key', 'no sessions']);
    expect(card.colour).toBe('router');
    expect(card.none).toBe('Pays per token; no window');
  });
  test('an API-key head uses its declared vendor colour rather than the generic key colour', () => {
    expect(fleetCard(head({ authKind: 'api-key' }), inputs({ family: 'anthropic' })).colour).toBe('claude');
    expect(fleetCard(head({ authKind: 'api-key' }), inputs({ family: null })).colour).toBe('none');
  });
  test('a card with no window to draw says why, and only what is true', () => {
    const keyed = { path: '', keys: [{ name: 'K', stored: true, heads: [{ head: 'openrouter', source: 'store' }] }] } as never;
    const key = head({ key: 'openrouter', label: 'openrouter', authKind: 'api-key' });
    expect(fleetCard(key, inputs({ keys: keyed, usage: null })).none).toBe('Pays per token; no window');
    expect(fleetCard(head({ key: 'bonsai', authKind: 'api-key' }), inputs({ family: 'local', keys: keyed, usage: null })).none).toBeNull();
    expect(fleetCard(head(), inputs({ usage: null })).none).toBe('No quota reading reported');
    const old = usage(41, NOW / 1000 - 60);
    const read = old.heads[0]?.usage?.quota?.five_hour;
    if (read !== undefined) read.observed_at = NOW / 1000 - 3 * 3600 - 60;
    expect(fleetCard(head(), inputs({ usage: old })).none).toBe('Last reading 3h ago: 41% of 5 hours, which has reset since');
  });
  test('a header-only rate-limit reading says when it was observed instead of claiming none exists', () => {
    const reading: UsagePayload = { window_hours: 5, warn_pct: 80, warn_tokens_5h: 0, heads: [{ key: 'claude-grok', label: 'Grok', usage: {
      output_tokens_5h: 0, entries: 0, ratelimit: { limit_tokens: 53_000_000, remaining_tokens: 53_000_000, reset_tokens: '1h', observed_at: NOW / 1000 - 43 * 3600 },
      warn: { pct: 0, level: 'ok', source: 'none', reset: null },
    } }] };
    expect(fleetCard(head(), inputs({ usage: reading })).none).toBe('Last rate-limit reading 43h ago');
  });
  test.each([null, undefined])('a retained header reading with unknown observation %s does not claim it never existed', (observed_at) => {
    const reading: UsagePayload = { window_hours: 5, warn_pct: 80, warn_tokens_5h: 0, heads: [{ key: 'claude-grok', label: 'Grok', usage: {
      output_tokens_5h: 0, entries: 0, ratelimit: { limit_tokens: 53_000_000, remaining_tokens: 53_000_000, reset_tokens: '1h', ...(observed_at === undefined ? {} : { observed_at }) },
      warn: { pct: 0, level: 'ok', source: 'none', reset: null },
    } }] };
    expect(fleetCard(head(), inputs({ usage: reading })).none).toBeNull();
  });
  test('until the key store answers, an api-key head is called what the daemon calls it', () => {
    expect(fleetCard(head({ key: 'bonsai', label: 'bonsai', authKind: 'api-key' }), inputs({ keys: null, sessions: new Map() })).meta[0]).toBe('api key');
  });
  test('a ready head keeps its quota facts and offers the command to start a session, not a daemon start', () => {
    const card = fleetCard(head(), inputs({ accounts: [account()] }));
    expect(card).toMatchObject({ state: 'Ready', tone: 'work', attention: false, fix: 'copy-launch' });
    expect(card.line).toMatchObject({ kind: 'gauge', name: '5 hours', pct: 41, full: false });
    expect(card.meta).toEqual(['grok', 'Ava’s Grok', '2 sessions']);
  });
  test('an older login uses the shared command fallback, never its plan or hashed account id', () => {
    const auth = { 'claude-grok': { kind: 'grok-oauth', login: '', present: true, account_id_masked: '3460...b1aa' } };
    const meta = fleetCard(head(), inputs({ auth, accounts: [account({ label: null, plan: 'pro' })] })).meta;
    expect(meta).toEqual(['grok', 'claude-grok', '2 sessions']);
    expect(fleetCard(head(), inputs({ auth, accounts: [account({ label: null, plan: 'pro', display_name: 'Personal login' })] })).meta).toEqual(['grok', 'Personal login', '2 sessions']);
    expect(fleetCard(head(), inputs({ auth, accounts: [account({ label: null })] })).meta).toEqual(['grok', 'claude-grok', '2 sessions']);
  });
  test('one proven Claude account in two login places is not a two-account pool', () => {
    const native = account({
      kind: 'client', heads: ['synthetic-head'], label: 'Primary alias', display_name: 'Personal login', identity_verified: true,
      login_place: { id: 'claude', command: 'synthetic-native' },
      account: { uuid: 'synthetic-account', email: 'synthetic@example.invalid' },
    });
    const wrapped = { ...native, label: 'Wrapped alias', login_place: { id: 'claude-splice' as const, command: 'synthetic-wrapped' } };
    const card = fleetCard(head({ key: 'synthetic-head', authKind: 'client' }), inputs({ accounts: [native, wrapped], usage: null, family: 'anthropic' }));
    expect(card.meta).not.toContain('Pool · 2 accounts');
    expect(card.meta).toContain('synthetic@example.invalid');
    expect(card.meta).toContain('Personal login');
    expect(card.meta).not.toContain('Primary alias');
    const unverified = fleetCard(head({ key: 'synthetic-head', authKind: 'client' }), inputs({ accounts: [{ ...native, identity_verified: false }], usage: null, family: 'anthropic' }));
    expect(unverified.meta).toContain('Personal login');
    expect(unverified.meta).not.toContain('synthetic@example.invalid');
  });
  test('matching display email without matching account identity still counts as two accounts', () => {
    const first = account({ account: { uuid: 'synthetic-one', email: 'same@example.invalid' } });
    const second = account({ label: 'Second', account: { uuid: 'synthetic-two', email: 'same@example.invalid' } });
    expect(fleetCard(head(), inputs({ accounts: [first, second] })).meta).toContain('Pool · 2 accounts');
  });
  test('a weekly gauge still reports the five-hour percentage and reset beside it', () => {
    const reading = usage(12);
    const quota = reading.heads[0]?.usage?.quota;
    if (quota === undefined) throw new Error('synthetic quota missing');
    quota.seven_day = { used_pct: 65, resets_at: NOW / 1000 + 86400 };
    const card = fleetCard(head(), inputs({ usage: reading }));
    expect(card.line).toMatchObject({ kind: 'gauge', name: 'Week', pct: 65 });
    expect(card.meta).toContain(`5 hours · 12% · resets ${localZonedInstantText(NOW / 1000 + 3600)} · Observation time not reported`);
    expect(card.meta.at(-1)).toBe('2 sessions');
  });

  test('a head near its warn share says so, without needing a person', () => {
    const card = fleetCard(head(), inputs({ usage: usage(90) }));
    expect(card).toMatchObject({ state: 'Ready', standing: 'ready', tone: 'work', attention: false });
    expect(card.line).toMatchObject({ kind: 'gauge', note: `near its limit · 90%, resets ${localZonedInstantText(NOW / 1000 + 3600)}` });
    expect(fleetLede([card])).toContain('one ready');
  });
  test.each([100, 105])('a %s percent reading without a held refusal stays ready in the command colour', (pct) => {
    const card = fleetCard(head(), inputs({ usage: usage(pct) }));
    expect(card).toMatchObject({ state: 'Ready', standing: 'ready', tone: 'work', colour: 'grok', attention: false, fix: 'copy-launch' });
    expect(card.line).toMatchObject({ kind: 'gauge', pct, full: false, note: `near its limit · ${pct}%, resets ${localZonedInstantText(NOW / 1000 + 3600)}` });
    expect(fleetLede([card])).toContain('one ready');
    expect(fleetLede([card])).not.toContain('out of quota');
  });
  test('a provider refusal is out of quota with its reset, and a pool offers to switch account', () => {
    const until = NOW / 1000 + 7200;
    const card = fleetCard(head({ quotaResetAtEpochSeconds: until }), inputs({ accounts: [account(), account({ label: 'Second' })] }));
    expect(card.state).toBe(`Out of quota until ${localZonedInstantText(until)}`);
    expect(localZonedInstantText(until)).not.toBe(localInstantText(until));
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
  test.each(['bearer', 'grok-oauth'])('the provider-reported API-key kind exposes key recovery for configured auth %s', (authKind) => {
    const key = head({ key: 'custom-key', authKind });
    const auth = { 'custom-key': { kind: 'api-key', login: '', present: false, env_var: 'CUSTOM_API_KEY' } };
    const card = fleetCard(key, inputs({ family: 'openrouter', auth, usage: null, sessions: new Map() }));
    expect(card).toMatchObject({ state: 'Key missing', fix: 'copy-key', keyCommand: 'splice key set CUSTOM_API_KEY' });
    expect(card.line).toMatchObject({ kind: 'note', text: 'Its next turn will fail until its key is set.' });
  });
  test('a latched refresh is an expired sign-in', () => {
    expect(fleetCard(head(), inputs({ auth: { 'claude-grok': { kind: 'grok-oauth', login: '', present: true, refresh_latched: 'invalid_grant' } } })).state).toBe('Sign-in expired');
  });
  test('a stopped head is stopped and can be started; a silent local runtime is off, never an item, and is copied not started', () => {
    expect(fleetCard(head({ running: false }), inputs())).toMatchObject({ state: 'Stopped', attention: false, fix: 'start' });
    const off = fleetCard(head({ authKind: 'local', runtimeNotAnswering: ':8099' }), inputs({ family: 'local' }));
    expect(off).toMatchObject({ state: 'Runtime off', tone: 'idle', attention: false, fix: 'copy-start' });
    expect(off.line).toEqual({ kind: 'note', text: 'The runtime is not answering on :8099.' });
    expect(off.meta[0]).toBe('this computer');
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
    expect(card.meta.join(' ')).toContain(`Last reading 99% · reset ${localZonedInstantText(NOW / 1000 - 60)} · Not current`);
    expect(card.meta.join(' ')).not.toContain(`resets ${localZonedInstantText(NOW / 1000 - 60)}`);
  });
  test('an aged reading before its reset stays visible and is not called reset or current', () => {
    const reading = usage(41);
    const window = reading.heads[0]?.usage?.quota?.five_hour;
    if (window === undefined) throw new Error('synthetic quota missing');
    window.observed_at = NOW / 1000 - 3600;
    window.current = false;
    const card = fleetCard(head(), inputs({ usage: reading }));
    expect(card.line).toBeNull();
    expect(card.none).toBe('Last reading 1h ago: 41% of 5 hours, not current');
    expect(card.meta).toContain(`5 hours · Last reading 41% · resets ${localZonedInstantText(NOW / 1000 + 3600)} · Not current · Observed ${localZonedInstantText(NOW / 1000 - 3600)}`);
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
    expect(fleetLede(stand('ready', 'quota', 'ready', 'off', 'ready', 'signed-out'))).toBe(
      'Six commands: three ready, one out of quota, one needs a sign-in or a key, one switched off. Drag a card to put it where you want it; Sessions follows.',
    );
  });
  test('one command is singular and a standing nobody has is left out', () => {
    expect(fleetLede(stand('other'))).toBe('One command: one in need of a look. Drag a card to put it where you want it; Sessions follows.');
  });
});
