#!/usr/bin/env node
// The locked invariants L1–L4 as permanent tests (the walls enforce the
// structural half at write time; these prove the behavioral half).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

process.env.CODEX_PROXY_TEST = '1';

const { buildRequest, stablePromptCacheKey } = await import('../src/codex/translate-request.mjs');
const { translateResponse, pickModelText, ensureTextFromThinking } = await import('../src/codex/translate-response.mjs');
const { mirrorInto, MIRROR_MIN_CHARS, PROMOTE_MIN_CHARS, HONESTY_MIN_CHARS, mirrorWireText } = await import('../src/reasoning/mirror.mjs');
const { encodeReasoningEnvelope, decodeReasoningEnvelope } = await import('../src/reasoning/replay.mjs');
const { getConfig } = await import('../src/config.mjs');

const SRC = join(dirname(fileURLToPath(import.meta.url)), '..', 'src');

// ── Reasoning replay (default-off, gated) + prompt_cache_key — Codex-parity ──
// (Replaces the retired L1 "never replay" invariant, 2026-07-14. The mirror
// still always runs; replay + cache key are the added cache-warm channels.)

const cfgReplay = (on) => ({ ...getConfig(), replayReasoning: on });

test('replay defaults off to preserve fresh reasoning depth', () => {
  assert.equal(getConfig().replayReasoning, false);
});

test('replay opt-in: non-compact requests ask the backend for encrypted reasoning', () => {
  const body = { model: 'gpt-5.6-sol', messages: [{ role: 'user', content: 'hi' }] };
  const { req } = buildRequest(body, { compact: false, config: cfgReplay(true), originalModel: body.model });
  assert.deepEqual(req.include, ['reasoning.encrypted_content']);
});

test('replay gated off: config.replayReasoning=false sets no include (pure amnesia)', () => {
  const body = { model: 'gpt-5.6-sol', messages: [{ role: 'user', content: 'hi' }] };
  const { req } = buildRequest(body, { compact: false, config: cfgReplay(false), originalModel: body.model });
  assert.equal(req.include, undefined);
  assert.ok(!('include' in req));
});

test('replay never on compact turns (text-only summarizer)', () => {
  const body = { model: 'gpt-5.6-sol', messages: [{ role: 'user', content: 'summarize' }] };
  const { req } = buildRequest(body, { compact: true, config: cfgReplay(true), originalModel: body.model });
  assert.equal(req.include, undefined, 'compact never replays');
});

test('replay round-trip: a redacted_thinking block decodes back into a reasoning input item, in cache order', () => {
  const envelope = encodeReasoningEnvelope({ id: 'rs_1', encrypted_content: 'ENCRYPTED', summary: [{ type: 'summary_text', text: 'sum' }] });
  const body = {
    model: 'gpt-5.6-sol',
    messages: [
      { role: 'user', content: 'solve x' },
      { role: 'assistant', content: [
        { type: 'thinking', thinking: 'sum' },
        { type: 'redacted_thinking', data: envelope },
        { type: 'tool_use', id: 'toolu_1', name: 't', input: {} },
      ] },
      { role: 'user', content: [{ type: 'tool_result', tool_use_id: 'toolu_1', content: 'ok' }] },
    ],
  };
  const on = buildRequest(body, { compact: false, config: cfgReplay(true), originalModel: body.model });
  const reasoning = on.req.input.filter((i) => i.type === 'reasoning');
  assert.equal(reasoning.length, 1, 'decoded reasoning item is in the input');
  assert.equal(reasoning[0].encrypted_content, 'ENCRYPTED');
  const rIdx = on.req.input.findIndex((i) => i.type === 'reasoning');
  const fIdx = on.req.input.findIndex((i) => i.type === 'function_call');
  assert.ok(rIdx >= 0 && rIdx < fIdx, 'reasoning precedes the function_call it preceded (prefix order held)');

  const off = buildRequest(body, { compact: false, config: cfgReplay(false), originalModel: body.model });
  assert.equal(off.req.input.some((i) => i.type === 'reasoning'), false, 'replay off drops the reasoning item entirely');
});

test('reasoning envelope round-trips; foreign or garbled data decodes to null (dropped, never misparsed)', () => {
  const decoded = decodeReasoningEnvelope(encodeReasoningEnvelope({ id: 'rs_9', encrypted_content: 'BLOB', summary: [{ type: 'summary_text', text: 's' }] }));
  assert.equal(decoded.type, 'reasoning');
  assert.equal(decoded.id, 'rs_9');
  assert.equal(decoded.encrypted_content, 'BLOB');
  assert.equal(decodeReasoningEnvelope('not-base64-$$$'), null);
  assert.equal(decodeReasoningEnvelope(Buffer.from('{"tag":"other","v":1,"item":{}}').toString('base64')), null);
  assert.equal(decodeReasoningEnvelope(''), null);
});

