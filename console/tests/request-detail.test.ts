// V4-345 (acceptance Q48, Q47): a request opens whole, and the landed turns narrow to the one a person
// is after. The request is read from the turn's own records: the Messages body Claude Code sent, read
// into instructions, messages and tools, and the answer the head streamed back, folded into its blocks.
// The views are proven to show what a person reads (the prompt, the answer's text, the cost) and to
// keep every large part (a tool's result, its input, a schema, thinking) out of the document behind
// its reveal; a turn that kept nothing is proven to say why and to carry the one control that changes
// it, in each state capture can be in. The filters and the session column are proven on the pure
// selection the page draws from.
import * as React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, test } from 'vitest';
import { mergeTurns } from '../src/entities/perf';
import type { CaptureState, CaptureWire, TranscriptConversationWire, TracedTurnWire, TraceTurnWire, TurnRow } from '../src/entities/perf';
import { landedKeysOf } from '../src/pages/turns/columns';
import { TurnFilters } from '../src/pages/turns/filters';
import { filterChoices, filterTurns, isFiltered, NO_FILTER, rowKeyer } from '../src/pages/turns/select';
import { readAnswer, readRequest, RequestDetail, RequestNotKept, RequestRead, TranscriptRequestRead } from '../src/widgets/request-detail';

const h = React.createElement;
const render = (el: React.ReactElement): string => renderToStaticMarkup(el);

const PROMPT = 'why is the build red';
const RESULT = 'FAILED ConfigGuardTest > rejects an unknown key';
const SCHEMA = { type: 'object', properties: { command: { type: 'string' } } };

/** A Messages request as Claude Code sends it: two system blocks, a tool call and its result, an
 *  earlier exchange, and the newest prompt last. */
const REQUEST = {
  model: 'claude-opus-4-8',
  max_tokens: 32000,
  stream: true,
  system: [{ type: 'text', text: 'You are Claude Code.' }, { type: 'text', text: 'Project rules follow.' }],
  tools: [{ name: 'Bash', description: 'Runs a shell command.', input_schema: SCHEMA }],
  messages: [
    { role: 'user', content: 'run the gate' },
    {
      role: 'assistant',
      content: [
        { type: 'thinking', thinking: 'the gate is bun tools/gate run', signature: 's' },
        { type: 'tool_use', id: 'toolu_1', name: 'Bash', input: { command: 'bun tools/gate run' } },
      ],
    },
    { role: 'user', content: [{ type: 'tool_result', tool_use_id: 'toolu_1', content: [{ type: 'text', text: RESULT }], is_error: true }] },
    { role: 'user', content: [{ type: 'text', text: PROMPT }] },
  ],
};

const sse = (...events: object[]): string => events.map((event) => `event: x\ndata: ${JSON.stringify(event)}\n\n`).join('');

/** The answer as a head streams it: thinking, text in two deltas, a tool call whose input arrives in
 *  pieces, and the stop reason on message_delta. */
const STREAM = sse(
  { type: 'message_start', message: { id: 'msg_1' } },
  { type: 'content_block_start', index: 0, content_block: { type: 'thinking', thinking: '' } },
  { type: 'content_block_delta', index: 0, delta: { type: 'thinking_delta', thinking: 'config guard' } },
  { type: 'content_block_start', index: 1, content_block: { type: 'text', text: '' } },
  { type: 'content_block_delta', index: 1, delta: { type: 'text_delta', text: 'The config guard ' } },
  { type: 'content_block_delta', index: 1, delta: { type: 'text_delta', text: 'rejects a key.' } },
  { type: 'content_block_start', index: 2, content_block: { type: 'tool_use', id: 'toolu_2', name: 'Read', input: {} } },
  { type: 'content_block_delta', index: 2, delta: { type: 'input_json_delta', partial_json: '{"file_pa' } },
  { type: 'content_block_delta', index: 2, delta: { type: 'input_json_delta', partial_json: 'th":"splice.toml"}' } },
  { type: 'message_delta', delta: { stop_reason: 'tool_use' } },
  { type: 'message_stop' },
);

