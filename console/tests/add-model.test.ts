// V4-220's add-model, the console half: Models adds catalogue models to an OpenRouter head over
// GET and POST /api/add-model (AddModelRoutes.kt, #300).
//
// THE WIRE IS READ FROM THE KOTLIN, as the add's is: the offers and the answer are AddViews members,
// and the request is what AddModelRequestReader reads, so the console's types are held to those.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { addModels, fetchAddModelOffers } from '../src/entities/add';
import type { AddModelOffer, AddModelOffers, AddModelsAdded, AddRestart } from '../src/entities/add';
import { AddedModels, PickModels } from '../src/features/add-model';
import { offerOf, pickedIds, toggled } from '../src/features/add-model/model';
import { H, S } from '../src/features/add-model/strings';
import { keysPut } from './lib/kotlin-views';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const ADD = path.join(repoRoot, 'features/configuration/src/main/kotlin/splice/configuration/add');
const VIEWS = readFileSync(path.join(ADD, 'AddViews.kt'), 'utf8');
const ROUTES = readFileSync(path.join(ADD, 'AddModelRoutes.kt'), 'utf8');

const sorted = (...lists: readonly string[][]): string[] => [...new Set(lists.flat())].sort();

const GROK = { id: 'x-ai/grok-4.7', label: 'Grok 4.7', context_window: 256000, slots: [] };
const QWEN = { id: 'qwen/qwen3-max', label: 'Qwen3 Max', context_window: 262144, slots: [] };
// `Required` holds each object to its whole interface, so a key a type gains or loses moves here.
const OFFER: Required<AddModelOffer> = { head: 'claude-openrouter', provider: 'openrouter', models: [GROK, QWEN] };
const OFFERS: Required<AddModelOffers> = { path: '/home/op/.config/splice/splice.toml', heads: [OFFER] };
const DRAINING: AddRestart = { status: 'draining' };
const ADDED: Required<AddModelsAdded> = { path: OFFERS.path, head: OFFER.head, added: [GROK.id], restart: DRAINING };

describe('the add-model wire is AddViews\' and AddModelRoutes\' own', () => {
  test('the offers put exactly the keys the offers and each head\'s offer declare', () => {
    expect(keysPut(VIEWS, 'offers(', 'AddViews.kt')).toEqual(sorted(Object.keys(OFFERS), Object.keys(OFFER)));
  });

  test('an add\'s answer puts exactly the keys its type declares', () => {
    expect(keysPut(VIEWS, 'added(', 'AddViews.kt')).toEqual(sorted(Object.keys(ADDED)));
  });

  test('the request reader reads exactly the two fields the client sends', () => {
    const read = [...ROUTES.matchAll(/(?:str\(body, |body\?\.get\()"([a-z_]+)"/g)].flatMap(([, key]) => (key === undefined ? [] : [key])).sort();
    expect(read).toEqual(['head', 'models']);
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

describe('add-model through the real client', () => {
  afterEach(() => vi.unstubAllGlobals());

  test('the offers are one GET', async () => {
    const sent: Sent[] = [];
    daemon(200, OFFERS, sent);
    expect(await fetchAddModelOffers()).toEqual(OFFERS);
    expect(sent).toEqual([{ path: '/api/add-model', method: 'GET', body: undefined }]);
  });

  test('an add is one POST of the head and the ids, and its answer is what was added', async () => {
    const sent: Sent[] = [];
    daemon(200, ADDED, sent);
    expect(await addModels(OFFER.head, [GROK.id, QWEN.id])).toEqual(ADDED);
    expect(sent).toEqual([{ path: '/api/add-model', method: 'POST', body: '{"head":"claude-openrouter","models":["x-ai/grok-4.7","qwen/qwen3-max"]}' }]);
  });

  test('a refused add rejects with the daemon\'s sentence', async () => {
    daemon(409, { error: '\'x-ai/grok-4.7\' is not on offer for \'claude-openrouter\': it is on its roster already, or not in the catalogue.' }, []);
    await expect(addModels(OFFER.head, [GROK.id])).rejects.toThrow('is not on offer for');
  });
});

describe('the pick\'s rules', () => {
  const second: AddModelOffer = { head: 'claude-openrouter-2', provider: 'openrouter', models: [QWEN] };

  test('the form adds to the head picked, else the file\'s first OpenRouter head', () => {
    expect(offerOf([OFFER, second], 'claude-openrouter-2')).toBe(second);
    expect(offerOf([OFFER, second], null)).toBe(OFFER);
    expect(offerOf([OFFER, second], 'gone')).toBe(OFFER);
    expect(offerOf([], null)).toBeNull();
  });

  test('the ids sent are the picks still on offer, in the catalogue\'s order', () => {
    expect(pickedIds(OFFER, new Set([QWEN.id, GROK.id, 'gone/model']))).toEqual([GROK.id, QWEN.id]);
    expect(pickedIds(second, new Set([GROK.id]))).toEqual([]);
  });

  test('a switch puts an id in and takes it out, leaving the set it was given alone', () => {
    const before = new Set([GROK.id]);
    expect([...toggled(before, QWEN.id, true)]).toEqual([GROK.id, QWEN.id]);
    expect([...toggled(before, GROK.id, false)]).toEqual([]);
    expect([...before]).toEqual([GROK.id]);
  });
});

describe('the pick and the answer, rendered', () => {
  const pick = (offers: AddModelOffers, picked: string[] = []) => renderToStaticMarkup(createElement(PickModels, {
    offers, head: null, picked: new Set(picked), busy: false, onHead: () => undefined, onPick: () => undefined, onAdd: () => undefined,
  }));

  test('each offered model is a switch that names it, and the add key counts the picks', () => {
    const none = pick(OFFERS);
    expect(none).toContain(`aria-label="${S.pickModel(GROK.id)}"`);
    expect(none).toContain(`>${GROK.id}<`);
    expect(none, 'no add key before a pick, not even one that adds nothing').not.toMatch(/>Add \d+ models?</);
    expect(pick(OFFERS, [GROK.id, QWEN.id])).toContain(`>${S.add(2)}<`);
  });

  test('one head is named; two are a choice', () => {
    expect(pick(OFFERS)).not.toContain('role="combobox"');
    expect(pick({ ...OFFERS, heads: [OFFER, { ...OFFER, head: 'claude-openrouter-2' }] })).toContain('role="combobox"');
  });

  test('a head with nothing left says so, and a file with no OpenRouter head says where to add one', () => {
    expect(pick({ ...OFFERS, heads: [{ ...OFFER, models: [] }] })).toContain(H.allOffered);
    expect(pick({ ...OFFERS, heads: [] })).toContain(H.noHeads);
  });

  test('an add says what it wrote and the restart it took, and a refused restart prints why', () => {
    const answer = (restart: AddRestart) => renderToStaticMarkup(createElement(AddedModels, { added: { ...ADDED, restart } }));
    expect(answer(DRAINING)).toContain(H.draining);
    expect(answer(DRAINING)).toContain(`>${GROK.id}<`);
    expect(answer({ status: 'waiting', compactions: [{ head: 'claudex', age_ms: 900 }] })).toContain(H.waiting(1));
    expect(answer({ status: 'refused', error: 'nothing will restart this daemon' })).toContain('nothing will restart this daemon');
  });
});
