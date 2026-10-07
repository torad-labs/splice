// The Add-a-plan draft: what a profile asks, what the request carries, which plans are offered, and when a login saves itself.
import { describe, expect, test } from 'vitest';
import { asksAnything, autoSaveTarget, checkDetail, draftFor, loginRunning, openingField, planChoices, planLabel, ready, requestOf } from '../src/lib/add';
import type { AddProfile, AddView } from '../src/types/add';
import { AD } from '../src/lib/words-add';

const profile = (over: Partial<AddProfile> = {}): AddProfile => ({
  name: 'openrouter', summary: 's', auth_kind: 'api-key', requires_key: true, base_url: 'https://x', head_key: 'openrouter', command: '', models: [], asks: [], ...over,
});
const view = (over: Partial<AddView> = {}): AddView => ({
  id: 'a1', profile: 'codex', key: 'claudex', command: 'claudex', auth_kind: 'chatgpt-oauth', base_url: null, models: [], sign_in_by: 'login', key_env: null,
  credential: { present: false, detail: 'none' }, sign_in: null, checks: null, saved: null, ...over,
});

describe('producer-owned window diagnostics', () => {
  // AddChecks.kt:166–168, with the model id and token counts substituted verbatim.
  const branches = [
    { kind: 'unchecked', ok: true, detail: 'b unchecked: provider lists no window size' },
    { kind: 'oversized', ok: false, detail: 'm declares 200000, provider serves 100000' },
    { kind: 'fits', ok: true, detail: 'a fits: declares 128000, provider serves 128000' },
  ];
  const retired = 'declared rows fit the window sizes the provider lists';
  test.each(branches)('the actual $kind branch stays verbatim', ({ ok, detail }) => {
    expect(detail).not.toBe(retired);
    expect(checkDetail({ name: 'windows', ok, detail })).toBe(detail);
  });
  test('only the retired synthetic diagnostic reached the old summary rewrite', () => {
    expect(branches.every(({ detail }) => detail !== retired)).toBe(true);
    expect(checkDetail({ name: 'windows', ok: true, detail: retired })).toBe(retired);
  });
  test('the retired window summary strings have no production entry', () => {
    expect(AD).not.toHaveProperty('windowFitsDiagnostic');
    expect(AD).not.toHaveProperty('windowFits');
  });
  test('unknown checks keep their producer diagnostic too', () => {
    const detail = 'synthetic future check detail';
    expect(checkDetail({ name: 'future', ok: true, detail })).toBe(detail);
  });
});

test('a window result keeps checked and unchecked rows without claiming every model fits', () => {
  const detail = 'a fits: declares 128000, provider serves 128000; b unchecked: provider lists no window size';
  const shown = checkDetail({ name: 'windows', ok: true, detail });
  expect(shown).toBe(detail);
  expect(shown).toContain('b unchecked');
  expect(shown).not.toContain('Every model');
});

describe('the plans offered', () => {
  test('the six first-hour plans come first, named as a person says them, unavailable where the daemon does not offer them', () => {
    const choices = planChoices([profile({ name: 'codex' }), profile({ name: 'local' })]);
    expect(choices.slice(0, 6).map((choice) => choice.label)).toEqual(['ChatGPT', 'Grok', 'Kimi', 'Muse', 'OpenRouter key', 'Local model']);
    expect(choices.find((choice) => choice.id === 'codex')?.profile).not.toBeNull();
    expect(choices.find((choice) => choice.id === 'grok')?.profile).toBeNull();
  });
  test('every other profile the daemon offers follows under its own name, with its own sentence', () => {
    const choices = planChoices([profile({ name: 'deepseek', summary: 'DeepSeek, compatible' })]);
    expect(choices.at(-1)).toMatchObject({ id: 'deepseek', label: 'DeepSeek', why: 'DeepSeek, compatible' });
    expect(choices).toHaveLength(7);
  });
  test('a plan reads by its name, and a profile with no name of its own reads as its key', () => {
    expect(planLabel('codex')).toBe('ChatGPT');
    expect(planLabel('deepseek')).toBe('DeepSeek');
    expect(planLabel('claude')).toBe('Claude');
    expect(planLabel('api-key')).toBe('API key');
    expect(planLabel('future-provider')).toBe('future-provider');
  });
});

