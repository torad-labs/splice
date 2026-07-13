#!/usr/bin/env node
// classifyUpstreamFailure — ONE classifier for HTTP and SSE (the P0 fix).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { classifyUpstreamFailure, anthropicErrorBody, mapOutStatus } from '../src/codex/errors.mjs';

test('overflow via SSE failure event → invalid_request_error + "prompt is too long"', () => {
  const r = classifyUpstreamFailure('sse', 'invalid_request_error Your input exceeds the context window of this model.');
  assert.equal(r.type, 'invalid_request_error');
  assert.match(r.message, /^prompt is too long: /);
});

test('overflow via HTTP body → same classification (single classifier)', () => {
  const r = classifyUpstreamFailure('http', JSON.stringify({ error: { message: 'This request exceeds the maximum context length. Too many tokens.' } }), 400);
  assert.equal(r.type, 'invalid_request_error');
  assert.match(r.message, /prompt is too long/);
});

test('"too many tokens" is overflow, not auth (v29 tested /token/ before context)', () => {
  const r = classifyUpstreamFailure('http', JSON.stringify({ error: { message: 'too many tokens in prompt' } }), 400);
  assert.equal(r.type, 'invalid_request_error');
});

test('already-anthropic phrasing is not double-prefixed', () => {
  const r = classifyUpstreamFailure('sse', 'invalid_request_error prompt is too long: 250000 tokens > 231000 maximum');
  assert.equal(r.type, 'invalid_request_error');
  assert.ok(!/prompt is too long: .*prompt is too long/.test(r.message), r.message);
});

test('auth failures classify on 401 and on wording', () => {
  assert.equal(classifyUpstreamFailure('http', 'whatever', 401).type, 'authentication_error');
  assert.equal(classifyUpstreamFailure('sse', 'upstream_failed token expired, please re-authenticate').type, 'authentication_error');
});

test('rate limits classify on 429 and wording', () => {
  assert.equal(classifyUpstreamFailure('http', 'x', 429).type, 'rate_limit_error');
  assert.equal(classifyUpstreamFailure('sse', 'rate_limit_exceeded You have hit your usage quota').type, 'rate_limit_error');
});

test('html gateway pages become a clean api_error', () => {
  const r = classifyUpstreamFailure('http', '<html><body>Bad gateway</body></html>', 502);
  assert.equal(r.type, 'api_error');
  assert.match(r.message, /gateway/);
});

test('status tiers: 5xx api_error, 4xx invalid_request_error, sse default api_error', () => {
  assert.equal(classifyUpstreamFailure('http', JSON.stringify({ error: { message: 'internal' } }), 503).type, 'api_error');
  assert.equal(classifyUpstreamFailure('http', JSON.stringify({ error: { message: 'bad field' } }), 422).type, 'invalid_request_error');
  assert.equal(classifyUpstreamFailure('sse', 'server_error boom upstream').type, 'api_error');
});

test('anthropicErrorBody wraps; mapOutStatus turns 502 into 529', () => {
  const body = anthropicErrorBody(500, JSON.stringify({ error: { message: 'kaboom' } }));
  assert.equal(body.type, 'error');
  assert.equal(body.error.type, 'api_error');
  assert.equal(mapOutStatus(502), 529);
  assert.equal(mapOutStatus(429), 429);
});
