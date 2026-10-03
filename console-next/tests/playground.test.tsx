// V4-444: the Playground sends one prompt to several models and lays their answers side by side. The persona walk found the page
// saying "This page is being rebuilt" while a working one-command form sat in Settings › Health with no pointer to it; the form moved
// here and became several lanes, and Health keeps a link. A command that forwards Claude Code's own login is never offered, since the
// Playground has no login to forward and the daemon refuses it.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { describe, expect, test } from 'vitest';
import { keys } from '../src/api/queries';
import { modelsKey } from '../src/api/models';
import { MAX_LANES, answerOf, canTry, defaultLanes, laneKeys, laneParam, lanesOf, lanesSearch, nextLane } from '../src/lib/playground';
import { PlaygroundPage } from '../src/pages/playground/PlaygroundPage';
import { Health } from '../src/pages/settings/Sections';
import type { HeadStatus } from '../src/types/core';
import type { CatalogModel, HeadCatalog } from '../src/types/models';

const params = (search: string): URLSearchParams => new URLSearchParams(search);

describe('lanes in the address', () => {
  test('a lane is a command alone or a command and a model, split at the first colon, and at most four are read', () => {
    expect(lanesOf(params('try=claudex:gpt-6-luna&try=claudeor&try=claudeor:meta-llama/llama-3:free'))).toEqual([
      { head: 'claudex', model: 'gpt-6-luna' }, { head: 'claudeor', model: null }, { head: 'claudeor', model: 'meta-llama/llama-3:free' },
    ]);
    expect(lanesOf(params('try=&try=:x&try=a:'))).toEqual([{ head: 'a', model: null }]);
    expect(lanesOf(params('try=a&try=b&try=c&try=d&try=e'))).toHaveLength(MAX_LANES);
  });

  test('lanes write back to the same address they were read from', () => {
    const lanes = [{ head: 'claudex', model: 'gpt-6-luna' }, { head: 'claudeor', model: null }];
    expect(lanes.map(laneParam)).toEqual(['claudex:gpt-6-luna', 'claudeor']);
    expect(lanesOf(new URLSearchParams(lanesSearch(lanes)))).toEqual(lanes);
  });

  test('with none asked, the first two commands on their pinned models; a new lane takes the first command not yet used', () => {
    expect(defaultLanes(['a', 'b', 'c'])).toEqual([{ head: 'a', model: null }, { head: 'b', model: null }]);
    expect(nextLane([{ head: 'a', model: null }], ['a', 'b'])).toEqual({ head: 'b', model: null });
    expect(nextLane([{ head: 'a', model: null }, { head: 'b', model: null }], ['a', 'b'])).toEqual({ head: 'a', model: null });
    expect(nextLane(defaultLanes(['a', 'b']).concat(defaultLanes(['c', 'd'])), ['a', 'b', 'c', 'd', 'e'])).toBeNull();
    expect(nextLane([], [])).toBeNull();
  });

  test('a lane keeps its key when another is removed, and two identical lanes keep two keys', () => {
    const lanes = [{ head: 'a', model: null }, { head: 'b', model: 'm' }, { head: 'b', model: 'm' }];
    expect(laneKeys(lanes)).toEqual(['a#0', 'b:m#0', 'b:m#1']);
    expect(laneKeys(lanes.slice(1))).toEqual(['b:m#0', 'b:m#1']);
  });

  test('only a command that forwards the caller\'s own login is left out', () => {
    expect(['client', 'api-key', 'chatgpt-oauth', 'grok-oauth'].map(canTry)).toEqual([false, true, true, true]);
  });
});

describe('an answer, read in each dialect', () => {
  test('Anthropic messages: the text parts, and input and output tokens', () => {
    expect(answerOf(200, { content: [{ type: 'text', text: 'hello' }, { type: 'tool_use' }, { type: 'text', text: 'again' }], usage: { input_tokens: 12, output_tokens: 3 } }))
      .toEqual({ text: 'hello\n\nagain', input: 12, output: 3, error: null });
  });

  test('chat completions: the message content, and prompt and completion tokens', () => {
    expect(answerOf(200, { choices: [{ message: { content: 'hi' } }], usage: { prompt_tokens: 7, completion_tokens: 1 } })).toEqual({ text: 'hi', input: 7, output: 1, error: null });
  });

  test('responses: the output_text of each message item, past the reasoning item', () => {
    const body = { output: [{ type: 'reasoning', summary: [] }, { type: 'message', content: [{ type: 'output_text', text: 'console e2e answer' }] }], usage: { input_tokens: 9, output_tokens: 4 } };
    expect(answerOf(200, body)).toEqual({ text: 'console e2e answer', input: 9, output: 4, error: null });
  });

  const stream = (events: object[]): { raw: string } => ({ raw: events.map((event) => `event: x\ndata: ${JSON.stringify(event)}\n\n`).join('') });

  test('a streamed reply is put together: responses\' text deltas, with the usage its completed event carries', () => {
    const body = stream([
      { type: 'response.output_item.added', output_index: 0, item: { type: 'message' } },
      { type: 'response.output_text.delta', output_index: 0, delta: 'console e2e ' },
      { type: 'response.output_text.delta', output_index: 0, delta: 'answer' },
      { type: 'response.completed', response: { usage: { input_tokens: 12, output_tokens: 4 } } },
    ]);
    expect(answerOf(200, body)).toEqual({ text: 'console e2e answer', input: 12, output: 4, error: null });
  });

  test('an Anthropic stream keeps its input count from the start and takes its output count from the last delta', () => {
    const body = stream([
      { type: 'message_start', message: { usage: { input_tokens: 30, output_tokens: 1 } } },
      { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'Hel' } },
      { type: 'content_block_delta', index: 0, delta: { type: 'input_json_delta', partial_json: '{}' } },
      { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'lo' } },
      { type: 'message_delta', delta: { stop_reason: 'end_turn' }, usage: { output_tokens: 2 } },
    ]);
    expect(answerOf(200, body)).toEqual({ text: 'Hello', input: 30, output: 2, error: null });
  });

  test('a chat stream reads each choice delta and the usage on its last chunk, past the [DONE] line', () => {
    const body = { raw: `${stream([{ choices: [{ delta: { content: 'h' } }] }, { choices: [{ delta: { content: 'i' } }] }, { choices: [], usage: { prompt_tokens: 5, completion_tokens: 2 } }]).raw}data: [DONE]\n\n` };
    expect(answerOf(200, body)).toEqual({ text: 'hi', input: 5, output: 2, error: null });
  });

  test('an error a stream reports is the answer\'s error whatever the status said', () => {
    expect(answerOf(200, stream([{ type: 'error', error: { type: 'overloaded_error', message: 'Overloaded' } }])).error).toBe('Overloaded');
    expect(answerOf(200, stream([{ type: 'response.failed', response: { error: { message: 'model not found' } } }])).error).toBe('model not found');
  });

  test('a provider\'s error sentence is read only from a reply whose status failed, and a raw body is its own text', () => {
    expect(answerOf(429, { error: { message: 'rate limited' } }).error).toBe('rate limited');
    expect(answerOf(400, { error: 'bad model' }).error).toBe('bad model');
    expect(answerOf(200, { error: { message: 'not a failure' } }).error).toBeNull();
    expect(answerOf(502, { raw: 'upstream went away' })).toEqual({ text: 'upstream went away', input: null, output: null, error: null });
    expect(answerOf(200, 'not an object')).toEqual({ text: null, input: null, output: null, error: null });
  });
});