describe('what a profile asks', () => {
  test('a profile that asks nothing opens at once; one that asks needs every ask answered', () => {
    expect(asksAnything(profile())).toBe(false);
    const asking = profile({ asks: ['name', 'base_url', 'models'] });
    expect(asksAnything(asking)).toBe(true);
    const draft = draftFor('openrouter');
    expect(ready(draft, asking)).toBe(false);
    expect(ready({ ...draft, name: 'a', baseUrl: 'u' }, asking)).toBe(false);
    expect(ready({ ...draft, name: 'a', baseUrl: 'u', models: [{ id: 'm', window: '' }] }, asking)).toBe(true);
  });
  test('a window must be whole tokens, and a plan the daemon does not offer is never ready', () => {
    const asking = profile({ asks: ['models'] });
    const draft = { ...draftFor('x'), models: [{ id: 'm', window: '12k' }] };
    expect(ready(draft, asking)).toBe(false);
    expect(ready({ ...draft, models: [{ id: 'm', window: '128000' }] }, asking)).toBe(true);
    expect(ready(draft, null)).toBe(false);
  });
});

describe('opening recovery', () => {
  test('only structured, known refusal fields reveal an input, never error prose', () => {
    expect(openingField({ field: 'command' })).toBe('command');
    expect(openingField({ field: 'name' })).toBe('name');
    expect(openingField({ field: 'base_url' })).toBe('base_url');
    expect(openingField({ field: 'models' })).toBe('models');
    for (const body of [null, 'name', { error: 'pick another name' }, { field: 'future' }]) expect(openingField(body)).toBeNull();
  });
  test('a conflict makes its field required even when the profile originally asked nothing', () => {
    const draft = draftFor('claude');
    expect(ready(draft, profile({ name: 'claude' }), ['name'])).toBe(false);
    expect(ready({ ...draft, name: 'another' }, profile(), ['name', 'command'])).toBe(false);
    expect(ready({ ...draft, name: 'another', command: 'claude-another' }, profile(), ['name', 'command'])).toBe(true);
  });
  test('an explicit command override reaches the daemon, trimmed', () => {
    expect(requestOf({ ...draftFor('claude'), command: ' claude-another ' })).toEqual({ profile: 'claude', command: 'claude-another' });
  });
});

describe('the request', () => {
  test('a blank field sends nothing, so the daemon’s default holds', () => {
    expect(requestOf(draftFor('codex'))).toEqual({ profile: 'codex' });
  });
  test('what was typed goes as typed, trimmed, and only the named models with their windows', () => {
    const body = requestOf({ profile: 'openrouter', name: ' mine ', command: '', baseUrl: ' https://a ', models: [{ id: ' a ', window: ' 1000 ' }, { id: 'b', window: '' }, { id: '', window: '5' }] });
    expect(body).toEqual({ profile: 'openrouter', name: 'mine', base_url: 'https://a', models: [{ id: 'a', context_window: 1000 }, { id: 'b' }] });
  });
});

describe('a login that saves itself', () => {
  test('a credential already there, or landed while the add is open, saves once per add', () => {
    const present = view({ credential: { present: true, detail: 'ok' } });
    expect(autoSaveTarget(present, null)).toBe('a1');
    expect(autoSaveTarget(present, 'a1')).toBeNull();
    expect(autoSaveTarget(view({ credential: { present: true, detail: 'ok' }, sign_in: { id: 'l', state: 'signed_in', user_code: null, verification_uri: null, browser_url: null, failure_reason: null } }), null)).toBe('a1');
  });
  test('nothing saves while the sign-in still runs, after a save, for a key, or with no credential', () => {
    const running = view({ credential: { present: true, detail: 'ok' }, sign_in: { id: 'l', state: 'waiting', user_code: 'X', verification_uri: null, browser_url: null, failure_reason: null } });
    expect(autoSaveTarget(running, null)).toBeNull();
    expect(autoSaveTarget(view(), null)).toBeNull();
    expect(autoSaveTarget(view({ sign_in_by: 'key', credential: { present: true, detail: 'ok' } }), null)).toBeNull();
    expect(autoSaveTarget(view({ credential: { present: true, detail: 'ok' }, saved: { path: 'p', wrapper: { linked: true }, restart: { status: 'draining' } } }), null)).toBeNull();
    expect(autoSaveTarget(null, null)).toBeNull();
  });
  test('the add keeps being read in a background tab while a login is starting or waiting', () => {
    expect(loginRunning(view({ sign_in: { id: 'l', state: 'starting', user_code: null, verification_uri: null, browser_url: null, failure_reason: null } }))).toBe(true);
    expect(loginRunning(view())).toBe(false);
    expect(loginRunning(null)).toBe(false);
  });
});
