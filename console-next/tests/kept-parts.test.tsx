// What a turn keeps, rendered from a seeded cache: the failure sentence under its own turn, and the body capture control.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { describe, expect, test } from 'vitest';
import { captureState, savedCapture } from '../src/lib/turns-page';
import { CaptureControl } from '../src/pages/turns/CaptureControl';
import { Failure, KeptTabs } from '../src/pages/turns/KeptTabs';
import { PlansKept } from '../src/pages/settings/Kept';
import type { CaptureWire, KeptTurn, TurnRow } from '../src/types/perf';
import type { TopologyState } from '../src/types/topology';

const row = (turn: string): TurnRow => ({ head: 'claude-solo', ts: 1_000_000, model: 'm', outcome: 'error:conn-reset', compact: false, turn });
const kept = (id: string, sentence: string | null): Extract<KeptTurn, { read: unknown }> => ({
  read: { head: 'claude-solo', turn: { id, ts: 1, session: null, model: 'm', compact: false, open: false, outcome: 'error:conn-reset', failure_sentence: sentence, rounds: 1, attempts: 1, total_ms: 20 }, records: [] },
});
const capture = (enabled: boolean): CaptureWire => ({ head: 'claude-solo', enabled, retention_days: 7, max_body_chars: 1000, restart_required: true });
const page = (element: React.ReactElement, seed: (client: QueryClient) => void): string => {
  const client = new QueryClient();
  seed(client);
  return renderToStaticMarkup(<QueryClientProvider client={client}><MemoryRouter>{element}</MemoryRouter></QueryClientProvider>);
};

describe('a kept panel still being read', () => {
  test.each([
    ['conversation', 'Reading the conversation…'],
    ['request', 'Reading the kept request and answer…'],
  ])('%s says what is being read rather than leaving a blank panel', (tab, sentence) => {
    const pending = { ...row('a'), session_id: 'session-a', response_message_id: 'response-a' };
    const html = page(<KeptTabs row={pending} plan="Solo" tab={tab} />, () => undefined);
    expect(html).toContain(`<p class="kept-note" role="status">${sentence}</p>`);
  });
});

test('a request with a session but no reply says which lookup identity is absent', () => {
  const unanswered = { ...row('a'), session_id: 'session-a' };
  const html = page(<KeptTabs row={unanswered} plan="Solo" tab={null} />, () => undefined);
  expect(html).toContain('No response message was recorded for this request');
  expect(html).not.toContain('This request carries no session');
});

