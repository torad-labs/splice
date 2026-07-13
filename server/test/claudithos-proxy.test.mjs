#!/usr/bin/env node
// Behavior tests for claudithos-proxy v3 — the mythos memory-architecture
// experiment. Ported from the v2 suite; the three arms and the byte-faithful
// response passthrough are the contract.
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';

// Temp credentials + state (never touches real ~/.claude)
const credDir = mkdtempSync(join(tmpdir(), 'claudithos-test-'));
const CRED_PATH = join(credDir, 'credentials.json');
writeFileSync(CRED_PATH, JSON.stringify({
  claudeAiOauth: { accessToken: 'oauth-tok-1', refreshToken: 'r', expiresAt: 4102444800000 },
}));

// Mock Anthropic upstream: capture request, reply with a thinking+text SSE stream
const captured = [];
const mock = http.createServer((req, res) => {
  let raw = '';
  req.on('data', (c) => { raw += c; });
  req.on('end', () => {
    captured.push({ url: req.url, auth: req.headers.authorization, beta: req.headers['anthropic-beta'], body: raw ? JSON.parse(raw) : null });
    res.writeHead(200, { 'Content-Type': 'text/event-stream' });
    const ev = (name, data) => res.write(`event: ${name}\ndata: ${JSON.stringify(data)}\n\n`);
    ev('message_start', { type: 'message_start', message: { id: 'msg_1', type: 'message', role: 'assistant', content: [], model: 'claude-fable-5', usage: { input_tokens: 5, output_tokens: 0 } } });
    ev('content_block_start', { type: 'content_block_start', index: 0, content_block: { type: 'thinking', thinking: '' } });
    ev('content_block_delta', { type: 'content_block_delta', index: 0, delta: { type: 'thinking_delta', thinking: 'Deriving the invariant from the receipts first.' } });
    ev('content_block_stop', { type: 'content_block_stop', index: 0 });
    ev('content_block_start', { type: 'content_block_start', index: 1, content_block: { type: 'text', text: '' } });
    ev('content_block_delta', { type: 'content_block_delta', index: 1, delta: { type: 'text_delta', text: 'Answer.' } });
    ev('content_block_stop', { type: 'content_block_stop', index: 1 });
    ev('message_delta', { type: 'message_delta', delta: { stop_reason: 'end_turn', stop_sequence: null }, usage: { output_tokens: 9 } });
    ev('message_stop', { type: 'message_stop' });
    res.end();
  });
});
mock.listen(0, '127.0.0.1');
await once(mock, 'listening');

// Env BEFORE import (mode is read live per request — arm flips below work)
process.env.CLAUDITHOS_TEST = '1';
process.env.CLAUDEX_STATE_DIR = join(credDir, 'state');
process.env.CLAUDE_CREDENTIALS_PATH = CRED_PATH;
process.env.ANTHROPIC_UPSTREAM = `http://127.0.0.1:${mock.address().port}`;
process.env.CLAUDITHOS_MODE = 'mirror';

const proxy = await import('../src/claudithos-proxy.mjs');
const server = proxy.createServer();
server.listen(0, '127.0.0.1');
await once(server, 'listening');
const proxyPort = server.address().port;

after(() => { server.close(); mock.close(); });

function post(body) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: '127.0.0.1', port: proxyPort, path: '/v1/messages', method: 'POST', headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer mythos-local', 'anthropic-beta': 'interleaved-thinking-2025-05-14' } },
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

const agentTranscript = () => ({
  model: 'claude-fable-5',
  system: 'sys',
  stream: true,
  thinking: { type: 'enabled', budget_tokens: 10000 },
  messages: [
    { role: 'user', content: 'find the bug' },
    {
      role: 'assistant',
      content: [
        { type: 'thinking', thinking: 'private chain that must not replay', signature: 'sig123' },
        { type: 'tool_use', id: 'toolu_1', name: 'Read', input: { file_path: '/x.rs' } },
      ],
    },
    {
      role: 'user',
      content: [
        {
          type: 'tool_result',
          tool_use_id: 'toolu_1',
          content: [
            { type: 'text', text: 'fn main() {}' },
            { type: 'image', source: { type: 'base64', media_type: 'image/png', data: 'AAAA' } },
          ],
          cache_control: { type: 'ephemeral' },
        },
      ],
    },
  ],
  tools: [{ name: 'Read', input_schema: { type: 'object' } }],
});

// ── Unit: the A transform ────────────────────────────────────────────────────

test('transformMessages amnesia: drops thinking, textualizes tools, preserves roles/images/cache_control', () => {
  const out = proxy.transformMessages(agentTranscript().messages); // default: mirrorThinking false
  assert.equal(out.length, 3, 'message boundaries preserved');
  assert.deepEqual(out.map((m) => m.role), ['user', 'assistant', 'user'], 'role alternation preserved');

  const asst = out[1];
  assert.ok(!asst.content.some((b) => b.type === 'thinking'), 'thinking dropped');
  assert.ok(!asst.content.some((b) => /reasoning summary/.test(b.text || '')), 'no mirror in amnesia');
  assert.ok(!asst.content.some((b) => b.type === 'tool_use'), 'tool_use textualized');
  const call = asst.content.find((b) => b.type === 'text');
  assert.ok(/\[called Read \(toolu_1\)\]/.test(call.text), 'call text carries name+id');
  assert.ok(call.text.includes('"/x.rs"'), 'call text carries args');

  const toolMsg = out[2];
  assert.ok(!toolMsg.content.some((b) => b.type === 'tool_result'), 'tool_result textualized');
  const resultText = toolMsg.content.find((b) => b.type === 'text');
  assert.ok(/\[toolu_1 result\]/.test(resultText.text));
  assert.ok(resultText.text.includes('fn main() {}'));
  assert.deepEqual(resultText.cache_control, { type: 'ephemeral' }, 'cache breakpoint preserved');
  assert.ok(toolMsg.content.some((b) => b.type === 'image'), 'image survives textualization');
});

