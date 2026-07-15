#!/usr/bin/env node
// Behavior tests for the splice codex proxy (v30) — real assembled server
// against a mock ChatGPT upstream (scenario-mock pattern, ported from v29).
//
// v29 coverage carried: multi-part reasoning summaries (one thinking block,
// full mirror), honest failures (failed/truncated/idle → SSE error, never a
// clean empty end_turn), tool_call assembly, non-stream translation + UTF-8
// split chunks, 401 single-flight refresh, builder determinism/images,
// effort precedence (v27), gateway discovery, output clamp (v26).
//
// New (the three Eli gaps):
//   - SCENARIO:overflow_sse — context overflow via response.failed rewrites to
//     "prompt is too long" invalid_request_error (the P0 the old tree died on)
//   - compaction fixture matching the REAL v2.1.207 shape (verbatim summarizer
//     prompt WITH tools) → detected, tools stripped upstream, summary delivered
//   - client abort mid-stream: slot freed once, upstream aborted, no fake
//     end_turn, no crash
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtempSync, writeFileSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';

// ── Temp auth + state (never touches real ~/.codex or ~/.claude-codex) ──────
const authDir = mkdtempSync(join(tmpdir(), 'claudex-test-auth-'));
const AUTH_PATH = join(authDir, 'auth.json');
writeFileSync(AUTH_PATH, JSON.stringify({
  auth_mode: 'chatgpt',
  tokens: { id_token: 'id-1', access_token: 'tok-old', refresh_token: 'refresh-1', account_id: 'acct-1' },
  last_refresh: '2026-01-01T00:00:00Z',
}));
const stateRoot = mkdtempSync(join(tmpdir(), 'claudex-test-state-'));

// ── Mock upstream: scenario picked from a SCENARIO:<name> tag in instructions ──
const upstreamAuths = [];
const upstreamBodies = [];
const abortedScenarios = [];
let refreshCalls = 0;

function sseLine(res, evt) {
  res.write(`data: ${JSON.stringify(evt)}\n\n`);
}