test('prompt_cache_key: stable across turns, keyed on the first user message', () => {
  const turn1 = { model: 'gpt-5.6-sol', messages: [{ role: 'user', content: 'build the thing' }] };
  const turn2 = { model: 'gpt-5.6-sol', messages: [
    { role: 'user', content: 'build the thing' },
    { role: 'assistant', content: [{ type: 'text', text: 'ok' }] },
    { role: 'user', content: [{ type: 'tool_result', tool_use_id: 't', content: 'r' }] },
  ] };
  const k1 = buildRequest(turn1, { compact: false, config: getConfig(), originalModel: turn1.model }).req.prompt_cache_key;
  const k2 = buildRequest(turn2, { compact: false, config: getConfig(), originalModel: turn2.model }).req.prompt_cache_key;
  assert.ok(k1 && k1.startsWith('splice-'), 'key present and namespaced');
  assert.equal(k1, k2, 'same conversation (same first user msg) → same shard key across turns');

  const other = { model: 'gpt-5.6-sol', messages: [{ role: 'user', content: 'a different task' }] };
  const k3 = buildRequest(other, { compact: false, config: getConfig(), originalModel: other.model }).req.prompt_cache_key;
  assert.notEqual(k1, k3, 'different first message → different shard key');
});

test('stablePromptCacheKey: null when there is no user message to anchor on', () => {
  assert.equal(stablePromptCacheKey([]), null);
  assert.equal(stablePromptCacheKey([{ role: 'assistant', content: 'hi' }]), null);
  assert.equal(stablePromptCacheKey(undefined), null);
});

test('both channels coexist: replay (redacted_thinking) AND mirror (thinking) ride the same response', () => {
  const resp = {
    id: 'r1', status: 'completed', usage: {},
    output: [
      { type: 'reasoning', summary: [{ type: 'summary_text', text: 'because' }], encrypted_content: 'ENC' },
      { type: 'message', content: [{ type: 'output_text', text: 'answer' }] },
    ],
  };
  const on = translateResponse(resp, 'gpt-5.6-sol', { replay: true });
  assert.equal(on.content.filter((c) => c.type === 'redacted_thinking').length, 1, 'replay → encrypted reasoning block');
  assert.ok(on.content.some((c) => c.type === 'thinking'), 'mirror channel (visible thinking) also present');
  assert.ok(on.content.some((c) => c.type === 'text' && c.text === 'answer'));

  const off = translateResponse(resp, 'gpt-5.6-sol'); // default replay off
  assert.equal(off.content.some((c) => c.type === 'redacted_thinking'), false, 'replay off → no redacted_thinking');
  assert.ok(off.content.some((c) => c.type === 'thinking'), 'mirror still runs with replay off');
});

// ── L2: ONE mirrorInto, called by BOTH response paths ────────────────────────

test('L2: stream and non-stream paths both call the single mirrorInto', () => {
  const streamSrc = readFileSync(join(SRC, 'codex', 'stream.mjs'), 'utf8');
  const entrySrc = readFileSync(join(SRC, 'codex-proxy.mjs'), 'utf8');
  assert.match(streamSrc, /\bmirrorInto\(/, 'stream path calls mirrorInto');
  assert.match(entrySrc, /\bmirrorInto\(/, 'non-stream path calls mirrorInto');
  assert.match(streamSrc, /from '\.\.\/reasoning\/mirror\.mjs'/, 'stream imports the one implementation');
  assert.match(entrySrc, /from '\.\/reasoning\/mirror\.mjs'/, 'entry imports the one implementation');
});

test('L2: mirrorInto behavior — threshold, compact/off gating, wire format', () => {
  const calls = [];
  const sink = { addTextBlock: (t) => calls.push(t) };
  const thinking = 'a substantive reasoning summary for the mirror';

  assert.equal(mirrorInto(sink, thinking, { showReasoning: 'text', compact: false }), true);
  assert.equal(calls[0], mirrorWireText(thinking), 'wire format is the v25–v29 contract');
  assert.match(calls[0], /^\n\[reasoning summary\]\n/);

  assert.equal(mirrorInto(sink, thinking, { showReasoning: 'off', compact: false }), false, 'off → no mirror');
  assert.equal(mirrorInto(sink, thinking, { showReasoning: 'thinking', compact: false }), false, 'thinking-only → no mirror');
  assert.equal(mirrorInto(sink, thinking, { showReasoning: 'text', compact: true }), false, 'compact → no mirror');
  assert.equal(mirrorInto(sink, 'short', { showReasoning: 'text', compact: false }), false, 'below threshold → no mirror');
  assert.equal(calls.length, 1, 'exactly one emission across all gated calls');
});

test('named thresholds hold their v29 values in one place', () => {
  assert.equal(MIRROR_MIN_CHARS, 20);
  assert.equal(PROMOTE_MIN_CHARS, 40);
  assert.equal(HONESTY_MIN_CHARS, 20);
});

// ── L4: no fake summaries — empty in → empty out ─────────────────────────────

test('L4: pickModelText fabricates nothing — empty in, empty out', () => {
  assert.deepEqual(pickModelText('', ''), { text: '', source: 'empty' });
  assert.deepEqual(pickModelText('short', ''), { text: '', source: 'empty' }, 'sub-threshold thinking is not promoted');
  const long = 'reasoning long enough to be promoted to text, well over forty chars';
  assert.deepEqual(pickModelText(long, ''), { text: long, source: 'model_thinking' }, 'promotion carries MODEL content only');
});

test('L4: ensureTextFromThinking adds nothing when there is nothing', () => {
  const content = [];
  ensureTextFromThinking(content);
  assert.equal(content.length, 0);
});

test('L4: translateResponse of an empty completion has empty text (never invented)', () => {
  const msg = translateResponse({ id: 'r', status: 'completed', output: [], usage: {} }, 'gpt-5.6-sol');
  assert.equal(msg.content.length, 1);
  assert.equal(msg.content[0].text, '');
});
