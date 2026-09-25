// V4-220 item 3's console half: the add form over /api/add (AddRoutes.kt, #291).
//
// THE WIRE IS READ FROM THE KOTLIN, as the login's is (tests/login-wire.test.ts): an add id exists
// only after a POST, so the wire-keys probe cannot read GET /api/add/{id}, and a type written from a
// plan would agree with every fixture written from it. Each AddViews serializer's keys are the
// denominator, and the console's types are held to them.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { discardAdd, openAdd, saveAdd, verifyAdd } from '../src/entities/add';
import type { AddCheck, AddModel, AddProfile, AddRestart, AddSaved, AddView } from '../src/entities/add';
import { OpenAdd, ProfileForm, SavedAdd } from '../src/widgets/add-backend';
import { draftFor, ready, requestOf } from '../src/widgets/add-backend/model';
import { H, S } from '../src/widgets/add-backend/strings';
import { S as KEY_WORDS } from '../src/features/api-key/strings';
import { keysPut } from './lib/kotlin-views';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const VIEWS = readFileSync(path.join(repoRoot, 'features/configuration/src/main/kotlin/splice/configuration/add/AddViews.kt'), 'utf8');

const keysOf = (signature: string): string[] => keysPut(VIEWS, signature, 'AddViews.kt');

const sorted = (...lists: readonly string[][]): string[] => [...new Set(lists.flat())].sort();

// `Required` holds each object to its whole interface, so a key a type gains or loses moves here.
const MODEL: Required<AddModel> = { id: '', label: '', context_window: 0, slots: [] };
const PROFILE: Required<AddProfile> = { name: '', summary: '', auth_kind: '', base_url: null, head_key: '', command: '', models: [], asks: [] };
const CHECK: Required<AddCheck> = { name: '', ok: true, detail: '' };
const RESTART: Required<AddRestart> = { status: 'draining', error: '', compactions: [{ head: '', age_ms: 0 }] };
const SAVED: Required<AddSaved> = { path: '', wrapper: { linked: true, error: '' }, restart: RESTART };
const VIEW: Required<AddView> = {
  id: 'a1', profile: 'grok', key: 'claude-grok', command: 'claude-grok', auth_kind: 'api-key', base_url: 'https://api.x.ai/v1',
  models: [{ id: 'grok-4.7', label: 'Grok 4.7', context_window: 256000, slots: [] }], sign_in_by: 'key', key_env: 'XAI_API_KEY',
  credential: { present: false, detail: 'XAI_API_KEY is not set' }, sign_in: null, checks: null, saved: null,
};

describe('the add\'s wire is AddViews\' own', () => {
  test('a profile, a model and a check put exactly the keys their types declare', () => {
    expect(keysOf('profile(')).toEqual(sorted(Object.keys(PROFILE)));
    expect(keysOf('model(')).toEqual(sorted(Object.keys(MODEL)));
    expect(keysOf('checks(')).toEqual(sorted(Object.keys(CHECK)));
  });

  test('the session view puts exactly AddView\'s keys and its credential\'s', () => {
    expect(keysOf('session(')).toEqual(sorted(Object.keys(VIEW), Object.keys(VIEW.credential)));
  });

  test('a save puts its path, its wrapper\'s and its restart\'s keys, and the restart its compactions\'', () => {
    expect(keysOf('saved(')).toEqual(sorted(['path', 'wrapper', 'restart'], Object.keys(SAVED.wrapper)));
    expect(keysOf('restart(taken')).toEqual(sorted(Object.keys(RESTART), Object.keys(RESTART.compactions[0] ?? {})));
  });

  test('sign_in_by is one of the three words the daemon writes', () => {
    const words = [...VIEWS.matchAll(/private const val SIGN_IN_[A-Z]+ = "([a-z]+)"/g)].flatMap(([, word]) => (word === undefined ? [] : [word])).sort();
    expect(words).toEqual(['key', 'login', 'none']);
  });
});

interface Sent {
  path: string;
  method: string;
  body: string | undefined;
}

function daemon(status: number, body: unknown, sent: Sent[]): void {
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    sent.push({ path: url, method: init?.method ?? 'GET', body: typeof init?.body === 'string' ? init.body : undefined });
    return { ok: status >= 200 && status < 300, status, json: async () => body };
  });
}

describe('the add through the real client', () => {
  afterEach(() => vi.unstubAllGlobals());

  test('an open is one POST of the CLI\'s arguments, and its answer is the view', async () => {
    const sent: Sent[] = [];
    daemon(200, VIEW, sent);
    expect(await openAdd({ profile: 'grok' })).toEqual(VIEW);
    expect(sent).toEqual([{ path: '/api/add', method: 'POST', body: '{"profile":"grok"}' }]);
  });

  test('a failed check is an answer with its rows, not a fault; a live check asks for its turn', async () => {
    const sent: Sent[] = [];
    const rows = [{ name: 'credential', ok: false, detail: 'XAI_API_KEY is not set' }];
    daemon(409, { error: 'The credential check failed: XAI_API_KEY is not set.', checks: rows }, sent);
    expect(await verifyAdd('a1', true)).toEqual({ failed: { error: 'The credential check failed: XAI_API_KEY is not set.', checks: rows } });
    expect(sent[0]).toEqual({ path: '/api/add/a1/verify', method: 'POST', body: '{"live":true}' });
  });

  test('a save\'s other refusals throw the daemon\'s sentence, and a discard is one DELETE', async () => {
    daemon(409, { error: 'splice.toml changed under this add' }, []);
    await expect(saveAdd('a1')).rejects.toThrow('splice.toml changed under this add');
    const sent: Sent[] = [];
    daemon(200, { discarded: 'a1' }, sent);
    await discardAdd('a1');
    expect(sent).toEqual([{ path: '/api/add/a1', method: 'DELETE', body: undefined }]);
  });
});

