// WALLS for the playground's request (M4-03). The reducer's promise, that the console stores no
// body, is pinned in mcp-doctor.test.ts; this file pins the other half, the wire: what the console
// SENDS is exactly the body PlaygroundRoute parses (PlaygroundRoute.kt:70-71), and what comes back is
// handed over whole, because the page's job is to show it raw.
import { afterEach, describe, expect, test, vi } from 'vitest';
import { runPlayground } from '../src/entities/playground';
import type { PlaygroundWire } from '../src/entities/playground';
import { MgmtError } from '../src/shared/api';

interface Sent {
  path: string;
  method: string | undefined;
  body: unknown;
}

function transport(status: number, reply: unknown, sent: Sent[]): void {
  vi.stubGlobal('fetch', async (path: string, init?: RequestInit) => {
    sent.push({ path, method: init?.method, body: typeof init?.body === 'string' ? JSON.parse(init.body) : init?.body });
    return { ok: status >= 200 && status < 300, status, json: async () => reply };
  });
}

/** One answer as UpstreamPlaygroundProbe writes it (UpstreamPlaygroundProbe.kt:132-141), captured from
 *  the e2e stack's daemon on 2026-09-23: the upstream's body was an event stream, so it is `raw`. */
const WIRE: PlaygroundWire = {
  request: {
    url: 'http://127.0.0.1:40009/responses',
    method: 'POST',
    headers: { Authorization: '[redacted]' },
    body: { model: 'e2e-model', input: 'hello' },
  },
  response: { status: 200, body: { raw: 'event: response.output_text.delta\ndata: {"delta":"hi"}\n\n' } },
};

describe('POST /api/playground through the real client', () => {
  afterEach(() => vi.unstubAllGlobals());

  test('one prompt goes out as the {head, prompt} body the route parses, and the wire comes back whole', async () => {
    const sent: Sent[] = [];
    transport(200, WIRE, sent);
    expect(await runPlayground('e2e-codex', 'hello')).toEqual(WIRE);
    expect(sent).toEqual([{ path: '/api/playground', method: 'POST', body: { head: 'e2e-codex', prompt: 'hello' } }]);
  });

  test('a run the daemon refused rejects with its own sentence', async () => {
    transport(502, { error: 'head \'e2e-openrouter\' has no credential configured' }, []);
    const refusal = await runPlayground('e2e-openrouter', 'x').then(() => null, (err: unknown) => err);
    expect(refusal).toBeInstanceOf(MgmtError);
    expect((refusal as MgmtError).message).toBe('head \'e2e-openrouter\' has no credential configured');
  });
});