const mock = http.createServer((req, res) => {
  let raw = '';
  req.on('data', (c) => { raw += c; });
  req.on('end', () => {
    if (req.url === '/oauth/token') {
      refreshCalls += 1;
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ access_token: 'tok-new', refresh_token: 'refresh-2', id_token: 'id-2' }));
      return;
    }
    const body = JSON.parse(raw);
    const scenario = (/SCENARIO:(\w+)/.exec(body.instructions || '') || [])[1] || 'basic';
    upstreamAuths.push({ scenario, auth: req.headers.authorization });
    upstreamBodies.push({ scenario, body });

    if (scenario === 'refresh' && req.headers.authorization === 'Bearer tok-old') {
      res.writeHead(401, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: { message: 'token expired' } }));
      return;
    }

    res.writeHead(200, { 'Content-Type': 'text/event-stream' });
    res.flushHeaders?.();

    if (scenario === 'multipart') {
      sseLine(res, { type: 'response.output_item.added', output_index: 0, item: { type: 'reasoning' } });
      sseLine(res, { type: 'response.reasoning_summary_part.added', output_index: 0 });
      sseLine(res, { type: 'response.reasoning_summary_text.delta', output_index: 0, delta: 'Part one.' });
      sseLine(res, { type: 'response.reasoning_summary_text.done', output_index: 0 });
      sseLine(res, { type: 'response.reasoning_summary_part.done', output_index: 0 });
      sseLine(res, { type: 'response.reasoning_summary_part.added', output_index: 0 });
      sseLine(res, { type: 'response.reasoning_summary_text.delta', output_index: 0, delta: 'Part two.' });
      sseLine(res, { type: 'response.reasoning_summary_text.done', output_index: 0 });
      sseLine(res, { type: 'response.reasoning_summary_part.done', output_index: 0 });
      sseLine(res, { type: 'response.output_item.done', output_index: 0 });
      sseLine(res, { type: 'response.output_item.added', output_index: 1, item: { type: 'message' } });
      sseLine(res, { type: 'response.output_text.delta', output_index: 1, delta: 'Answer text.' });
      sseLine(res, { type: 'response.output_item.done', output_index: 1 });
      sseLine(res, {
        type: 'response.completed',
        response: { id: 'r1', status: 'completed', output: [], usage: { input_tokens: 10, output_tokens: 5 } },
      });
      res.write('data: [DONE]\n\n');
      res.end();
    } else if (scenario === 'toolcall') {
      sseLine(res, { type: 'response.output_item.added', output_index: 0, item: { type: 'function_call', call_id: 'call_abc', name: 'get_thing' } });
      sseLine(res, { type: 'response.function_call_arguments.delta', output_index: 0, delta: '{"a":' });
      sseLine(res, { type: 'response.function_call_arguments.delta', output_index: 0, delta: '1}' });
      sseLine(res, { type: 'response.function_call_arguments.done', output_index: 0 });
      sseLine(res, { type: 'response.output_item.done', output_index: 0 });
      sseLine(res, {
        type: 'response.completed',
        response: { id: 'r2', status: 'completed', output: [], usage: { input_tokens: 4, output_tokens: 2 } },
      });
      res.end();
    } else if (scenario === 'failed') {
      sseLine(res, { type: 'response.failed', response: { error: { code: 'server_error', message: 'boom upstream' } } });
      res.end();
    } else if (scenario === 'overflow_sse') {
      // The P0 shape: a live overflow arrives via response.failed, not HTTP non-ok.
      sseLine(res, {
        type: 'response.failed',
        response: { error: { code: 'invalid_request_error', message: 'Your input exceeds the context window of this model. Please reduce the length.' } },
      });
      res.end();
    } else if (scenario === 'truncated') {
      sseLine(res, { type: 'response.output_item.added', output_index: 0, item: { type: 'message' } });
      sseLine(res, { type: 'response.output_text.delta', output_index: 0, delta: 'partial answer' });
      res.end(); // no response.completed
    } else if (scenario === 'idle') {
      // Stream STARTS (first byte) then stalls with no completion → the post-first-byte
      // idle watchdog (streamIdleMs) reaps it as a zombie.
      sseLine(res, { type: 'response.output_item.added', output_index: 0, item: { type: 'message' } });
      sseLine(res, { type: 'response.output_text.delta', output_index: 0, delta: 'partial' });
      setTimeout(() => { try { res.end(); } catch { /* ignore */ } }, 5_000).unref();
    } else if (scenario === 'prefill') {
      // Silent well past streamIdleMs (a big-context PREFILL, like a ~160k compaction)
      // then streams + completes. Must NOT be reaped: pre-first-byte idle is governed
      // by firstByteTimeoutMs, not streamIdleMs. This is the compaction regression.
      setTimeout(() => {
        sseLine(res, { type: 'response.output_item.added', output_index: 0, item: { type: 'message' } });
        sseLine(res, { type: 'response.output_text.delta', output_index: 0, delta: 'summary after slow prefill' });
        sseLine(res, { type: 'response.output_item.done', output_index: 0 });
        sseLine(res, { type: 'response.completed', response: { usage: { input_tokens: 1000, output_tokens: 5 } } });
        try { res.end(); } catch { /* ignore */ }
      }, 1500).unref();
    } else if (scenario === 'drip') {
      // endless deltas until the client (the proxy) aborts us — records the abort
      sseLine(res, { type: 'response.output_item.added', output_index: 0, item: { type: 'message' } });
      const timer = setInterval(() => sseLine(res, { type: 'response.output_text.delta', output_index: 0, delta: 'drip ' }), 40);
      timer.unref();
      req.on('close', () => { clearInterval(timer); abortedScenarios.push('drip'); });
    } else if (scenario === 'bigout') {
      sseLine(res, { type: 'response.output_item.added', output_index: 0, item: { type: 'message' } });
      sseLine(res, { type: 'response.output_text.delta', output_index: 0, delta: 'short summary' });
      sseLine(res, { type: 'response.output_item.done', output_index: 0 });
      sseLine(res, {
        type: 'response.completed',
        response: { id: 'rbig', status: 'completed', output: [], usage: { input_tokens: 500, output_tokens: 200000 } },
      });
      res.end();
    } else if (scenario === 'nonstream_tool') {
      const evt = {
        type: 'response.completed',
        response: {
          id: 'r3',
          status: 'completed',
          output: [
            { type: 'reasoning', summary: [{ type: 'summary_text', text: 'Because reasons that are long enough to mirror.' }] },
            { type: 'message', content: [{ type: 'output_text', text: 'héllo — ✓ done' }] },
            { type: 'function_call', call_id: 'call_xyz', name: 'fn_x', arguments: '{"q":"z"}' },
          ],
          usage: { input_tokens: 3, output_tokens: 2 },
        },
      };
      const buf = Buffer.from(`data: ${JSON.stringify(evt)}\n\n`, 'utf8');
      const splitAt = buf.indexOf(Buffer.from('✓', 'utf8')) + 1; // inside the 3-byte ✓
      res.write(buf.subarray(0, splitAt));
      setTimeout(() => { res.write(buf.subarray(splitAt)); res.end(); }, 20);
    } else if (scenario === 'compactish') {
      // a compact-shaped answer: reasoning summary only, empty text channel
      sseLine(res, { type: 'response.output_item.added', output_index: 0, item: { type: 'reasoning' } });
      sseLine(res, { type: 'response.reasoning_summary_text.delta', output_index: 0, delta: 'Goal: port the proxy. Decisions: split modules. Next: tests.' });
      sseLine(res, { type: 'response.output_item.done', output_index: 0 });
      sseLine(res, {
        type: 'response.completed',
        response: { id: 'rc', status: 'completed', output: [], usage: { input_tokens: 9, output_tokens: 3 } },
      });
      res.end();
    } else if (scenario === 'replaystream') {
      // Reasoning item carrying encrypted_content on its done event → the proxy
      // must emit a redacted_thinking replay block (stream path) AND the mirror.
      sseLine(res, { type: 'response.output_item.added', output_index: 0, item: { type: 'reasoning' } });
      sseLine(res, { type: 'response.reasoning_summary_text.delta', output_index: 0, delta: 'Long enough reasoning summary to mirror into text.' });
      sseLine(res, { type: 'response.output_item.done', output_index: 0, item: { type: 'reasoning', id: 'rs_stream', encrypted_content: 'ENC-STREAM' } });
      sseLine(res, { type: 'response.output_item.added', output_index: 1, item: { type: 'message' } });
      sseLine(res, { type: 'response.output_text.delta', output_index: 1, delta: 'answer' });
      sseLine(res, { type: 'response.output_item.done', output_index: 1 });
      sseLine(res, {
        type: 'response.completed',
        response: { id: 'rrs', status: 'completed', output: [], usage: { input_tokens: 7, output_tokens: 4 } },
      });
      res.end();
    } else {
      // basic / refresh-after-refresh: minimal text turn
      sseLine(res, { type: 'response.output_item.added', output_index: 0, item: { type: 'message' } });
      sseLine(res, { type: 'response.output_text.delta', output_index: 0, delta: 'ok after auth' });
      sseLine(res, { type: 'response.output_item.done', output_index: 0 });
      sseLine(res, {
        type: 'response.completed',
        response: { id: 'r4', status: 'completed', output: [], usage: { input_tokens: 1, output_tokens: 1 } },
      });
      res.end();
    }
  });
});
mock.listen(0, '127.0.0.1');
await once(mock, 'listening');
const mockPort = mock.address().port;

