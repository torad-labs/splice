// V4-239: the models page's Compare reads what `splice models` prints, GET /api/models/upstream, when
// the operator presses it. The action is proven to call the route through the real client; the board
// prints every declared row with its verdict and collapses the models no row declares behind a reveal,
// as the verb shows eight and counts the rest; a provider that publishes no list, or could not be
// read, says why.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, test, vi } from 'vitest';
import type { UpstreamModelsPayload } from '../src/entities/model';
import { ModelsBoard } from '../src/pages/models';
import { compareUpstream, UpstreamBoard } from '../src/pages/models/upstream';

const h = React.createElement;
const render = (el: React.ReactElement): string => renderToStaticMarkup(el);

/** The route's answer for three providers, one of each roster. */
const PAYLOAD: UpstreamModelsPayload = {
  path: '/home/op/.config/splice/splice.toml',
  providers: [
    {
      key: 'openrouter',
      dialect: 'openai-chat',
      url: 'https://openrouter.ai/api/v1/models',
      roster: 'published',
      agrees: false,
      rows: [
        { id: 'm-1', verdict: 'capped', declared_window: 100_000, upstream_window: 128_000, note: 'this row caps a larger ceiling' },
        { id: 'gone', verdict: 'unserved', declared_window: 50_000, upstream_window: null, note: "the endpoint lists no model 'gone'" },
        { id: 'kept-out-model', verdict: 'excluded', declared_window: null, upstream_window: 8_000, note: 'kept out of the picker' },
        { id: 'discovered-model', verdict: 'new', declared_window: null, upstream_window: 64_000, note: 'discovered' },
      ],
    },
    { key: 'claude', dialect: 'anthropic', url: 'https://api.anthropic.com/v1/models', roster: 'unpublished', reason: 'your own Claude login', agrees: true, rows: [] },
    { key: 'broken', dialect: 'openai-chat', url: 'http://127.0.0.1:9/models', roster: 'unreadable', reason: 'HTTP 500 from http://127.0.0.1:9/models', agrees: false, rows: [] },
  ],
};

interface Sent {
  path: string;
  method: string;
}

function daemon(status: number, body: unknown, sent: Sent[]): void {
  vi.stubGlobal('fetch', async (url: string, init?: RequestInit) => {
    sent.push({ path: url, method: init?.method ?? 'GET' });
    return { ok: status >= 200 && status < 300, status, json: async () => body };
  });
}

describe('Compare calls its route', () => {
  afterEach(() => vi.unstubAllGlobals());

  test('one GET of every provider, and its answer is the comparison', async () => {
    const sent: Sent[] = [];
    daemon(200, PAYLOAD, sent);
    const payloads: UpstreamModelsPayload[] = [];
    const faults: (string | null)[] = [];

    await compareUpstream((payload) => payloads.push(payload), (fault) => faults.push(fault));

    expect(sent).toEqual([{ path: '/api/models/upstream', method: 'GET' }]);
    expect(payloads).toEqual([PAYLOAD]);
    expect(faults).toEqual([null]);
  });

  test('a refusal is kept in the daemon\'s words', async () => {
    daemon(503, { error: 'the daemon wired no provider comparison; /api/models/upstream cannot ask the providers' }, []);
    const faults: (string | null)[] = [];

    await compareUpstream(() => undefined, (fault) => faults.push(fault));

    expect(faults).toEqual(['the daemon wired no provider comparison; /api/models/upstream cannot ask the providers']);
  });

  test('the page offers Compare and reads nothing until it is pressed', () => {
    const sent: Sent[] = [];
    daemon(200, PAYLOAD, sent);
    const catalog = { heads: [{ head: 'claudex', provider: 'openrouter', pinned_model: '', models: [] }] };

    const out = render(h(ModelsBoard, { catalog }));

    expect(out).toContain('>Compare<');
    expect(sent).toEqual([]);
  });
});

describe('the comparison', () => {
  test('every declared row prints with its verdict, and the provider says it needs a decision', () => {
    const out = render(h(UpstreamBoard, { payload: PAYLOAD }));

    expect(out).toContain('>m-1<');
    expect(out).toContain('>Capped<');
    expect(out).toContain('>gone<');
    expect(out).toContain('>Unserved<');
    expect(out).toContain('Needs a decision');
    expect(out).toContain('openai-chat · https://openrouter.ai/api/v1/models');
  });

  test('the models no row declares are counted and collapsed until revealed', () => {
    const out = render(h(UpstreamBoard, { payload: PAYLOAD }));

    expect(out).toContain('Undeclared models 2');
    expect(out).not.toContain('discovered-model');
    expect(out).not.toContain('kept-out-model');
  });

  test('a provider with no list, or one that could not be read, says why', () => {
    const out = render(h(UpstreamBoard, { payload: PAYLOAD }));

    expect(out).toContain('No list');
    expect(out).toContain('your own Claude login');
    expect(out).toContain('Unreadable');
    expect(out).toContain('HTTP 500 from http://127.0.0.1:9/models');
  });

  test('no provider declared is an empty state, never an empty board', () => {
    expect(render(h(UpstreamBoard, { payload: { path: '/x', providers: [] } }))).toContain('No providers');
  });
});