describe('a failed turn\'s sentence', () => {
  const seed = (client: QueryClient): void => {
    client.setQueryData(['trace', 'claude-solo', 'a'], kept('a', 'the connection for a closed mid-request; retry'));
    client.setQueryData(['trace', 'claude-solo', 'b'], kept('b', 'the connection for b closed mid-request; retry'));
  };
  test('is printed whole, and each turn prints the one its own read holds, whichever read answered last', () => {
    const b = page(<Failure row={row('b')} />, seed);
    expect(b).toContain('<p class="failure-sentence">The connection for b closed mid-request; retry</p>');
    expect(b).not.toContain('the connection for a');
    const a = page(<Failure row={row('a')} />, seed);
    expect(a).toContain('The connection for a closed mid-request; retry');
    expect(a).not.toContain('the connection for b');
  });
  test('a lowercase rate-limit sentence starts as a sentence without losing the recorded reason', () => {
    const sentence = 'rate limit reached; retry after the named reset, with the same session.';
    const limited = { ...row('limited'), outcome: 'error:rate-limited' };
    const data = kept('limited', sentence);
    const html = page(<Failure row={limited} />, client => client.setQueryData(['trace', 'claude-solo', 'limited'], data));
    expect(html).toContain('<p class="failure-sentence">Rate limit reached; retry after the named reset, with the same session.</p>');
    expect(data.read.turn.failure_sentence).toBe(sentence);
  });

  test.each([
    'the provider failed on its side; retry in a moment',
    'an older failure sentence with entirely different wording; retry',
    null,
  ])('decoded policy cause overrides stale or absent kept wording: %s', sentence => {
    const refused = { ...row('policy'), outcome: 'failure:api_error', cause: 'CONTENT_FILTERED' };
    const data = kept('policy', sentence);
    const html = page(<Failure row={refused} />, client => client.setQueryData(['trace', 'claude-solo', 'policy'], data));
    expect(html).toContain('The provider stopped the answer under its content check; ask for a different task');
    if (sentence !== null) expect(html).not.toContain(sentence);
    expect(html).not.toContain('cybersecurity');
    expect(data.read.turn.failure_sentence).toBe(sentence);
  });

  test('a cause-corrected daemon sentence keeps the explicit provider words', () => {
    const refused = { ...row('policy'), outcome: 'failure:api_error', cause: 'CONTENT_FILTERED' };
    const sentence = 'OpenAI refused the request under its cybersecurity check. This synthetic request was flagged. Try rephrasing.';
    const data = kept('policy', sentence);
    data.read.turn.cause = refused.cause;
    const html = page(<Failure row={refused} />, client => client.setQueryData(['trace', 'claude-solo', 'policy'], data));
    expect(html).toContain(sentence);
    expect(data.read.turn.failure_sentence).toBe(sentence);
  });

  test('a retained summary for a different cause cannot contradict the perf row', () => {
    const refused = { ...row('policy'), outcome: 'failure:api_error', cause: 'CONTENT_FILTERED' };
    const data = kept('policy', 'an obsolete synthetic outage');
    data.read.turn.cause = 'UPSTREAM_STATUS_5XX';
    const html = page(<Failure row={refused} />, client => client.setQueryData(['trace', 'claude-solo', 'policy'], data));
    expect(html).toContain('The provider stopped the answer under its content check; ask for a different task');
    expect(html).not.toContain('obsolete synthetic outage');
  });

  test('a model refusal without kept bodies overrides arbitrary older wording', () => {
    const refused = { ...row('model'), outcome: 'failure:api_error', cause: 'MODEL_REFUSED' };
    const data = kept('model', 'a synthetic obsolete server failure');
    const html = page(<Failure row={refused} />, client => client.setQueryData(['trace', 'claude-solo', 'model'], data));
    expect(html).toContain('The model declined to answer; ask for a different task');
    expect(html).not.toContain('obsolete server failure');
  });

  test.each(['client_abort', 'error:stopped'])('names a missing explanation without inventing the cause of %s', outcome => {
    const stopped = { ...row('a'), outcome };
    const none = page(<Failure row={stopped} />, (client) => client.setQueryData(['trace', 'claude-solo', 'a'], kept('a', null)));
    expect(none).toContain('This answer stopped before it completed. No detailed stop reason was kept.');
    expect(none).not.toContain('the client disconnected');
    const gone = page(<Failure row={stopped} />, (client) => client.setQueryData(['trace', 'claude-solo', 'a'], { gone: 'no such turn' }));
    expect(gone).toContain('This answer stopped before it completed. No detailed stop reason was kept.');
    expect(gone).toContain('no such turn');
  });

  test('an unavailable trace does not hide the recorded outcome, and a success needs no explanation', () => {
    const noTrace = { head: 'claude-solo', ts: 1, model: 'm', outcome: 'error:rate-limited', compact: false };
    const failed = page(<Failure row={noTrace} />, () => undefined);
    expect(failed).toContain('This request ended: Rate limited. No detailed failure reason was kept.');
    const done = page(<Failure row={{ ...noTrace, outcome: 'ok' }} />, () => undefined);
    expect(done).not.toContain('failure-sentence');
    const empty = page(<Failure row={{ ...noTrace, outcome: 'empty_message' }} />, () => undefined);
    expect(empty).not.toContain('failure-sentence');
  });
});

