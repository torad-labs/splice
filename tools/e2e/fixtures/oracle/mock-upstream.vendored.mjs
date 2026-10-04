// VENDORED, DO NOT EDIT — byte-identical extraction of the mock upstream region from
// server/test/codex-proxy.test.mjs, between the two markers replay.mjs slices on. Vendored
// 2026-08-10 so the migration oracle survives P8-CUT's deletion of server/: replay.mjs read that
// file at RUNTIME and sha256-gated it, so the enumerated destruction would have taken the 11
// byte-exact fixtures with it.
//
// The trailing marker line is retained so the same slice works here as against the original; the
// sha256 of the sliced region is pinned in fixtures/_manifest.json exactly as before. While
// server/ still exists replay.mjs re-extracts from it and fails on divergence from this copy.
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