describe('a request body reads as what the model received', () => {
  test('instructions, messages and tools, each part as a person reads it, and the rest as settings', () => {
    const read = readRequest(JSON.stringify(REQUEST), false);
    if (!('view' in read)) throw new Error('the request did not read');
    const { view } = read;
    expect(view.instructions).toEqual(['You are Claude Code.', 'Project rules follow.']);
    expect(view.messages.map((message) => message.role)).toEqual(['user', 'assistant', 'user', 'user']);
    expect(view.messages[0]?.parts).toEqual([{ kind: 'text', text: 'run the gate' }]);
    expect(view.messages[1]?.parts).toEqual([
      { kind: 'thinking', text: 'the gate is bun tools/gate run' },
      { kind: 'tool-call', id: 'toolu_1', name: 'Bash', input: JSON.stringify({ command: 'bun tools/gate run' }, null, 2) },
    ]);
    // A result names the tool its call named, so it reads as "Result of Bash", not an id.
    expect(view.messages[2]?.parts).toEqual([{ kind: 'tool-result', id: 'toolu_1', name: 'Bash', text: RESULT, error: true }]);
    expect(view.tools).toEqual([{ name: 'Bash', description: 'Runs a shell command.', schema: JSON.stringify(SCHEMA, null, 2) }]);
    expect(view.settings).toEqual([['model', 'claude-opus-4-8'], ['max_tokens', '32000'], ['stream', 'true']]);
  });

  test('a string system prompt is one instruction', () => {
    const read = readRequest(JSON.stringify({ system: 'Be brief.', messages: [] }), false);
    expect('view' in read && read.view.instructions).toEqual(['Be brief.']);
  });

  test('a body cut at the cap, or one that is not JSON, is kept as its text with the reason', () => {
    const cut = JSON.stringify(REQUEST).slice(0, 40);
    expect(readRequest(cut, true)).toEqual({ unread: 'cut', text: cut });
    expect(readRequest('not a request', false)).toEqual({ unread: 'not-json', text: 'not a request' });
  });
});

describe('an answer reads as what the model sent back', () => {
  test('a stream folds into its blocks in order, a tool call\'s pieces into its input, with the stop reason', () => {
    expect(readAnswer(STREAM, true)).toEqual({
      parts: [
        { kind: 'thinking', text: 'config guard' },
        { kind: 'text', text: 'The config guard rejects a key.' },
        { kind: 'tool-call', id: 'toolu_2', name: 'Read', input: JSON.stringify({ file_path: 'splice.toml' }, null, 2) },
      ],
      stopReason: 'tool_use',
      error: null,
    });
  });

  test('a stream that failed carries the error in its own words', () => {
    const failed = sse({ type: 'error', error: { type: 'overloaded_error', message: 'Overloaded' } });
    expect(readAnswer(failed, true)).toEqual({ parts: [], stopReason: null, error: 'Overloaded' });
  });

  test('a reply read whole: its content, its stop reason, or its error', () => {
    const reply = JSON.stringify({ content: [{ type: 'text', text: 'done' }], stop_reason: 'end_turn' });
    expect(readAnswer(reply, false)).toEqual({ parts: [{ kind: 'text', text: 'done' }], stopReason: 'end_turn', error: null });
    const refused = JSON.stringify({ type: 'error', error: { type: 'invalid_request_error', message: 'prompt is too long' } });
    expect(readAnswer(refused, false).error).toBe('prompt is too long');
  });
});

const SUMMARY: TracedTurnWire = {
  id: '3f2a9c01d4e5', ts: 1_790_467_200_000, session: 'sess-1', model: 'claude-opus-4-8', compact: false, open: false,
  outcome: 'ok', rounds: 1, attempts: 1, total_ms: 5200,
};

function turnRead(over: Partial<TraceTurnWire> = {}, messages: unknown[] = REQUEST.messages): TraceTurnWire {
  return {
    head: 'claudex',
    turn: SUMMARY,
    cost_usd: 0.00526,
    records: [
      {
        kind: 'attempt', turn: SUMMARY.id, ts: SUMMARY.ts, attempt: 1,
        request: { body: '{"model":"gpt-5.3-codex","input":[]}' }, response: { status: 200, text: 'data: {}' },
      },
      {
        kind: 'turn', turn: SUMMARY.id, ts: SUMMARY.ts, outcome: 'ok',
        client: { method: 'POST', path: '/v1/messages', headers: { 'x-test': 'flag' }, body: JSON.stringify({ ...REQUEST, messages }) },
        answer: { status: 200, stream: true, body: STREAM },
      },
    ],
    ...over,
  };
}