describe('the draft asks what the profile asks', () => {
  const custom: AddProfile = { ...PROFILE, name: 'api-key', head_key: '', base_url: null, asks: ['name', 'base_url', 'models'] };

  test('a profile that asks a name, a base URL and models opens only once all three are there', () => {
    let draft = draftFor('api-key');
    expect(ready(draft, custom)).toBe(false);
    draft = { ...draft, name: 'claude-local', baseUrl: 'http://127.0.0.1:8080/v1' };
    expect(ready(draft, custom)).toBe(false);
    draft = { ...draft, models: [{ id: 'qwen3', window: '' }] };
    expect(ready(draft, custom)).toBe(true);
    expect(ready({ ...draft, models: [{ id: 'qwen3', window: '32k' }] }, custom), 'a window is whole tokens').toBe(false);
  });

  test('the request sends each field the operator filled, and a blank one leaves the default', () => {
    expect(requestOf(draftFor('grok'))).toEqual({ profile: 'grok' });
    const draft = { ...draftFor('api-key'), name: ' claude-local ', baseUrl: 'http://127.0.0.1:8080/v1', models: [{ id: 'qwen3', window: '32768' }, { id: 'phi', window: '' }, { id: '', window: '' }] };
    expect(requestOf(draft)).toEqual({
      profile: 'api-key', name: 'claude-local', base_url: 'http://127.0.0.1:8080/v1',
      models: [{ id: 'qwen3', context_window: 32768 }, { id: 'phi' }],
    });
  });

  test('the form shows a base URL only where the profile asks one', () => {
    const grok: AddProfile = { ...PROFILE, name: 'grok', head_key: 'claude-grok', base_url: 'https://api.x.ai/v1', asks: [] };
    const form = (profiles: AddProfile[], name: string) => renderToStaticMarkup(createElement(ProfileForm, {
      profiles, draft: draftFor(name), onDraft: () => undefined, busy: false, onOpen: () => undefined,
    }));
    expect(form([grok, custom], 'grok')).not.toContain(`>${S.baseUrl}<`);
    expect(form([grok, custom], 'api-key')).toContain(`>${S.baseUrl}<`);
  });
});

describe('an open add and a saved one', () => {
  const open = (view: AddView) => renderToStaticMarkup(createElement(OpenAdd, {
    view, checks: null, busy: false, onSignIn: () => undefined, onVerify: () => undefined, onSave: () => undefined, onDiscard: () => undefined,
  }));

  test('a key-signed head stores its key with the Accounts key form, and offers the live check', () => {
    const out = open(VIEW);
    expect(out).toContain(`>${KEY_WORDS.store}<`);
    expect(out).toContain(H.key('XAI_API_KEY'));
    expect(out).toContain(`>${S.liveCheck}<`);
    expect(out).toContain(`>${S.missing}<`);
  });

  test('a login-signed head prints the flow\'s code and link once it announces them', () => {
    const out = open({
      ...VIEW, sign_in_by: 'login', key_env: null,
      sign_in: { id: 'L1', state: 'waiting', user_code: 'AB-12', verification_uri: 'https://auth.example/device', browser_url: null, failure_reason: null },
    });
    expect(out).toContain(`>${S.signIn}<`);
    expect(out).toContain('>AB-12<');
    expect(out).not.toContain(`>${S.liveCheck}<`);
  });

  test('a client head has nothing to sign in', () => {
    expect(open({ ...VIEW, sign_in_by: 'none', key_env: null })).toContain(H.forwarded);
  });

  test('a save says the restart it took, and a refused one prints the daemon\'s reason', () => {
    const saved = (restart: AddRestart, wrapper: AddSaved['wrapper'] = { linked: true }) => renderToStaticMarkup(createElement(SavedAdd, {
      view: { ...VIEW, saved: { path: '/home/op/.config/splice/splice.toml', wrapper, restart } },
    }));
    expect(saved({ status: 'draining' })).toContain(H.draining);
    expect(saved({ status: 'waiting', compactions: [{ head: 'claudex', age_ms: 900 }] })).toContain(H.waiting(1));
    const refused = saved({ status: 'refused', error: 'nothing will restart this daemon' }, { linked: false, error: 'The claude-grok command was not linked; run splice install claude-grok.' });
    expect(refused).toContain('nothing will restart this daemon');
    expect(refused).toContain('run splice install claude-grok');
  });
});
