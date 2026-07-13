#!/usr/bin/env node
// The locked invariants L1–L4 as permanent tests (the walls enforce the
// structural half at write time; these prove the behavioral half).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

process.env.CODEX_PROXY_TEST = '1';

const { buildRequest } = await import('../src/codex/translate-request.mjs');
const { translateResponse, pickModelText, ensureTextFromThinking } = await import('../src/codex/translate-response.mjs');
const { mirrorInto, MIRROR_MIN_CHARS, PROMOTE_MIN_CHARS, HONESTY_MIN_CHARS, mirrorWireText } = await import('../src/reasoning/mirror.mjs');
const { getConfig } = await import('../src/config.mjs');

const SRC = join(dirname(fileURLToPath(import.meta.url)), '..', 'src');

// ── L1: no reasoning-item replay — buildRequest NEVER sets include ───────────

test('L1: buildRequest(any).include === undefined — including adversarial bodies', () => {
  const cases = [
    { model: 'gpt-5.6-sol', messages: [{ role: 'user', content: 'x' }] },
    { model: 'gpt-5.6-sol', messages: [{ role: 'user', content: 'x' }], include: ['reasoning.encrypted_content'] },
    { model: 'gpt-5.6-sol', system: 'You are a helpful AI assistant tasked with summarizing conversations.', messages: [{ role: 'user', content: 'x' }] },
    { model: 'gpt-5.6-sol', messages: [{ role: 'user', content: 'x' }], thinking: { type: 'enabled', budget_tokens: 128000 }, tools: [{ name: 't', input_schema: {} }] },
  ];
  for (const body of cases) {
    const { req } = buildRequest(body, { compact: false, config: getConfig(), originalModel: body.model });
    assert.equal(req.include, undefined, `include must never be set (body: ${JSON.stringify(body).slice(0, 80)})`);
    assert.ok(!('include' in req), 'include key must not even exist');
  }
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
