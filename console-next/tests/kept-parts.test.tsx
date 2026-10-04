// What a turn keeps, rendered from a seeded cache: the failure sentence under its own turn, and the body capture control.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { describe, expect, test } from 'vitest';
import { captureState, savedCapture } from '../src/lib/turns-page';
import { CaptureControl } from '../src/pages/turns/CaptureControl';
import { KeptTabs } from '../src/pages/turns/KeptTabs';
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
    ['sent', 'Reading what was sent to the model…'],
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
    const b = page(<KeptTabs row={row('b')} plan="Solo" tab={null} />, seed);
    expect(b).toContain('<p class="failure-sentence">The connection for b closed mid-request; retry</p>');
    expect(b).not.toContain('the connection for a');
    const a = page(<KeptTabs row={row('a')} plan="Solo" tab={null} />, seed);
    expect(a).toContain('The connection for a closed mid-request; retry');
    expect(a).not.toContain('the connection for b');
  });
  test('a lowercase rate-limit sentence starts as a sentence without losing the recorded reason', () => {
    const sentence = 'rate limit reached; retry after the named reset, with the same session.';
    const limited = { ...row('limited'), outcome: 'error:rate-limited' };
    const data = kept('limited', sentence);
    const html = page(<KeptTabs row={limited} plan="Solo" tab={null} />, client => client.setQueryData(['trace', 'claude-solo', 'limited'], data));
    expect(html).toContain('<p class="failure-sentence">Rate limit reached; retry after the named reset, with the same session.</p>');
    expect(data.read.turn.failure_sentence).toBe(sentence);
  });

  test.each(['client_abort', 'error:stopped'])('names a missing explanation without inventing the cause of %s', outcome => {
    const stopped = { ...row('a'), outcome };
    const none = page(<KeptTabs row={stopped} plan="Solo" tab={null} />, (client) => client.setQueryData(['trace', 'claude-solo', 'a'], kept('a', null)));
    expect(none).toContain('This answer stopped before it completed. No detailed stop reason was kept.');
    expect(none).not.toContain('the client disconnected');
    const gone = page(<KeptTabs row={stopped} plan="Solo" tab={null} />, (client) => client.setQueryData(['trace', 'claude-solo', 'a'], { gone: 'no such turn' }));
    expect(gone).toContain('This answer stopped before it completed. No detailed stop reason was kept.');
    expect(gone).toContain('no such turn');
  });

  test('an unavailable trace does not hide the recorded outcome, and a success needs no explanation', () => {
    const noTrace = { head: 'claude-solo', ts: 1, model: 'm', outcome: 'error:rate-limited', compact: false };
    const failed = page(<KeptTabs row={noTrace} plan="Solo" tab={null} />, () => undefined);
    expect(failed).toContain('This request ended: Rate limited. No detailed failure reason was kept.');
    const done = page(<KeptTabs row={{ ...noTrace, outcome: 'ok' }} plan="Solo" tab={null} />, () => undefined);
    expect(done).not.toContain('failure-sentence');
    const empty = page(<KeptTabs row={{ ...noTrace, outcome: 'empty_message' }} plan="Solo" tab={null} />, () => undefined);
    expect(empty).not.toContain('failure-sentence');
  });
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