test.each([undefined, 'different-trace'])('Sent selects its request id independently of trace %s and retains every matching round', trace => {
  const tapped = { head: 'claude-solo', ts: 1_000_000, model: 'm', outcome: 'ok', compact: false, turn_id: 'request-a', ...(trace === undefined ? {} : { turn: trace }) };
  const record = { ts: tapped.ts, session: 'same-session', model: 'm', compact: false };
  const html = page(<KeptTabs row={tapped} plan="Solo" tab="sent" />, client => client.setQueryData(['wire', 'claude-solo'], { tap: {
    key: 'claude-solo', keep: 4, records: [
      { ...record, turn_id: 'request-b', body: 'SYNTHETIC_FOREIGN_REQUEST' },
      { ...record, turn_id: 'request-a', body: 'SYNTHETIC_FIRST_ROUND' },
      { ...record, body: 'SYNTHETIC_UNOWNED_REQUEST' },
      { ...record, turn_id: 'request-a', body: 'SYNTHETIC_SECOND_ROUND' },
    ],
  } }));
  expect(html).toContain('SYNTHETIC_FIRST_ROUND');
  expect(html).toContain('SYNTHETIC_SECOND_ROUND');
  expect(html).not.toContain('SYNTHETIC_FOREIGN_REQUEST');
  expect(html).not.toContain('SYNTHETIC_UNOWNED_REQUEST');
});

test.each([
  ['reading', undefined, 'Reading what was sent to the model…'],
  ['off', { off: 'synthetic tap advice' }, 'Sent bodies are not kept for this command.'],
  ['missing', { tap: { key: 'claude-solo', keep: 1, records: [{ ts: 1_000_000, turn_id: 'other-request', model: 'm', compact: false, body: 'SYNTHETIC_FOREIGN_BODY' }] } }, 'The sent record cannot be matched to this request.'],
  ['wrong-head', { tap: { key: 'other-head', keep: 1, records: [{ ts: 1_000_000, turn_id: 'request-a', model: 'm', compact: false, body: 'SYNTHETIC_FOREIGN_BODY' }] } }, 'The sent record cannot be matched to this request.'],
])('an owned Sent read names its %s state without showing foreign bytes', (_state, cached, sentence) => {
  const tapped = { head: 'claude-solo', ts: 1_000_000, model: 'm', outcome: 'ok', compact: false, turn_id: 'request-a' };
  const html = page(<KeptTabs row={tapped} plan="Solo" tab="sent" />, client => { if (cached !== undefined) client.setQueryData(['wire', tapped.head], cached); });
  expect(html).toContain(sentence);
  expect(html).not.toContain('SYNTHETIC_FOREIGN_BODY');
});

test('an owned Sent read keeps a wire error as an observed error', () => {
  const client = new QueryClient({ defaultOptions: { queries: { retryOnMount: false } } });
  const queryKey = ['wire', 'claude-solo'];
  client.setQueryData(queryKey, { tap: { key: 'claude-solo', keep: 1, records: [] } });
  const query = client.getQueryCache().find({ queryKey });
  if (query === undefined) throw new Error('seeded wire query must exist');
  query.setState({ data: undefined, status: 'error', fetchStatus: 'idle', error: new Error('Synthetic owned wire read failed') });
  const html = renderToStaticMarkup(<QueryClientProvider client={client}><MemoryRouter><KeptTabs row={{ ...row('synthetic'), turn_id: 'request-a' }} plan="Solo" tab="sent" /></MemoryRouter></QueryClientProvider>);
  expect(html).toContain('<p class="kept-note" role="alert">Synthetic owned wire read failed</p>');
  expect(html).not.toContain('Sent bodies are not kept');
});

test('tap-on capture-off keeps Request off and never uses the request id as a trace id', () => {
  const tapped = { head: 'claude-solo', ts: 1_000_000, model: 'm', outcome: 'error:conn-reset', compact: false, turn_id: 'request-a' };
  const client = new QueryClient();
  client.setQueryData(['capture', tapped.head], capture(false));
  client.setQueryData(['trace', tapped.head, tapped.turn_id], kept(tapped.turn_id, 'SYNTHETIC_FOREIGN_TRACE_REASON'));
  const html = renderToStaticMarkup(<QueryClientProvider client={client}><MemoryRouter><Failure row={tapped} /><KeptTabs row={tapped} plan="Solo" tab="request" /></MemoryRouter></QueryClientProvider>);
  expect(html).toContain('Request capture is off for Solo');
  expect(html).not.toContain('SYNTHETIC_FOREIGN_TRACE_REASON');
  expect(client.getQueryCache().find({ queryKey: ['trace', tapped.head, tapped.turn_id] })?.getObserversCount()).toBe(0);
});