// ── Env BEFORE importing the proxy module ────────────────────────────────────
process.env.CODEX_PROXY_TEST = '1';
process.env.CODEX_AUTH_PATH = AUTH_PATH;
process.env.CLAUDEX_STATE_DIR = join(stateRoot, 'state');
process.env.CHATGPT_API_BASE = `http://127.0.0.1:${mockPort}`;
process.env.CODEX_OAUTH_TOKEN_URL = `http://127.0.0.1:${mockPort}/oauth/token`;
process.env.CLAUDEX_STREAM_IDLE_MS = '700';
process.env.CLAUDEX_UPSTREAM_RETRIES = '2';
process.env.CLAUDEX_SHOW_REASONING = 'text';
process.env.CLAUDEX_REASONING_EFFORT = 'high';
process.env.CLAUDEX_REASONING_SUMMARY = 'detailed';
// Replay ships OFF by default (it suppresses the reasoning wall); this suite
// exercises the replay wire path, so opt it on. Only the 'replaystream' scenario
// returns encrypted_content, so no other scenario is affected.
process.env.CLAUDEX_REPLAY_REASONING = '1';
delete process.env.CLAUDEX_MAX_INFLIGHT;

const proxy = await import('../src/codex-proxy.mjs');
const { getConfig } = await import('../src/config.mjs');
const { COMPACT_MARKER } = await import('../src/codex/compact.mjs');
const { decodeReasoningEnvelope } = await import('../src/reasoning/replay.mjs');
const server = proxy.createServer();
server.listen(0, '127.0.0.1');
await once(server, 'listening');
const proxyPort = server.address().port;

after(() => { server.close(); mock.close(); });

const buildReq = (body) => proxy.buildRequest(body, {
  compact: proxy.classifyCompact(body),
  config: getConfig(),
  originalModel: body.model,
}).req;

function postMessages(body) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: '127.0.0.1', port: proxyPort, path: '/v1/messages', method: 'POST', headers: { 'Content-Type': 'application/json' } },
      (res) => {
        let text = '';
        res.on('data', (c) => { text += c; });
        res.on('end', () => resolve({ status: res.statusCode, text }));
      },
    );
    req.on('error', reject);
    req.end(JSON.stringify(body));
  });
}