test('transformMessages mirror: thinking becomes [reasoning summary] text (B — the notebook)', () => {
  const out = proxy.transformMessages(agentTranscript().messages, { mirrorThinking: true });
  const asst = out[1];
  assert.ok(!asst.content.some((b) => b.type === 'thinking'), 'signed thinking still never replays');
  const mirror = asst.content.find((b) => b.type === 'text' && /\[reasoning summary\]/.test(b.text));
  assert.ok(mirror, 'past thinking persists as text');
  assert.ok(mirror.text.includes('private chain that must not replay'), 'distilled content carried into context');
  assert.ok(asst.content.some((b) => /\[called Read/.test(b.text || '')), 'tool_use still textualized in mirror');
});

test('transformMessages: string content and plain text untouched; empty content guarded', () => {
  const out = proxy.transformMessages([
    { role: 'user', content: 'plain' },
    { role: 'assistant', content: [{ type: 'text', text: 'kept' }] },
    { role: 'assistant', content: [{ type: 'thinking', thinking: 'only chain', signature: 's' }] },
  ]);
  assert.equal(out[0].content, 'plain');
  assert.equal(out[1].content[0].text, 'kept');
  assert.equal(out[2].content[0].text, '[empty]', 'thinking-only message gets placeholder, never empty (amnesia)');
});

// ── Integration: the three arms ──────────────────────────────────────────────

test('amnesia arm: upstream sees textualized history, real OAuth, oauth beta appended; no mirror', async () => {
  process.env.CLAUDITHOS_MODE = 'amnesia';
  const { status, text } = await post(agentTranscript());
  assert.equal(status, 200);
  const up = captured.at(-1);
  assert.equal(up.auth, 'Bearer oauth-tok-1', 'dummy token replaced with real OAuth');
  assert.ok(/oauth-2025-04-20/.test(up.beta), 'oauth beta appended');
  assert.ok(/interleaved-thinking/.test(up.beta), 'client beta preserved');
  const blocks = up.body.messages.flatMap((m) => (Array.isArray(m.content) ? m.content : []));
  assert.ok(!blocks.some((b) => b.type === 'thinking'), 'no thinking upstream');
  assert.ok(!blocks.some((b) => b.type === 'tool_use' || b.type === 'tool_result'), 'no native tool blocks upstream');
  assert.ok(up.body.tools?.length === 1, 'tools stay available for new calls');
  assert.ok(!text.includes('[reasoning summary]'), 'amnesia does not inject mirror');
  assert.ok(text.includes('thinking_delta'), 'thinking still streams to the client UI');
});

test('mirror arm: history thinking→text upstream; RESPONSE is byte-faithful passthrough (loop intact)', async () => {
  process.env.CLAUDITHOS_MODE = 'mirror';
  const { status, text } = await post(agentTranscript());
  assert.equal(status, 200);
  const up = captured.at(-1);
  const blocks = up.body.messages.flatMap((m) => (Array.isArray(m.content) ? m.content : []));
  assert.ok(!blocks.some((b) => b.type === 'thinking'), 'no signed thinking upstream');
  assert.ok(blocks.some((b) => /\[reasoning summary\]/.test(b.text || '')), 'past thinking persisted as text (B)');
  const starts = [...text.matchAll(/"type":"content_block_start"/g)].length;
  assert.equal(starts, 2, 'response passthrough: exactly the 2 upstream blocks, none injected');
  assert.ok(text.includes('thinking_delta') && text.includes('Answer.'), 'upstream content intact');
});

test('native arm: request passes through byte-faithful (thinking + native tool blocks intact)', async () => {
  process.env.CLAUDITHOS_MODE = 'native';
  const sent = agentTranscript();
  const { status, text } = await post(sent);
  assert.equal(status, 200);
  const up = captured.at(-1);
  assert.deepEqual(up.body.messages, sent.messages, 'history untouched in control arm');
  const starts = [...text.matchAll(/"type":"content_block_start"/g)].length;
  assert.equal(starts, 2, 'response passthrough in control arm');
});

test('/mgmt/compact on this head carries the shared-state note', async () => {
  const { ensureMgmtKey } = await import('../src/mgmt/api.mjs');
  const key = ensureMgmtKey();
  const { status, text } = await new Promise((resolve, reject) => {
    http.get(
      { host: '127.0.0.1', port: proxyPort, path: '/mgmt/compact', headers: { Authorization: `Bearer ${key}` } },
      (res) => {
        let t = '';
        res.on('data', (c) => { t += c; });
        res.on('end', () => resolve({ status: res.statusCode, text: t }));
      },
    ).on('error', reject);
  });
  assert.equal(status, 200);
  const j = JSON.parse(text);
  assert.match(j.note, /codex head/, 'claudithos labels the shared stats file');
  assert.ok(Array.isArray(j.shadow), 'own shadow tail present');
});

test('health reports mode and v3', async () => {
  process.env.CLAUDITHOS_MODE = 'amnesia';
  const { status, text } = await new Promise((resolve, reject) => {
    http.get({ host: '127.0.0.1', port: proxyPort, path: '/health' }, (res) => {
      let t = '';
      res.on('data', (c) => { t += c; });
      res.on('end', () => resolve({ status: res.statusCode, text: t }));
    }).on('error', reject);
  });
  assert.equal(status, 200);
  const j = JSON.parse(text);
  assert.equal(j.mode, 'amnesia');
  assert.equal(j.version, '3');
});
