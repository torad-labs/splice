#!/usr/bin/env node
// ─────────────────────────────────────────────────────────────────────────────
// Does replaying encrypted reasoning bust the prompt cache on the ChatGPT/Codex
// Responses backend? A controlled A/B.
//
// Hypothesis (from live traces on gpt-5.x): the backend keeps prior encrypted
// reasoning items when they are useful for the turn and drops them when they are
// not; that conditional keep/drop destabilizes the cacheable prefix. Concretely:
// NOT sending reasoning back keeps the prefix stable and the cache warm, so a
// long agent session bills far fewer *uncached* input tokens (the thing that
// drains the 5-hour / weekly quota).
//
// This runs ONE fixed, context-building conversation TWICE. Same user text, same
// assistant text (captured once), same request params. The ONLY difference:
//   Pass A (replay ON)  — prior encrypted reasoning items ARE in the input.
//   Pass B (replay OFF) — prior encrypted reasoning items are DROPPED.
// It reports cached vs uncached input tokens per turn for each pass.
//
// It hits the real backend and spends a little of your ChatGPT quota (~2N calls).
// Point it at api.openai.com with an API key for third-party reproduction (see
// README). No dependencies beyond undici (already in the repo).
// ─────────────────────────────────────────────────────────────────────────────
import { readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { fetch as undiciFetch } from 'undici';

const MODEL = process.env.CACHE_EXP_MODEL || 'gpt-5.6-sol';
const EFFORT = process.env.CACHE_EXP_EFFORT || 'medium';
const BASE = (process.env.CACHE_EXP_BASE || 'https://chatgpt.com/backend-api/codex').replace(/\/$/, '');
const AUTH_PATH = process.env.CODEX_AUTH_PATH || join(homedir(), '.codex', 'auth.json');
const API_KEY = process.env.OPENAI_API_KEY || null; // set to hit api.openai.com instead of the subscription backend

function loadOauth() {
  const a = JSON.parse(readFileSync(AUTH_PATH, 'utf8'));
  const token = a?.tokens?.access_token;
  if (!token) throw new Error(`no access_token in ${AUTH_PATH}`);
  return { token, accountId: a?.tokens?.account_id ?? null };
}
const oauth = API_KEY ? null : loadOauth();

// A fixed, deterministic, context-building conversation. Each turn references the
// prior ones so the cacheable prefix grows across turns.
const TURNS = [
  'I am building a rate limiter in Node.js. Sketch a token-bucket implementation with a background refill timer. One class, no dependencies.',
  'Now make it burst-tolerant with a maxTokens cap, and explain the tradeoff between burst size and steady-state rate.',
  'Add per-key buckets stored in a Map, plus a sweep that evicts keys idle for more than 5 minutes so the Map does not grow unbounded.',
  'Write a Jest test that proves the refill math over 3 simulated seconds using fake timers, with no real waiting.',
  'Refactor to drop the setInterval entirely and compute available tokens lazily on each take() call. Explain why lazy refill is better for a serverless deployment.',
  'Summarize the final design in one short paragraph and list every edge case we handled.',
];

const INSTRUCTIONS = 'You are a precise senior Node.js engineer. Answer with concise, correct code and short explanations.';

async function callResponses(input, cacheKey) {
  const body = {
    model: MODEL,
    input,
    store: false,
    stream: true,
    instructions: INSTRUCTIONS,
    reasoning: { effort: EFFORT, summary: 'auto' },
    prompt_cache_key: cacheKey,
    // Constant in BOTH passes, so the request param is identical. The variable is
    // purely whether the returned reasoning items are echoed back into `input`.
    include: ['reasoning.encrypted_content'],
  };
  const headers = { 'Content-Type': 'application/json', Accept: 'text/event-stream' };
  if (API_KEY) headers.Authorization = `Bearer ${API_KEY}`;
  else {
    headers.Authorization = `Bearer ${oauth.token}`;
    if (oauth.accountId) headers['ChatGPT-Account-ID'] = oauth.accountId;
  }

  const res = await undiciFetch(`${BASE}/responses`, { method: 'POST', headers, body: JSON.stringify(body) });
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${(await res.text()).slice(0, 500)}`);

  // The ChatGPT/Codex backend streams output items incrementally via
  // response.output_item.done (reasoning items carry encrypted_content; message
  // items carry assistant text). response.completed carries usage but does NOT
  // repeat the full output array — so collect items from the stream, not from the
  // terminal event. (THE BUG: relying on response.completed.output left the golden
  // transcript empty, so history never grew and every turn stayed sub-1024 → the
  // prompt cache never engaged and both passes read 0%.)
  let usage = null;
  let finalResponse = null;
  const output = [];
  const dec = new TextDecoder();
  let buf = '';
  for await (const chunk of res.body) {
    buf += dec.decode(chunk, { stream: true });
    let idx;
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const block = buf.slice(0, idx); buf = buf.slice(idx + 2);
      const line = block.split('\n').find((l) => l.startsWith('data:'));
      if (!line) continue;
      const data = line.slice(5).trim();
      if (!data || data === '[DONE]') continue;
      let ev; try { ev = JSON.parse(data); } catch { continue; }
      if (ev.type === 'response.output_item.done' && ev.item) output.push(ev.item);
      if (ev.type === 'response.completed' || ev.type === 'response.done') finalResponse = ev.response ?? finalResponse;
      if (!usage && ev.response?.usage) usage = ev.response.usage;
    }
  }
  if (!usage) throw new Error('no terminal response with usage found in the SSE stream');
  // Prefer streamed items; fall back to a terminal-event output array if present.
  return { usage, output: output.length ? output : (finalResponse?.output ?? []) };
}

function usageRow(turn, resp) {
  const u = resp.usage;
  const input = u.input_tokens ?? u.prompt_tokens ?? 0;
  const cached = u.input_tokens_details?.cached_tokens ?? u.prompt_tokens_details?.cached_tokens ?? 0;
  return { turn, input, cached, cachedPct: input ? Math.round((cached / input) * 100) : 0, output: u.output_tokens ?? 0 };
}

// Echo back only the fields the Responses input validator needs.
function toInputItem(item) {
  if (item.type === 'reasoning') {
    return { type: 'reasoning', id: item.id, encrypted_content: item.encrypted_content, summary: item.summary ?? [] };
  }
  if (item.type === 'message') {
    return { type: 'message', role: 'assistant', content: (item.content ?? []).map((c) => ({ type: 'output_text', text: c.text ?? '' })) };
  }
  return item;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function passRun(label, includeReasoning, cacheKey, providedGolden) {
  console.log(`\n== ${label} ==`);
  const rows = [];
  const history = [];
  // Pass A: providedGolden=null → capture the real output into a fresh accumulator.
  // Pass B: providedGolden=Pass-A's transcript → replay it verbatim (minus reasoning).
  const capturing = !providedGolden;
  const golden = providedGolden ?? [];
  for (let i = 0; i < TURNS.length; i++) {
    history.push({ role: 'user', content: TURNS[i] });
    const resp = await callResponses(history, cacheKey);
    const row = usageRow(i + 1, resp);
    rows.push(row);
    console.log(`  turn ${row.turn}: input=${row.input}  cached=${row.cached} (${row.cachedPct}%)  output=${row.output}`);
    if (capturing) golden[i] = (resp.output ?? []).map(toInputItem); // Pass A: freeze this turn's transcript
    const items = golden[i] ?? [];
    // The ONLY variable across passes: whether the encrypted reasoning items ride
    // back into the next turn's input. Same user text, same assistant text either way.
    history.push(...items.filter((it) => includeReasoning || it.type !== 'reasoning'));
    await sleep(300);
  }
  return { rows, golden };
}

async function main() {
  console.log(`cache-replay A/B  model=${MODEL}  effort=${EFFORT}  backend=${API_KEY ? 'api.openai.com (key)' : BASE}`);
  const salt = Math.random().toString(36).slice(2, 8); // fresh, non-colliding cache shards per invocation

  // Pass A first: it captures the golden transcript (reasoning + assistant text)
  // that Pass B replays verbatim, minus the reasoning items.
  const a = await passRun('Pass A — replay ON (encrypted reasoning echoed into input)', true, `cacherepl-on-${salt}`, null);
  const b = await passRun('Pass B — replay OFF (same text, reasoning items dropped)', false, `cacherepl-off-${salt}`, a.golden);

  console.log('\n== per-turn cache hit% (share of input served from cache) ==');
  console.log('turn |  replay-ON        |  replay-OFF');
  let uncachedOn = 0, uncachedOff = 0;
  let coldOn = 0, coldOff = 0, hitOnSum = 0, hitOffSum = 0, warmTurns = 0;
  for (let i = 0; i < TURNS.length; i++) {
    const ra = a.rows[i], rb = b.rows[i];
    uncachedOn += ra.input - ra.cached;
    uncachedOff += rb.input - rb.cached;
    // Turn 1 has no prior prefix to cache; only turns >=2 actually test warmth.
    if (i >= 1) {
      warmTurns++;
      hitOnSum += ra.cachedPct; hitOffSum += rb.cachedPct;
      if (ra.input > 1024 && ra.cachedPct < 20) coldOn++;   // over the 1024 floor yet stone cold = a real miss
      if (rb.input > 1024 && rb.cachedPct < 20) coldOff++;
    }
    console.log(`  ${String(ra.turn).padStart(2)} |  ${String(ra.cachedPct).padStart(3)}% (${String(ra.cached).padStart(6)}) | ${String(rb.cachedPct).padStart(3)}% (${String(rb.cached).padStart(6)})`);
  }
  const avgOn = warmTurns ? Math.round(hitOnSum / warmTurns) : 0;
  const avgOff = warmTurns ? Math.round(hitOffSum / warmTurns) : 0;

  // PRIMARY signal: cache warmth on turns 2..N. Same user + assistant text in both
  // passes; the ONLY difference is whether encrypted reasoning rides in the input.
  // A lower hit% / more cold turns under replay ON = replay destabilized the prefix.
  console.log(`\n== cache warmth on turns 2..${TURNS.length} (the controlled comparison) ==`);
  console.log(`  replay ON  : avg hit ${avgOn}%   COLD turns (>1024 tok, <20% hit) = ${coldOn}/${warmTurns}`);
  console.log(`  replay OFF : avg hit ${avgOff}%   COLD turns (>1024 tok, <20% hit) = ${coldOff}/${warmTurns}`);

  // SECONDARY: real-world uncached token cost. CONFOUND: replay ON also SHIPS the
  // encrypted reasoning as extra input, so part of any gap here is volume, not a
  // cache miss. Read it alongside the hit% above, never instead of it.
  console.log(`\ntotal uncached input tokens (secondary; replay ON also ships the reasoning blobs):`);
  console.log(`  replay ON  = ${uncachedOn}`);
  console.log(`  replay OFF = ${uncachedOff}`);
  const ratio = uncachedOff > 0 ? (uncachedOn / uncachedOff) : 0;

  const bustsCache = coldOn > coldOff || avgOn + 5 < avgOff; // directional; ONE run is noisy
  console.log(bustsCache
    ? `\n>> Directional: replay ON ran colder (avg ${avgOn}% vs ${avgOff}%, ${coldOn} vs ${coldOff} cold turns) — consistent with replay destabilizing the cacheable prefix. One run is noisy; re-run several times before trusting it.`
    : `\n>> Directional: no cache penalty from replay this run (avg ${avgOn}% vs ${avgOff}%, ${coldOn} vs ${coldOff} cold). Not reproduced here; re-run, and try the tool-use variant where the backend's keep/drop of reasoning is less predictable.`);

  const outFile = join(process.cwd(), `trace-${Date.now()}.json`);
  writeFileSync(outFile, JSON.stringify({
    model: MODEL, effort: EFFORT, backend: API_KEY ? 'api.openai.com' : BASE, at: new Date().toISOString(),
    turns: TURNS, replayOn: a.rows, replayOff: b.rows,
    avgHitOn: avgOn, avgHitOff: avgOff, coldOn, coldOff, warmTurns, uncachedOn, uncachedOff, ratio,
  }, null, 2));
  console.log(`\nfull machine-readable trace: ${outFile}`);
}

main().catch((e) => { console.error('\nFAILED:', e.message); process.exit(1); });
