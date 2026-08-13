#!/usr/bin/env node
// Wire recorder: stands in for chatgpt.com/backend-api so we can see EXACTLY what a client sends.
// Records method+path+headers+body to <outdir>/<label>-NNN.json, then answers with a minimal but
// VALID Responses SSE stream so the client completes its turn and shows us a whole conversation
// rather than one aborted request.
import http from 'node:http';
import { writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

const PORT = Number(process.env.PORT || 8899);
const OUT = process.env.OUT || '/tmp/wire-capture';
const LABEL = process.env.LABEL || 'req';
mkdirSync(OUT, { recursive: true });
let n = 0;

const SSE = (id) => [
  `event: response.created\ndata: ${JSON.stringify({ type: 'response.created', response: { id, status: 'in_progress' } })}\n\n`,
  `event: response.output_text.delta\ndata: ${JSON.stringify({ type: 'response.output_text.delta', delta: 'ok' })}\n\n`,
  `event: response.completed\ndata: ${JSON.stringify({ type: 'response.completed', response: { id, status: 'completed', output: [{ type: 'message', role: 'assistant', content: [{ type: 'output_text', text: 'ok' }] }], usage: { input_tokens: 10, output_tokens: 1 } } })}\n\n`,
].join('');

http.createServer((req, res) => {
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => {
    // Bodies may be COMPRESSED (codex sends content-encoding: zstd), so keep the raw bytes
    // verbatim in a sidecar and only attempt JSON on the decoded text. Reading a zstd frame as
    // utf-8 corrupts it irrecoverably — that mistake cost one capture round.
    const buf = Buffer.concat(chunks);
    const enc = (req.headers['content-encoding'] || '').toLowerCase();
    let body = null;
    if (!enc) { const t = buf.toString('utf8'); try { body = JSON.parse(t); } catch { body = t; } }
    const seq = ++n;
    const stem = `${LABEL}-${String(seq).padStart(3, '0')}`;
    if (buf.length) writeFileSync(join(OUT, `${stem}.bin`), buf);
    const rec = { seq, method: req.method, url: req.url, headers: req.headers, encoding: enc || null, bodyBytes: buf.length, body };
    const f = join(OUT, `${stem}.json`);
    writeFileSync(f, JSON.stringify(rec, null, 2));
    console.error(`[rec] ${req.method} ${req.url} -> ${stem} (${buf.length}b ${enc || 'identity'})`);

    if (req.url.includes('/responses')) {
      res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' });
      res.end(SSE(`resp_${n}`));
    } else {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end('{}');
    }
  });
}).listen(PORT, '127.0.0.1', () => console.error(`[rec] listening :${PORT} -> ${OUT}`));