test.each([
  undefined,
  { off: 'synthetic cached tap advice' },
  { tap: { key: 'claude-solo', keep: 1, records: [{ ts: 1_001_000, session: 'session-a', model: 'm', compact: false, body: 'SYNTHETIC_LATER_REQUEST_BODY' }] } },
])('Sent withholds records without request ownership regardless of cached tap state %j', cached => {
  const html = page(<KeptTabs row={{ ...row('synthetic'), session_id: 'session-a' }} plan="Solo" tab="sent" />, client => {
    if (cached !== undefined) client.setQueryData(['wire', 'claude-solo'], cached);
  });
  expect(html).toContain('<p class="kept-note">The sent record cannot be matched to this request.</p>');
  expect(html).not.toContain('SYNTHETIC_LATER_REQUEST_BODY');
  expect(html).not.toContain('synthetic cached tap advice');
});

test('Sent cannot mistake an unobserved cached wire error for a read of this request', () => {
  const client = new QueryClient({ defaultOptions: { queries: { retryOnMount: false } } });
  const queryKey = ['wire', 'claude-solo'];
  client.setQueryData(queryKey, { tap: { key: 'claude-solo', keep: 1, records: [] } });
  const query = client.getQueryCache().find({ queryKey });
  if (query === undefined) throw new Error('seeded wire query must exist');
  query.setState({ data: undefined, status: 'error', fetchStatus: 'idle', error: new Error('Synthetic wire read failed') });
  const html = renderToStaticMarkup(<QueryClientProvider client={client}><MemoryRouter><KeptTabs row={row('synthetic')} plan="Solo" tab="sent" /></MemoryRouter></QueryClientProvider>);
  expect(html).toContain('<p class="kept-note">The sent record cannot be matched to this request.</p>');
  expect(html).not.toContain('Synthetic wire read failed');
  expect(query.getObserversCount()).toBe(0);
});

describe('the body capture control', () => {
  const file = (trace: string | null): TopologyState => ({ path: '/x/splice.toml', stale: false, topology: { heads: { 'claude-solo': { overrides: trace === null ? {} : { trace } } } } });
  const control = (enabled: boolean | null, saved: string | null = null): string => page(<CaptureControl head="claude-solo" plan="Solo" />, (client) => {
    if (enabled !== null) client.setQueryData(['capture', 'claude-solo'], capture(enabled));
    if (saved !== null) client.setQueryData(['topology'], file(saved));
  });
  test('is a switch that says what it keeps, for how long and who can read it, and shows what the daemon records now', () => {
    expect(control(false)).toMatch(/role="switch" aria-checked="false" aria-label="Keep full requests and replies for Solo \(7 days, only you can read them\)"/);
    const on = control(true);
    expect(on).toMatch(/aria-checked="true"/);
    expect(on).toContain('Recording bodies for Solo.');
    expect(on).not.toContain('Restart to apply');
  });
  test('reads what was saved from splice.toml, so the pending restart is there on any turn\'s page and after a reload', () => {
    const waiting = control(false, 'true');
    expect(waiting).toMatch(/aria-checked="true"/);
    expect(waiting).toContain('Restart to apply. Capture will be on after splice restarts.');
    expect(waiting).toContain('Restart splice');
    expect(waiting).not.toContain('Recording bodies');
    const off = control(true, 'false');
    expect(off).toMatch(/aria-checked="false"/);
    expect(off).toContain('Capture will be off after splice restarts.');
    expect(control(true, 'true')).not.toContain('Restart to apply');
  });
  test('the saved value is the file\'s own trace text for that head, and nothing when the file sets none', () => {
    expect(savedCapture(file('true'), 'claude-solo')).toBe(true);
    expect(savedCapture(file('false'), 'claude-solo')).toBe(false);
    expect(savedCapture(file(null), 'claude-solo')).toBeNull();
    expect(savedCapture(file('true'), 'other')).toBeNull();
    expect(savedCapture({ pending: 'no route' } as unknown as TopologyState, 'claude-solo')).toBeNull();
    expect(savedCapture(undefined, 'claude-solo')).toBeNull();
  });
  test('draws nothing until the daemon has said', () => {
    expect(control(null)).toBe('');
  });
  test('shows what was saved against what runs, and a restart waiting when they differ', () => {
    expect(captureState(false, null)).toEqual({ on: false, recording: false, pending: false });
    expect(captureState(false, true)).toEqual({ on: true, recording: false, pending: true });
    expect(captureState(true, false)).toEqual({ on: false, recording: true, pending: true });
    expect(captureState(true, true)).toEqual({ on: true, recording: true, pending: false });
    expect(captureState(false, false)).toEqual({ on: false, recording: false, pending: false });
    expect(captureState(null, null).on).toBe(false);
  });
});