const head = (key: string, authKind: string, label = key): HeadStatus => ({
  key, label, name: key, port: 0, authKind, wantVersion: '', running: true, healthy: true, version: null, versionMatch: null,
  mode: null, gate: null, maxInflight: null, health: {} as HeadStatus['health'], pids: [],
});
const model = (id: string): CatalogModel => ({ id, label: id.toUpperCase(), description: '', slot: null, context_window: null, context_window_source: 'exact', pinned: false, resolved: true });
const CATALOGS: HeadCatalog[] = [
  { head: 'claudex', provider: 'codex', pinned_model: 'gpt-6-sol', models: [model('gpt-6-sol'), model('gpt-6-luna')] },
  { head: 'openrouter', provider: 'openrouter', pinned_model: '', models: [model('free/one')] },
];

function page(path: string, heads: HeadStatus[] = [head('claudex', 'chatgpt-oauth'), head('openrouter', 'api-key', 'claudeor'), head('claude-splice', 'client')]): string {
  const client = new QueryClient();
  client.setQueryData([...keys.heads, '/api/heads'], { heads });
  client.setQueryData([...modelsKey], { heads: CATALOGS });
  return renderToStaticMarkup(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <PlaygroundPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const lanesIn = (html: string): string[] => [...html.matchAll(/<li class="win[^"]*pg-lane" aria-label="([^"]*)"/g)].map((match) => match[1] ?? '');

describe('the Playground page', () => {
  test('opens on two lanes, each command on its pinned model, and names the command it leaves out and why', () => {
    const html = page('/playground');
    expect(html).toContain('<h1>Playground</h1>');
    expect(lanesIn(html)).toEqual(['gpt-6-sol', 'claudeor']);
    expect(html).toContain('claude-splice signs in with Claude Code&#x27;s own login, which the Playground does not have, so it is not offered here.');
    expect(html).not.toContain('aria-label="claude-splice"');
    expect(html).not.toContain('This page is being rebuilt');
  });

  test('reads its lanes from the address, the named model in its field and the pinned one as the placeholder', () => {
    const html = page('/playground?try=claudex:gpt-6-luna&try=claudex&try=openrouter');
    expect(lanesIn(html)).toEqual(['gpt-6-luna', 'gpt-6-sol', 'claudeor']);
    expect(html).toContain('value="gpt-6-luna"');
    expect(html).toContain('placeholder="The pinned model, gpt-6-sol"');
    expect(html).toContain('placeholder="Type or choose a model"');
    expect(html).toContain('<option value="gpt-6-luna">GPT-6-LUNA</option>');
  });

  test('holds the send until there is a prompt, waits in every lane, and stops adding at four lanes', () => {
    const html = page('/playground?try=claudex&try=openrouter');
    expect(html).toMatch(/<button type="submit" class="btn go" disabled="">Send<\/button>/);
    expect(html.match(/Waiting for a prompt\./g)).toHaveLength(2);
    expect(html).toMatch(/<button type="button" class="btn pg-add">/);
    expect(page('/playground?try=claudex&try=claudex&try=openrouter&try=openrouter')).toMatch(/<button type="button" class="btn pg-add" disabled="">/);
  });

  test('with nothing it can try, says so and where to add one', () => {
    const html = page('/playground', [head('claude-splice', 'client')]);
    expect(html).toContain('No command can be tried yet.');
    expect(html).not.toContain('pg-lane');
  });
});

describe('Settings › Health', () => {
  test('keeps a link to the Playground where the one-command form used to be', () => {
    const html = renderToStaticMarkup(<QueryClientProvider client={new QueryClient()}><MemoryRouter><Health /></MemoryRouter></QueryClientProvider>);
    expect(html).toContain('Try a command');
    expect(html).toMatch(/<a class="btn" href="\/playground"[^>]*>Open the Playground<\/a>/);
    expect(html).not.toContain('Choose a command');
  });
});