describe('an opened request', () => {
  test('shows the prompt and the answer, with the cost, and keeps every large part behind its reveal', () => {
    const out = render(h(RequestRead, { read: turnRead() }));
    expect(out).toContain('>Model received<');
    expect(out).toContain(`>${PROMPT}<`);
    expect(out).toContain('>You are Claude Code.<');
    expect(out).toContain('>Model sent back<');
    expect(out).toContain('>The config guard rejects a key.<');
    expect(out).toContain('Stopped: tool_use');
    expect(out).toContain('>$0.005<');
    // The tool result, the call's input, the thinking and the schema wait behind their reveals, each
    // with its size beside it.
    expect(out).not.toContain(RESULT);
    expect(out).toContain(`>${RESULT.length} chars<`);
    expect(out).not.toContain('bun tools/gate run');
    expect(out).not.toContain('"command"');
    expect(out).toContain('>Show result<');
    // What the provider got after translation is its own section, the body behind its reveal.
    expect(out).toContain('>Provider received<');
    expect(out).not.toContain('gpt-5.3-codex');
    // The interpreted view omits some wire fields, such as cache directives and SSE usage; the
    // exact captured request and answer are available on demand, never silently thrown away.
    expect(out).toContain('Raw request');
    expect(out).toContain('Raw answer');
    expect(out).toContain('Request headers');
    expect(out).not.toContain('"message_start"');
  });

  test('a long conversation shows its newest messages and holds the rest behind one key', () => {
    const many = Array.from({ length: 10 }, (_, at) => ({ role: at % 2 === 0 ? 'user' : 'assistant', content: `message ${at}` }));
    const out = render(h(RequestRead, { read: turnRead({}, many) }));
    expect(out).toContain('>Show earlier 4<');
    expect(out).not.toContain('>message 3<');
    expect(out).toContain('>message 4<');
    expect(out).toContain('>message 9<');
  });

  test('a model with no card says so, never $0, and an open turn prints no cost', () => {
    expect(render(h(RequestRead, { read: turnRead({ cost_usd: null }) }))).toContain('No price card');
    expect(render(h(RequestRead, { read: turnRead({ cost_usd: null }) }))).not.toContain('$0');
    const priced = turnRead();
    const open: TraceTurnWire = { head: priced.head, turn: priced.turn, records: priced.records };
    expect(render(h(RequestRead, { read: open }))).not.toContain('>Cost<');
  });

  test('a turn the trace no longer holds says so, and a read in flight says it is reading', () => {
    const gone = render(h(RequestDetail, { head: 'claudex', turn: SUMMARY.id, read: { gone: 'no turn 3f2a9c01d4e5 in claudex\'s trace' } }));
    expect(gone).toContain('No longer kept');
    const kept = render(h(RequestDetail, { head: 'claudex', turn: SUMMARY.id, read: { read: turnRead() } }));
    expect(kept).toContain(`>${PROMPT}<`);
  });
});

const RUNNING: CaptureWire = { head: 'claudex', enabled: false, retention_days: 7, max_body_chars: 2_000_000, restart_required: false };
const capture = (over: Partial<CaptureState> = {}): CaptureState => ({ running: RUNNING, written: null, refused: null, writing: false, ...over });