function getPath(path) {
  return new Promise((resolve, reject) => {
    http.get({ host: '127.0.0.1', port: proxyPort, path }, (res) => {
      let t = ''; res.on('data', (c) => { t += c; }); res.on('end', () => resolve({ status: res.statusCode, text: t }));
    }).on('error', reject);
  });
}

const baseBody = (scenario, extra = {}) => ({
  model: 'gpt-5.6-sol',
  system: `You are a test. SCENARIO:${scenario}`,
  messages: [{ role: 'user', content: 'go' }],
  stream: true,
  ...extra,
});

// ── Streaming behavior (v29 carries) ─────────────────────────────────────────

test('multi-part reasoning: one thinking block, no premature stop, full mirror', async () => {
  const { status, text } = await postMessages(baseBody('multipart'));
  assert.equal(status, 200);

  const thinkingStarts = [...text.matchAll(/"content_block_start".*?"type":"thinking"/g)];
  assert.equal(thinkingStarts.length, 1, `expected 1 thinking block, transcript:\n${text}`);

  const startMatch = /"type":"content_block_start","index":(\d+),"content_block":\{"type":"thinking"/.exec(text);
  assert.ok(startMatch, 'thinking content_block_start present');
  const thinkIdx = startMatch[1];

  const partTwoPos = text.indexOf('Part two.');
  const stopPos = text.indexOf(`{"type":"content_block_stop","index":${thinkIdx}}`);
  assert.ok(partTwoPos > -1, 'Part two delta streamed');
  assert.ok(stopPos > partTwoPos, 'thinking block closed only after ALL parts (v24 closed after part 1)');

  assert.ok(/"thinking":"\\n\\n"/.test(text), 'part separator delta emitted');

  // LOAD-BEARING: mirror text block carries the full summary
  assert.ok(text.includes('[reasoning summary]'), 'mirror block present');
  const mirrorDelta = [...text.matchAll(/"text_delta","text":"((?:[^"\\]|\\.)*)"/g)].map((m) => m[1]).join('');
  assert.ok(mirrorDelta.includes('Part one.'), 'mirror contains part one');
  assert.ok(mirrorDelta.includes('Part two.'), 'mirror contains part two');

  assert.ok(text.includes('"stop_reason":"end_turn"'));
  assert.ok(text.includes('"type":"message_stop"'));
});

test('replay (stream): encrypted reasoning → redacted_thinking block, alongside the mirror, with include + prompt_cache_key sent', async () => {
  const { status, text } = await postMessages(baseBody('replaystream'));
  assert.equal(status, 200);

  // Client receives an encrypted-reasoning replay block that round-trips exactly
  const m = /"content_block_start","index":\d+,"content_block":\{"type":"redacted_thinking","data":"([^"]+)"/.exec(text);
  assert.ok(m, `expected a redacted_thinking block, transcript:\n${text}`);
  assert.equal(decodeReasoningEnvelope(m[1]).encrypted_content, 'ENC-STREAM', 'envelope round-trips to the backend blob');
  assert.ok(text.includes('[reasoning summary]'), 'the mirror still runs alongside replay (both channels)');

  // The request the proxy SENT upstream carried the replay + cache-key fields
  const sent = upstreamBodies.find((u) => u.scenario === 'replaystream').body;
  assert.deepEqual(sent.include, ['reasoning.encrypted_content'], 'include requests encrypted reasoning back');
  assert.ok(typeof sent.prompt_cache_key === 'string' && sent.prompt_cache_key.startsWith('splice-'), 'stable prompt_cache_key sent');
  assert.equal(sent.store, false, 'store:false is the required replay pairing');
});

test('output_tokens clamped to client max_tokens (compaction overflow guard)', async () => {
  const { status, text } = await postMessages(baseBody('bigout', { max_tokens: 128000 }));
  assert.equal(status, 200);
  const m = /"type":"message_delta"[\s\S]*?"output_tokens":(\d+)/.exec(text);
  assert.ok(m, 'message_delta usage present');
  assert.ok(Number(m[1]) <= 128000, `output_tokens must be clamped to max_tokens, got ${m[1]}`);
  assert.ok(text.includes('short summary'), 'summary content delivered');
});

test('output_tokens NOT clamped when under max_tokens', async () => {
  const { text } = await postMessages(baseBody('basic', { max_tokens: 128000 }));
  const m = /"type":"message_delta"[\s\S]*?"output_tokens":(\d+)/.exec(text);
  assert.ok(m && Number(m[1]) === 1, `normal small output unchanged, got ${m?.[1]}`);
});