describe('unavailable kept bodies', () => {
  const requestPage = (body: unknown, reason?: unknown): string => page(
    <KeptTabs row={row('synthetic')} plan="Solo" tab="request" />,
    (client) => {
      const data = kept('synthetic', null);
      client.setQueryData(['trace', 'claude-solo', 'synthetic'], {
        read: {
          ...data.read,
          records: [{
            kind: 'attempt', ts: 1, attempt: 1,
            request: { body: reason === undefined ? body : { unavailable: true, reason } },
            response: { text: 'synthetic intact answer' },
          }],
        },
      });
    },
  );

  test('a missing or corrupt body stays legible beside the intact answer', () => {
    const html = requestPage({ trace_chunks: 1, parts: [], unavailable: true });
    expect(html).toContain('Body unavailable.');
    expect(html).toContain('<pre>synthetic intact answer</pre>');
  });

  test('a capacity omission shows its stored reason, escaped as text', () => {
    const html = requestPage(null, 'daily trace body budget exhausted <synthetic>');
    expect(html).toContain('Body unavailable. daily trace body budget exhausted &lt;synthetic&gt;');
    expect(html).toContain('synthetic intact answer');
  });

  test('an unexpected non-string reason cannot become a React child', () => {
    expect(requestPage(null, { malformed: 'synthetic' })).toContain('Body unavailable.');
  });

  test('unavailable response text and answer bodies cannot blank the panel', () => {
    const html = page(<KeptTabs row={row('synthetic')} plan="Solo" tab="request" />, (client) => {
      const data = kept('synthetic', null);
      client.setQueryData(['trace', 'claude-solo', 'synthetic'], {
        read: {
          ...data.read,
          records: [
            { kind: 'attempt', ts: 1, attempt: 1, request: { body: 'synthetic intact request' }, response: { text: { unavailable: true } } },
            { kind: 'turn', ts: 2, answer: { body: { unavailable: true, reason: 'daily trace body budget exhausted' } } },
          ],
        },
      });
    });
    expect(html).toContain('<pre>synthetic intact request</pre>');
    expect(html.match(/Body unavailable\./g)).toHaveLength(2);
    expect(html).toContain('daily trace body budget exhausted');
  });

  test('legacy literals remain verbatim', () => {
    expect(requestPage('synthetic legacy body')).toContain('<pre>synthetic legacy body</pre>');
  });
});

describe('the plans that keep activity labels', () => {
  const plans = [{ key: 'claudex', label: 'Claudex' }, { key: 'bonsai', label: 'Bonsai' }];
  const control = (value: string): string => renderToStaticMarkup(<PlansKept value={value} plans={plans} onSave={() => undefined} />);
  test('is a switch per plan under a summary, never a text box of plan keys', () => {
    const html = control('claudex');
    expect(html).toContain('1 of 2 commands · Choose commands');
    expect(html).toMatch(/aria-checked="true" aria-label="Keep activity labels for Claudex"/);
    expect(html).toMatch(/aria-checked="false" aria-label="Keep activity labels for Bonsai"/);
    expect(html).not.toContain('<input');
  });
  test('* reads as every plan and empty as none', () => {
    expect(control('*')).toContain('Every command · Choose commands');
    expect(control('')).toContain('No command · Choose commands');
  });
});