describe('a turn that kept no request says why, and what changes it', () => {
  const notKept = (state: CaptureState | null, onSwitch?: (enabled: boolean) => void) =>
    render(h(RequestNotKept, { capture: state, ...(onSwitch === undefined ? {} : { onSwitch }) }));

  test('capture off: the one line and the key that turns it on', () => {
    const out = notKept(capture(), () => undefined);
    expect(out).toContain('>Model received<');
    expect(out).toContain('Capture is off');
    expect(out).toContain('>Turn on capture<');
    expect(out).toContain('Requests are kept only while body capture is on.');
  });

  test('turned on and waiting for a restart: the line and the restart', () => {
    const written: CaptureWire = { ...RUNNING, enabled: true, restart_required: true };
    const out = notKept(capture({ written }), () => undefined);
    expect(out).toContain('Restart to apply');
    expect(out).toContain('>Restart splice<');
    expect(out).not.toContain('Turn on capture');
  });

  test('capture on: this turn ran before it, and there is nothing to press', () => {
    const out = notKept(capture({ running: { ...RUNNING, enabled: true } }), () => undefined);
    expect(out).toContain('Not kept');
    expect(out).not.toContain('Turn on capture');
    expect(out).not.toContain('Restart daemon');
  });

  test('capture not read yet: the plain fact, and no control that could act on a guess', () => {
    const out = notKept(null);
    expect(out).toContain('Not kept');
    expect(out).not.toContain('Turn on capture');
  });
});

function row(over: Partial<TurnRow> = {}): TurnRow {
  return { head: 'claudex', ts: 1_000_000, model: 'gpt-5.3-codex', outcome: 'ok', compact: false, ...over };
}

describe('the landed turns narrow by head, model, session, status and time (Q47)', () => {
  const NOW = 10_000_000;
  const rows = [
    row({ ts: NOW - 60_000, session: 'aaaa1111' }),
    row({ ts: NOW - 2 * 3_600_000, head: 'muse', model: 'claude-opus-4-8', session: 'bbbb2222', outcome: 'error:upstream' }),
    row({ ts: NOW - 30 * 60_000, model: 'claude-opus-4-8', session: 'aaaa1111' }),
  ];

  test('each filter keeps only the turns that match it, and they combine', () => {
    expect(isFiltered(NO_FILTER)).toBe(false);
    expect(filterTurns(rows, NO_FILTER, NOW)).toEqual(rows);
    expect(filterTurns(rows, { ...NO_FILTER, head: 'muse' }, NOW)).toEqual([rows[1]]);
    expect(filterTurns(rows, { ...NO_FILTER, model: 'claude-opus-4-8' }, NOW)).toEqual([rows[1], rows[2]]);
    expect(filterTurns(rows, { ...NO_FILTER, session: 'aaaa1111' }, NOW)).toEqual([rows[0], rows[2]]);
    expect(filterTurns(rows, { ...NO_FILTER, status: 'failed' }, NOW)).toEqual([rows[1]]);
    expect(filterTurns(rows, { ...NO_FILTER, status: 'ok' }, NOW)).toEqual([rows[0], rows[2]]);
    expect(filterTurns(rows, { ...NO_FILTER, within: 3_600_000 }, NOW)).toEqual([rows[0], rows[2]]);
    const both = { ...NO_FILTER, model: 'claude-opus-4-8', within: 3_600_000 };
    expect(isFiltered(both)).toBe(true);
    expect(filterTurns(rows, both, NOW)).toEqual([rows[2]]);
  });

  test('the choices are what the turns carry, and a chosen value stays choosable once no turn carries it', () => {
    expect(filterChoices(rows, NO_FILTER)).toEqual({
      heads: ['claudex', 'muse'],
      models: ['claude-opus-4-8', 'gpt-5.3-codex'],
      sessions: ['aaaa1111', 'bbbb2222'],
    });
    expect(filterChoices([rows[0] ?? row()], { ...NO_FILTER, model: 'kimi-k3' }).models).toEqual(['gpt-5.3-codex', 'kimi-k3']);
  });

  test('all five filters stay visible on a quiet head', () => {
    const out = render(h(TurnFilters, { rows: [row()], filter: NO_FILTER, onFilter: () => undefined, nameOf: (key: string) => key }));
    for (const label of ['Command', 'Model', 'Session', 'Status', 'Time']) {
      expect(out).toContain(`>${label}</span>`);
    }
  });

  test('a traced twin keeps its own key when a filter removes the other twin', () => {
    const first = row({ ts: NOW, turn: 'turn-one', session: 'aaaa1111' });
    const second = row({ ts: NOW, turn: 'turn-two', session: 'bbbb2222' });
    const before = rowKeyer();
    const firstKey = before(first);
    const secondKey = before(second);
    expect(firstKey).not.toBe(secondKey);
    const filtered = filterTurns([first, second], { ...NO_FILTER, session: 'bbbb2222' }, NOW);
    expect(filtered).toEqual([second]);
    expect(rowKeyer()(second)).toBe(secondKey);
  });

  test('a view saved before the session column shows it beside the model', () => {
    expect(landedKeysOf(['time', 'head', 'model', 'outcome'])).toEqual(['time', 'head', 'model', 'session', 'outcome']);
    expect(landedKeysOf(['session', 'time', 'model'])).toEqual(['session', 'time', 'model']);
    expect(landedKeysOf(['time', 'head'])).toEqual(['time', 'head']);
  });
});