test('tool call assembly: id, name, streamed args, stop_reason tool_use', async () => {
  const { status, text } = await postMessages(baseBody('toolcall', { tools: [{ name: 'get_thing', input_schema: { type: 'object' } }] }));
  assert.equal(status, 200);
  assert.ok(text.includes('"type":"tool_use","id":"call_abc","name":"get_thing"'));
  const args = [...text.matchAll(/"partial_json":"((?:[^"\\]|\\.)*)"/g)].map((m) => m[1]).join('');
  assert.equal(args.replace(/\\"/g, '"'), '{"a":1}');
  assert.ok(text.includes('"stop_reason":"tool_use"'));
});

test('response.failed → SSE error event, never a clean end_turn', async () => {
  const { text } = await postMessages(baseBody('failed'));
  assert.ok(text.includes('event: error'), `expected SSE error, got:\n${text}`);
  assert.ok(text.includes('boom upstream'));
  assert.ok(!text.includes('"type":"message_stop"'), 'no fake clean completion');
});

// ── Eli gap #1: overflow arriving via SSE response.failed ────────────────────

test('SCENARIO:overflow_sse — SSE overflow rewrites to invalid_request_error "prompt is too long"', async () => {
  const { text } = await postMessages(baseBody('overflow_sse'));
  assert.ok(text.includes('event: error'), `expected SSE error, got:\n${text}`);
  assert.ok(text.includes('"type":"invalid_request_error"'), 'overflow classified as invalid_request_error (v29 sent api_error)');
  assert.ok(/prompt is too long/i.test(text), 'carries the phrase Claude Code compacts on');
  assert.ok(!text.includes('"type":"message_stop"'), 'no fake clean completion');
});

test('truncated stream (no response.completed) → SSE error', async () => {
  const { text } = await postMessages(baseBody('truncated'));
  assert.ok(text.includes('event: error'));
  assert.ok(text.includes('without response.completed'));
  assert.ok(!text.includes('"type":"message_stop"'));
});

test('idle upstream → watchdog aborts and surfaces SSE error (not empty end_turn)', async () => {
  const { text } = await postMessages(baseBody('idle'));
  assert.ok(text.includes('event: error'), `expected idle error, got:\n${text}`);
  assert.ok(/idle/i.test(text));
  assert.ok(!text.includes('"type":"message_stop"'));
});

test('slow prefill (silent past streamIdleMs, then streams) is NOT reaped — the compaction fix', async () => {
  // A ~160k compaction is silent for minutes while the backend prefills, then streams.
  // Before this fix the idle watchdog reaped it at streamIdleMs (700ms here) → every
  // compaction aborted → retry loop re-read the transcript. It must complete cleanly.
  const { text } = await postMessages(baseBody('prefill'));
  assert.ok(text.includes('summary after slow prefill'), `prefill must complete, got:\n${text}`);
  assert.ok(text.includes('"type":"message_stop"'), 'clean completion, not an abort');
  assert.ok(!text.includes('event: error'), 'no abort error for a legit slow prefill');
});

// ── Eli gap #3: client abort mid-stream ──────────────────────────────────────

test('client abort mid-stream: upstream aborted, slot freed once, no fake end_turn', async () => {
  const before = JSON.parse((await getPath('/stats')).text).gate;
  const partial = await new Promise((resolve, reject) => {
    let buf = '';
    const req = http.request(
      { host: '127.0.0.1', port: proxyPort, path: '/v1/messages', method: 'POST', headers: { 'Content-Type': 'application/json' } },
      (res) => {
        res.on('data', (c) => {
          buf += c;
          if (buf.includes('drip drip')) req.destroy(); // abort mid-stream
        });
        res.on('close', () => resolve(buf));
        res.on('error', () => resolve(buf));
      },
    );
    req.on('error', () => resolve(buf)); // destroy() surfaces here
    setTimeout(() => reject(new Error('drip never aborted')), 5000).unref();
    req.end(JSON.stringify(baseBody('drip')));
  });
  assert.ok(!partial.includes('"type":"message_stop"'), 'aborted stream must not end with a clean stop');

  // upstream abort + single slot release propagate async — poll briefly
  for (let i = 0; i < 50; i++) {
    const gate = JSON.parse((await getPath('/stats')).text).gate;
    if (gate.inflight === 0 && gate.released >= before.released + 1) break;
    await new Promise((r) => setTimeout(r, 50));
  }
  const gate = JSON.parse((await getPath('/stats')).text).gate;
  assert.equal(gate.inflight, 0, 'slot freed');
  assert.equal(gate.acquired, gate.released, 'released exactly matches acquired (freed once)');
  assert.ok(abortedScenarios.includes('drip'), 'upstream request actually aborted');
});

// ── Non-stream path ───────────────────────────────────────────────────────────

test('non-stream: tool name from final output, UTF-8 intact across split chunks, mirror parity', async () => {
  const { status, text } = await postMessages(baseBody('nonstream_tool', { stream: false }));
  assert.equal(status, 200);
  const resp = JSON.parse(text);
  const tool = resp.content.find((c) => c.type === 'tool_use');
  assert.ok(tool, 'tool_use present');
  assert.equal(tool.name, 'fn_x');
  assert.equal(tool.id, 'call_xyz');
  assert.deepEqual(tool.input, { q: 'z' });
  const txt = resp.content.find((c) => c.type === 'text' && c.text.includes('héllo'));
  assert.ok(txt, 'text block present');
  assert.ok(txt.text.includes('héllo — ✓ done'), 'multi-byte chars survived split chunks');
  assert.ok(resp.content.some((c) => c.type === 'thinking'), 'thinking block translated');
  assert.ok(resp.content.some((c) => c.type === 'text' && c.text.includes('[reasoning summary]')), 'non-stream mirror parity (L2: same mirrorInto)');
  assert.equal(resp.stop_reason, 'tool_use');
});

// ── 401 → OAuth refresh → retry ───────────────────────────────────────────────

test('401 triggers single-flight refresh, retry with new token, auth.json rewritten', async () => {
  // dedicated mini-mock behavior: the 'refresh' scenario 401s old tokens
  const { status, text } = await postMessages(baseBody('refresh'));
  assert.equal(status, 200);
  assert.ok(text.includes('ok after auth'), `expected success after refresh, got:\n${text}`);
  assert.equal(refreshCalls, 1, 'exactly one refresh call');
  const calls = upstreamAuths.filter((u) => u.scenario === 'refresh');
  assert.deepEqual(calls.map((c) => c.auth), ['Bearer tok-old', 'Bearer tok-new']);
  const saved = JSON.parse(readFileSync(AUTH_PATH, 'utf8'));
  assert.equal(saved.tokens.access_token, 'tok-new');
  assert.equal(saved.tokens.refresh_token, 'refresh-2');
  assert.equal(saved.tokens.account_id, 'acct-1', 'account_id preserved');
});

// ── Eli gap #2: compaction detection matches the REAL v2.1.207 shape ─────────

const REAL_COMPACT_FIXTURE = (scenario = 'compactish') => ({
  model: 'gpt-5.6-sol',
  // The verbatim summarizer prompt from the v2.1.207 binary trace — identical
  // for auto + manual compact. SCENARIO tag rides in a second system block.
  system: [
    { type: 'text', text: 'You are a helpful AI assistant tasked with summarizing conversations.' },
    { type: 'text', text: `SCENARIO:${scenario}` },
  ],
  // Real compaction requests CARRY TOOLS (Read + ToolSearch + MCP by default).
  tools: [
    { name: 'Read', input_schema: { type: 'object' } },
    { name: 'ToolSearch', input_schema: { type: 'object' } },
    { name: 'mcp__eli-brain__recall', input_schema: { type: 'object' } },
  ],
  messages: [{ role: 'user', content: `<conversation>${'transcript '.repeat(80)}</conversation>` }],
  stream: true,
  max_tokens: 128000,
});

test('canary: the verbatim summarizer marker is pinned (drift fails loudly)', () => {
  assert.equal(COMPACT_MARKER, 'tasked with summarizing conversations');
  assert.ok(
    'You are a helpful AI assistant tasked with summarizing conversations.'.includes(COMPACT_MARKER),
    'marker must be a substring of the traced v2.1.207 summarizer prompt',
  );
});

test('compact detection: REAL shape (marker + tools) detected; tools-agnostic', () => {
  assert.equal(proxy.classifyCompact(REAL_COMPACT_FIXTURE()), true, 'v29 rejected this real shape because tools were present');
  assert.equal(proxy.classifyCompact({
    system: 'You are a helpful AI assistant tasked with summarizing conversations.',
    messages: [{ role: 'user', content: 'x' }],
  }), true, 'toolless marker still detected');
});

test('compact detection: post-compact resume and long toolless dumps do NOT match', () => {
  assert.equal(proxy.classifyCompact({
    system: 'You are Claude Code.',
    messages: [{ role: 'user', content: 'This session is being continued from a previous conversation…' }],
  }), false, 'post-compact resume is a normal turn (v13 bug class)');
  assert.equal(proxy.classifyCompact({
    system: 'You are an assistant.',
    messages: [{ role: 'user', content: `Analyze this page. ${'The message content here. '.repeat(500)}` }],
  }), false, 'long toolless dump is NOT compact (heuristics stay dead)');
  assert.equal(proxy.classifyCompact({
    system: 'x',
    messages: [{ role: 'user', content: `<conversation>${'y'.repeat(600)}</conversation>` }],
  }), false, 'conversation tags alone are a content heuristic — dead in splice');
});

test('compact detection: 2.1.207 marker that moved to a user message / new phrasing still matches', () => {
  // 2.1.207 moved the summarizer instruction out of the system into the last user message.
  assert.equal(proxy.classifyCompact({
    system: 'You are Claude Code, an interactive CLI tool.',
    messages: [
      { role: 'user', content: 'earlier turn' },
      { role: 'user', content: 'Your task is to create a detailed summary of this conversation. This summary will be placed at the start of a continuing session.' },
    ],
  }), true, 'summarizer instruction in the last user message is detected');
  // Partial/microcompact summarizer as the system prompt.
  assert.equal(proxy.classifyCompact({
    system: 'Summarize this portion of a Claude Code session transcript. Focus on: key decisions.',
    messages: [{ role: 'user', content: 'x' }],
  }), true, 'portion summarizer in the system is detected');
});

test('compact model: default runs on the session model (pinnedModel) + session effort; explicit compactModel still overrides the model; normal turns untouched', () => {
  const body = REAL_COMPACT_FIXTURE();
  // Default (empty compactModel): compaction mirrors the session — model = pinnedModel,
  // effort = the session effort (NOT a hardcoded low) — so it shares the warm cache.
  const dflt = proxy.buildRequest(body, { compact: true, config: { ...getConfig(), compactModel: '' }, originalModel: body.model });
  assert.equal(dflt.req.model, 'gpt-5.6-sol', 'empty compactModel runs compaction on the session model (pinnedModel)');
  assert.deepEqual(dflt.req.reasoning, { effort: 'high', summary: 'detailed' }, 'compact inherits the session effort, no hardcoded low');

  // Explicit override still forces a specific compact model (at the same session effort).
  const on = proxy.buildRequest(body, { compact: true, config: { ...getConfig(), compactModel: 'gpt-5.4-mini' }, originalModel: body.model });
  assert.equal(on.req.model, 'gpt-5.4-mini', 'explicit compactModel still overrides the model');
  assert.deepEqual(on.req.reasoning, { effort: 'high', summary: 'detailed' }, 'override changes the model, not the (session) effort');

  const normal = proxy.buildRequest(
    { model: 'gpt-5.6-sol', messages: [{ role: 'user', content: 'hi' }] },
    { compact: false, config: { ...getConfig(), compactModel: 'gpt-5.4-mini' }, originalModel: 'gpt-5.6-sol' },
  );
  assert.equal(normal.req.model, 'gpt-5.6-sol', 'normal turns never swap the model');
});

test('compact end-to-end: tools stripped upstream, session effort, summary text delivered', async () => {
  const { status, text } = await postMessages(REAL_COMPACT_FIXTURE());
  assert.equal(status, 200);
  const up = upstreamBodies.filter((b) => b.scenario === 'compactish').at(-1);
  assert.ok(up, 'upstream received the compact request');
  assert.equal(up.body.tools, undefined, 'tools stripped upstream (forces text)');
  assert.ok(/COMPACT MODE/.test(up.body.instructions), 'compact instructions attached');
  assert.deepEqual(up.body.reasoning, { effort: 'high', summary: 'detailed' }, 'compact runs at the session effort (mirrors a normal turn), not forced low');
  // The model answered thinking-only → promote-to-text delivers a REAL summary
  const textDeltas = [...text.matchAll(/"text_delta","text":"((?:[^"\\]|\\.)*)"/g)].map((m) => m[1]).join('');
  assert.ok(textDeltas.includes('Goal: port the proxy.'), 'summary text present (promoted from model thinking)');
  assert.ok(text.includes('"stop_reason":"end_turn"'), 'clean completion');
});

