// V4-346: the first screen names the act a new operator came to complete.
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { AddKey, FleetBoard } from '../src/pages/fleet';
import { HealthCell, healthOf } from '../src/widgets/rule';
import type { FleetSources } from '../src/pages/fleet';
import { planChoices } from '../src/widgets/connect-plan/model';
import { PlanPicker } from '../src/widgets/connect-plan';
import { firstDraft } from '../src/widgets/add-backend/model';
import { OpenAdd, ProfileForm, SavedAdd } from '../src/widgets/add-backend';
import { AccountLogin, LoginTicket, advanceSignInTab, loginTabError, openSignInTab } from '../src/features/account-login';
import { IDLE } from '../src/features/account-login/model';
import type { LoginView } from '../src/entities/auth';
import { startLogin } from '../src/entities/auth';
import type { AddProfile, AddView } from '../src/entities/add';
import type { HeadStatus } from '../src/shared/api';

const profile = (name: string): AddProfile => ({
  name, summary: `${name} profile`, auth_kind: 'api-key', base_url: null,
  head_key: name, command: `claude-${name}`, models: [], asks: [],
});

const emptySources: FleetSources = {
  auth: null, usage: null, accounts: null, topology: null, catalogs: null,
  fieldsPending: false, topologyStale: false, landed: [], lastTs: new Map(), overrides: [],
};

const liveHead: HeadStatus = {
  key: 'claudex', label: 'claudex', name: 'claudex', port: 3099, authKind: 'chatgpt-oauth',
  wantVersion: '0.4.0', running: true, healthy: true, version: '0.4.0', versionMatch: true,
  mode: null, gate: null, maxInflight: 4, health: { localOriginErrors: 0, providerErrors: 0 }, pids: [1],
};

