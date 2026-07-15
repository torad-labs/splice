#!/usr/bin/env node
// Replay a captured Anthropic /v1/messages transcript through the live proxy.
// Same bodies both arms; only the proxy's replayReasoning config differs.
// Cache hit% is read from the proxy log after each arm (see real-ab.sh).
//
// Usage:
//   node experiments/cache-replay/replay-captured.mjs \
//     --dir ~/.claude/jobs/.../capture --port 3097 --tag '[A]'
//
// --tag is prefixed onto the FIRST user message of every turn so each arm
// gets a distinct prompt_cache_key (stablePromptCacheKey hashes that seed).

import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

function arg(name, fallback = null) {
  const i = process.argv.indexOf(`--${name}`);
  if (i < 0) return fallback;
  return process.argv[i + 1] ?? fallback;
}

const DIR = resolve(arg('dir', ''));
const PORT = Number(arg('port', '3097'));
const TAG = arg('tag', '');
const TIMEOUT_MS = Number(arg('timeout', '300000'));

if (!DIR) {
  console.error('usage: replay-captured.mjs --dir <capture-dir> [--port 3097] [--tag "[A]"]');
  process.exit(2);
}

function saltBody(body, tag) {
  if (!tag) return body;
  const b = structuredClone(body);
  const firstUser = (b.messages ?? []).find((m) => m?.role === 'user');
  if (!firstUser) return b;
  if (typeof firstUser.content === 'string') {
    firstUser.content = `${tag} ${firstUser.content}`;
  } else if (Array.isArray(firstUser.content)) {
    const t = firstUser.content.find((x) => x?.type === 'text' && typeof x.text === 'string');
    if (t) t.text = `${tag} ${t.text}`;
  }
  return b;
}

/** Drain a streaming or non-streaming Anthropic response. Returns { status, bytes }. */
async function postTurn(body) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), TIMEOUT_MS);
  try {
    const res = await fetch(`http://127.0.0.1:${PORT}/v1/messages`, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'anthropic-version': '2023-06-01',
        accept: body.stream ? 'text/event-stream' : 'application/json',
      },
      body: JSON.stringify(body),
      signal: ctrl.signal,
    });
    // Always drain — the proxy only logs cache stats after the upstream completes.
    const buf = Buffer.from(await res.arrayBuffer());
    return { status: res.status, bytes: buf.length, head: buf.subarray(0, 200).toString('utf8') };
  } finally {
    clearTimeout(timer);
  }
}

const files = readdirSync(DIR)
  .filter((f) => /^turn-\d+\.json$/.test(f))
  .sort();
if (!files.length) {
  console.error(`no turn-NNN.json files in ${DIR}`);
  process.exit(1);
}

console.log(`replay ${files.length} turns from ${DIR} → :${PORT} tag=${JSON.stringify(TAG)}`);
let failed = 0;
for (let i = 0; i < files.length; i++) {
  const f = files[i];
  const raw = JSON.parse(readFileSync(join(DIR, f), 'utf8'));
  const body = saltBody(raw, TAG);
  const t0 = Date.now();
  try {
    const { status, bytes, head } = await postTurn(body);
    const ok = status >= 200 && status < 300;
    if (!ok) failed++;
    console.log(
      `  ${String(i + 1).padStart(2)}/${files.length} ${f}  status=${status}  ${bytes}b  ${Date.now() - t0}ms`
      + (ok ? '' : `  ERR ${head.replace(/\s+/g, ' ').slice(0, 120)}`),
    );
  } catch (err) {
    failed++;
    console.log(`  ${String(i + 1).padStart(2)}/${files.length} ${f}  FAIL ${err?.message || err}`);
  }
}
if (failed) {
  console.error(`replay finished with ${failed} failure(s)`);
  process.exit(1);
}
console.log('replay ok');