// ── Pure units: request builder ──────────────────────────────────────────────

test('buildRequest: deterministic output for identical logical input', () => {
  const mk = () => ({
    model: 'gpt-5.6-sol',
    system: 'sys',
    messages: [
      { role: 'user', content: 'hi' },
      { role: 'assistant', content: [{ type: 'text', text: 'yo' }, { type: 'tool_use', id: 'call_1', name: 'f', input: { a: 1 } }] },
      { role: 'user', content: [{ type: 'tool_result', tool_use_id: 'call_1', content: 'out' }] },
    ],
    tools: [{ name: 'f', description: 'd', input_schema: { type: 'object' } }],
  });
  assert.equal(JSON.stringify(buildReq(mk())), JSON.stringify(buildReq(mk())));
});

test('buildRequest: PURE — the incoming body is never mutated (no magic props)', () => {
  const body = baseBody('basic');
  const before = JSON.stringify(body);
  buildReq(body);
  assert.equal(JSON.stringify(body), before, 'body untouched (v29 stapled __claudex* props onto it)');
});

test('buildRequest: images become input_image, tool_result images ride along', () => {
  const req = buildReq({
    model: 'gpt-5.6-sol',
    messages: [
      { role: 'user', content: [{ type: 'image', source: { type: 'base64', media_type: 'image/png', data: 'AAAA' } }] },
      { role: 'user', content: [{ type: 'tool_result', tool_use_id: 'call_9', content: [{ type: 'text', text: 'saw it' }, { type: 'image', source: { type: 'base64', media_type: 'image/jpeg', data: 'BBBB' } }] }] },
    ],
  });
  const img = req.input.find((i) => Array.isArray(i.content) && i.content.some((p) => p.type === 'input_image'));
  assert.ok(img, 'input_image item present');
  assert.equal(img.content[0].image_url, 'data:image/png;base64,AAAA');
  const fco = req.input.find((i) => i.type === 'function_call_output');
  assert.equal(fco.output, 'saw it');
  const ride = req.input.find((i) => Array.isArray(i.content) && i.content.some((p) => p.type === 'input_text' && /tool_result call_9/.test(p.text)));
  assert.ok(ride, 'tool_result image rides in a follow-up user item');
  assert.equal(ride.content.find((p) => p.type === 'input_image').image_url, 'data:image/jpeg;base64,BBBB');
});