describe('a perf row names the trace turn its request was kept under', () => {
  test('the wire\'s turn id reaches the row, and its null is an absence, never a value', () => {
    const wire = { ts: 1, model: 'm', outcome: 'ok', compact: false, session: null, account: null, cache_cold: null };
    const merged = mergeTurns([{ since: 0, n: 20, heads: [{ key: 'claudex', label: 'claudex', rows: [
      { ...wire, turn: '3f2a9c01d4e5', session_id: 'sess-v4345', response_message_id: 'msg_42', cost_usd: 0.0014 },
      { ...wire, ts: 2, turn: null, session_id: null, response_message_id: null, cost_usd: null },
    ] }] }]);
    expect(merged.landed.map((landed) => landed.turn)).toEqual(['3f2a9c01d4e5', undefined]);
    expect(merged.landed[0]?.session_id).toBe('sess-v4345');
    expect(merged.landed[0]?.response_message_id).toBe('msg_42');
    expect(merged.landed[0]?.cost_usd).toBe(0.0014);
    expect(merged.landed[1]?.session_id).toBeUndefined();
    expect('turn' in (merged.landed[1] ?? {})).toBe(false);
  });
});

const SAVED: TranscriptConversationWire = {
  state: 'found',
  session_id: 'sess-v4354',
  response_message_id: 'msg_42_7',
  earlier: 0,
  messages: [
    { index: 0, role: 'user', text: 'why is the build red' },
    { index: 1, role: 'assistant', text: 'the config key is invalid', selected: true },
  ],
};

describe('default-install request from the client transcript (V4-354)', () => {
  test('the selected prompt and reply show with an honest boundary for exact bytes', () => {
    const out = render(h(TranscriptRequestRead, { read: SAVED, viewOn: true, onSwitch: () => undefined }));
    expect(out).toContain('>Model received<');
    expect(out).toContain('>why is the build red<');
    expect(out).toContain('>the config key is invalid<');
    expect(out).toContain('Local transcript');
    expect(out).toContain('Exact instructions and tools need request capture.');
    expect(out).toContain('aria-label="Transcript view"');
    expect(out).toContain('aria-checked="true"');
    expect(out).not.toContain('No price card');
  });

  test('the switch off hides every saved message and says how to turn it back on', () => {
    const out = render(h(TranscriptRequestRead, {
      read: { state: 'off', reason: 'Transcript view is off. Turn it on in Request detail.' },
      viewOn: false,
      onSwitch: () => undefined,
    }));
    expect(out).toContain('Transcript off');
    expect(out).toContain('Turn on transcript');
    expect(out).toContain('aria-checked="false"');
    expect(out).not.toContain(PROMPT);
    const stale = render(h(TranscriptRequestRead, { read: SAVED, viewOn: false, onSwitch: () => undefined }));
    expect(stale).not.toContain(PROMPT);
  });

  test('a bounded context links to its session for earlier messages', () => {
    const out = render(h(TranscriptRequestRead, {
      read: { ...SAVED, earlier: 5 }, viewOn: true, onSwitch: () => undefined,
    }));
    expect(out).toContain('Show earlier 5');
    expect(out).toContain('href="#/sessions?open=sess-v4354"');
  });

  test('a missing or pruned transcript says so, not an empty conversation', () => {
    const out = render(h(TranscriptRequestRead, {
      read: { state: 'missing', reason: 'No matching reply in this session\'s saved transcript.' },
      viewOn: true,
      onSwitch: () => undefined,
    }));
    expect(out).toContain('No saved reply');
    expect(out).toContain('No matching reply');
  });
});