describe('first-hour connection', () => {
  afterEach(() => vi.unstubAllGlobals());

  test('Sign in opens a neutral tab synchronously and then sends it to the provider', () => {
    const popup = {
      opener: {}, document: { title: '', body: { textContent: '' } }, location: { href: '' }, close: vi.fn(),
    } as unknown as Window;
    const open = vi.fn(() => popup);
    vi.stubGlobal('window', { open });

    const tab = openSignInTab();
    expect(open).toHaveBeenCalledWith('', '_blank');
    expect(popup.document.body?.textContent).toContain('Opening sign-in');
    expect(popup.opener).toBeNull();
    const status: LoginView = {
      id: 'login-1', state: 'waiting', user_code: null, verification_uri: null,
      browser_url: 'https://login.example/authorize', failure_reason: null,
    };
    expect(advanceSignInTab(tab, status, null)).toBeNull();
    expect(popup.location.href).toBe(status.browser_url);
  });

  test('a real refused login POST closes its opened tab with the daemon error', async () => {
    vi.stubGlobal('fetch', async () => ({ ok: false, status: 503, json: async () => ({ error: 'login cannot start' }) }));
    const close = vi.fn();
    const tab = { location: { href: '' }, close } as unknown as Window;
    const refused = await startLogin('claudex', 'work').then(() => null, (error: Error) => error.message);
    expect(refused).toContain('login cannot start');
    expect(advanceSignInTab(tab, null, refused)).toBeNull();
    expect(close).toHaveBeenCalledOnce();
  });

  test('blocked popup leaves the named sign-in link usable', () => {
    vi.stubGlobal('window', { open: () => null });
    expect(openSignInTab()).toBeNull();
    const status: LoginView = {
      id: 'login-2', state: 'waiting', user_code: null, verification_uri: null,
      browser_url: 'https://login.example/authorize', failure_reason: null,
    };
    expect(renderToStaticMarkup(createElement(LoginTicket, { status }))).toContain('Open sign-in page');
  });

  test('cancel and unsupported login both close a waiting tab', () => {
    expect(loginTabError(IDLE)).toBe('Cancel');
    expect(loginTabError({ ...IDLE, step: 'pending', note: 'login route unavailable' })).toBe('login route unavailable');
    const close = vi.fn();
    const tab = { location: { href: '' }, close } as unknown as Window;
    expect(advanceSignInTab(tab, null, loginTabError({ ...IDLE, step: 'pending', note: 'unavailable' }))).toBeNull();
    expect(close).toHaveBeenCalledOnce();
  });

  test('a failed login request closes the waiting tab and leaves an error path', () => {
    const close = vi.fn();
    const tab = { location: { href: '' }, close } as unknown as Window;
    expect(advanceSignInTab(tab, null, 'POST rejected')).toBeNull();
    expect(close).toHaveBeenCalledOnce();
  });

  test('a measured zero-head daemon says Not set up rather than green or degraded', () => {
    const state = healthOf(false, false, false, 0);
    expect(state).toBe('setup');
    expect(renderToStaticMarkup(createElement(HealthCell, { health: state }))).toContain('Not set up');
    expect(healthOf(false, true, false, 1)).toBe('amber');
    expect(healthOf(true, false, false, 0)).toBe('red');
    expect(healthOf(false, false, false, null)).toBe('reading');
    expect(healthOf(false, false, false, null, true)).toBe('unread');
  });

  test('an expired login offers Sign in again inside the affected head', () => {
    const sources: FleetSources = {
      ...emptySources, auth: { claudex: { kind: 'chatgpt-oauth', login: 'x', present: true, refresh_latched: 'expired' } },
    };
    const html = renderToStaticMarkup(createElement(FleetBoard, {
      heads: [liveHead], sources, openKey: liveHead.key, onOpen: () => undefined, nowMs: 1_800_000_000_000,
    }));
    expect(html).toContain('Sign in again');
    expect(html).toContain('Expired');
  });

  test('a configured API-key head keeps its missing-key warning instead of offering OAuth login', () => {
    const keyed = { ...liveHead, key: 'openrouter', label: 'OpenRouter', authKind: 'api-key' };
    const sources: FleetSources = {
      ...emptySources, auth: { openrouter: { kind: 'api-key', login: 'manual', present: false, env_var: 'OPENROUTER_API_KEY' } },
    };
    const html = renderToStaticMarkup(createElement(FleetBoard, {
      heads: [keyed], sources, openKey: keyed.key, onOpen: () => undefined, nowMs: 1_800_000_000_000,
    }));
    expect(html).toContain('No key');
    expect(html).toContain('splice key set OPENROUTER_API_KEY');
    expect(html).not.toContain('Sign in again');
  });

  test('a zero-head Fleet shows setup instead of a failed-head board', () => {
    const html = renderToStaticMarkup(createElement(FleetBoard, {
      heads: [], sources: emptySources, openKey: null, onOpen: () => undefined, nowMs: 1_800_000_000_000,
    }));
    expect(html).toContain('Connect a plan');
    expect(html).not.toContain('No heads yet');
    expect(html).not.toContain('Failing');
  });

  test('all six plans have honest profile matches, including separate OpenRouter and local choices', () => {
    const choices = planChoices([profile('codex'), profile('grok'), profile('openrouter'), profile('local')]);
    expect(choices.map((choice) => choice.label)).toEqual([
      'ChatGPT', 'Grok', 'Kimi', 'Muse', 'OpenRouter key', 'Local model',
    ]);
    expect(choices.map((choice) => choice.profile?.name ?? null)).toEqual([
      'codex', 'grok', null, null, 'openrouter', 'local',
    ]);
  });

  test('the first screen offers six readable choices and marks unavailable profiles honestly', () => {
    const choices = planChoices([profile('codex'), profile('openrouter'), profile('api-key')]);
    const html = renderToStaticMarkup(createElement(PlanPicker, { choices, onSelect: () => undefined, onOther: () => undefined }));
    expect(html).toContain('ChatGPT');
    expect(html).toContain('OpenRouter key');
    expect(html).toContain('Local model');
    expect(html).toContain('Kimi');
    expect(html).toContain('Other providers');
    expect(html).toContain('Unavailable in this splice build.');
    expect(html).toContain('disabled');
    expect(html).not.toContain('Profile: codex');
  });

  test('a chosen plan beats catalogue order and never silently falls back', () => {
    const rows = [profile('grok'), profile('codex')];
    expect(firstDraft(rows, 'codex').profile).toBe('codex');
    expect(firstDraft(rows, 'missing').profile).toBe('');
    expect(firstDraft(rows).profile).toBe('grok');
  });

  test('a chosen plan does not expose the catalogue profile key as the primary action', () => {
    const rows = [profile('codex')];
    const html = renderToStaticMarkup(createElement(ProfileForm, {
      profiles: rows, draft: firstDraft(rows, 'codex'), pinned: true,
      onDraft: () => undefined, busy: false, onOpen: () => undefined,
    }));
    expect(html).not.toContain('Profile: codex');
    expect(html).not.toContain('myx-choice-label');
    expect(html).toContain('codex profile');
  });

  test('a saved, linked head hands back the exact copyable command', () => {
    const view: AddView = {
      id: 'open-1', profile: 'codex', key: 'codex', command: 'claudex',
      auth_kind: 'chatgpt-oauth', base_url: null, models: [], sign_in_by: 'login', key_env: null,
      credential: { present: true, detail: 'signed in' }, sign_in: null, checks: null,
      saved: { path: '/work/splice.toml', wrapper: { linked: true }, restart: { status: 'draining' } },
    };
    const html = renderToStaticMarkup(createElement(SavedAdd, { view }));
    expect(html).toContain('<code>claudex</code>');
    expect(html).toContain('Copy');
    const unlinked = renderToStaticMarkup(createElement(SavedAdd, {
      view: { ...view, saved: { path: '/work/splice.toml', wrapper: { linked: false }, restart: { status: 'draining' } } },
    }));
    expect(unlinked).not.toContain('<code>claudex</code>');
  });

  test('a local runtime without a key does not claim to forward a Claude login', () => {
    const view: AddView = {
      id: 'local-1', profile: 'local', key: 'local', command: 'claude-local',
      auth_kind: 'api-key', base_url: 'http://127.0.0.1:8080/v1', models: [],
      sign_in_by: 'none', key_env: null, credential: { present: true, detail: 'no key needed' },
      sign_in: null, checks: null, saved: null,
    };
    const html = renderToStaticMarkup(createElement(OpenAdd, {
      view, checks: null, busy: false, onSignIn: () => undefined, onVerify: () => undefined,
      onSave: () => undefined, onDiscard: () => undefined,
    }));
    expect(html).toContain('No key is needed for this local runtime.');
    expect(html).toContain('No key needed');
    expect(html).not.toContain('Claude login');
    expect(html).not.toContain('Store key');
  });

  test('browser sign-in offers a named external action without painting the raw authorization URL', () => {
    const status: LoginView = {
      id: 'login-1', state: 'waiting', user_code: null, verification_uri: null,
      browser_url: 'https://login.example/authorize?state=private-fixture', failure_reason: null,
    };
    const html = renderToStaticMarkup(createElement(LoginTicket, { status }));
    expect(html).toContain('Open sign-in page');
    expect(html).toContain('target="_blank"');
    expect(html).toContain('rel="noopener noreferrer"');
    expect(html).not.toContain('>https://login.example/authorize');
  });

  test('device login keeps its code and names its verification page', () => {
    const status: LoginView = {
      id: 'login-2', state: 'waiting', user_code: 'AB-12', browser_url: null,
      verification_uri: 'https://login.example/device', failure_reason: null,
    };
    const html = renderToStaticMarkup(createElement(LoginTicket, { status }));
    expect(html).toContain('AB-12');
    expect(html).toContain('Open verification page');
    expect(html).not.toContain('>https://login.example/device');
    const tab = { location: { href: '' }, close: vi.fn() } as unknown as Window;
    expect(advanceSignInTab(tab, status, null)).toBeNull();
    expect(tab.location.href).toBe(status.verification_uri);
  });

  test('a non-provider URL cannot become an executable login link', () => {
    const status: LoginView = {
      id: 'login-3', state: 'waiting', user_code: null, verification_uri: null,
      browser_url: 'javascript:alert(1)', failure_reason: null,
    };
    const html = renderToStaticMarkup(createElement(LoginTicket, { status }));
    expect(html).not.toContain('href="javascript:');
    expect(html).not.toContain('Copy Link');
  });

  test('an announced unsafe login URL closes the waiting tab', () => {
    const close = vi.fn();
    const tab = { location: { href: '' }, close } as unknown as Window;
    const status: LoginView = {
      id: 'login-bad', state: 'waiting', user_code: null, verification_uri: null,
      browser_url: 'javascript:alert(1)', failure_reason: null,
    };
    expect(advanceSignInTab(tab, status, null)).toBeNull();
    expect(close).toHaveBeenCalledOnce();
  });

  test('an expired plan offers an in-place sign-in again rather than Add account', () => {
    const html = renderToStaticMarkup(createElement(AccountLogin, { head: 'claudex', purpose: 'renew' }));
    expect(html).toContain('Sign in again');
    expect(html).not.toContain('Add account');
  });

  test('the first action is called Connect a plan, not Add backend', () => {
    const html = renderToStaticMarkup(createElement(AddKey, { onAdd: () => undefined }));
    expect(html).toContain('Connect a plan');
    expect(html).not.toContain('Add backend');
  });
});