test('gateway discovery: GET /v1/models returns every codex model as a claude-prefixed id', async () => {
  const { status, text } = await getPath('/v1/models?limit=1000');
  assert.equal(status, 200);
  const j = JSON.parse(text);
  assert.ok(Array.isArray(j.data) && j.data.length > 0, 'has model list');
  for (const m of j.data) assert.match(m.id, /^claude/, `id ${m.id} must be claude-prefixed`);
  const ids = j.data.map((m) => m.id);
  assert.ok(ids.includes('claude-codex--gpt-5.6-luna'), 'Luna present (wrapped)');
  assert.ok(ids.includes('claude-codex--gpt-5.6-terra'), 'Terra present (wrapped)');
  assert.ok(ids.includes('claude-codex--gpt-5.6-sol'), 'Sol now included — it needs a discovery row for its label');
  assert.ok(!ids.includes('claude-codex--gpt-5.3-mini'), 'dropped speculative model absent');
  assert.ok(j.data.every((m) => m.display_name), 'each has a display_name for the picker');
});

test('discovery-wrapped model unwraps to the real codex id for the upstream request', () => {
  const req = buildReq({ model: 'claude-codex--gpt-5.6-terra', messages: [{ role: 'user', content: 'x' }] });
  assert.equal(req.model, 'gpt-5.6-terra');
});

test('claude-* models get an honest error (dead passthrough removed)', async () => {
  const { status, text } = await postMessages({ model: 'claude-fable-5', messages: [{ role: 'user', content: 'x' }], stream: false });
  assert.equal(status, 400);
  assert.ok(/no Anthropic credentials/.test(text), 'honest error, not a doomed forward');
});

test('effort precedence (v27): harness /effort (thinking budget) wins over env; low pick survives', () => {
  const body = (budget) => ({
    model: 'gpt-5.6-sol',
    messages: [{ role: 'user', content: 'x' }],
    thinking: { type: 'enabled', budget_tokens: budget },
  });
  assert.equal(buildReq(body(128000)).reasoning.effort, 'max', 'big budget → max, beats env=high');
  assert.equal(buildReq(body(12000)).reasoning.effort, 'high', 'mid budget → high');
  assert.equal(buildReq(body(1500)).reasoning.effort, 'low', 'low budget stays low (not floored to high)');
  const noThink = { model: 'gpt-5.6-sol', messages: [{ role: 'user', content: 'x' }] };
  assert.equal(buildReq(noThink).reasoning.effort, 'high', 'no budget → env fallback (high)');
});
