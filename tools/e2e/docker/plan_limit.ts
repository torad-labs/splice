#!/usr/bin/env bun
/** The structured half of plan-limit.sh: what the client is told, and what the upstream saw.
 *
 *  probe <port> <out> <log> three turns compared with the mock's recorded native responses:
 *                           FIRST, streamed: HTTP 429 with the native body and rate-limit headers,
 *                           one upstream attempt; SECOND, streamed at once: that same native
 *                           refusal, held locally with no upstream attempt; THIRD, buffered after
 *                           the bounded hold: one new attempt, again relayed. The body and every
 *                           header are verbatim except unified-reset and retry-after, which name
 *                           splice's 30 s recheck (ClientRecheck.kt) when the native one is later.
 *  upstream <log> [max]     the mock's request log as a timeline, and how many turns reached the
 *                           upstream while it was still at its limit. With [max], more than that
 *                           many fails.
 *  resumed <log> <end-ms>   the turn ended after the upstream's reset, i.e. it waited it out.
 *
 *  Contract as lib.ts: evidence on stdout and exit 0, or the reason and exit 1.
 */
import { readFileSync, writeFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

class CheckFailed extends Error {}

/** The longest the head refuses on its own before a turn probes the upstream again: NF-01's clamp,
 *  MAX_RATE_LIMIT_COOLDOWN_MS in integrations/upstream's RateLimitCooldown.kt. */
const HEAD_HOLD_CEILING_MS = 120_000;
/** How soon a limit answer tells the client to ask again: CLIENT_RECHECK_SECONDS in features/turns' ClientRecheck.kt. */
const CLIENT_RECHECK_S = 30;
const UNIFIED_RESET = "anthropic-ratelimit-unified-reset";
const RETRY_AFTER = "retry-after";
function check(condition: unknown, message: string): asserts condition {
  if (!condition) throw new CheckFailed(message);
}

interface Answer {
  status: number;
  headers: Record<string, string>;
  body: string;
  elapsed_ms: number;
  /** When the request was sent (epoch ms): the earliest clock a recheck deadline on the answer can have been written at. */
  started_ms: number;
  /** When the answer was whole (epoch ms): the latest such clock, and the one a reset it carries is judged against. */
  received_ms: number;
}

async function turn(port: string, stream = true): Promise<Answer> {
  const started = Date.now();
  const res = await fetch(`http://127.0.0.1:${port}/v1/messages`, {
    method: "POST",
    headers: { "content-type": "application/json", "x-api-key": "mock-key", "anthropic-version": "2023-06-01" },
    body: JSON.stringify({
      model: "claude-sonnet-5",
      max_tokens: 64,
      stream,
      messages: [{ role: "user", content: "Say hello." }],
    }),
    signal: AbortSignal.timeout(300_000),
  });
  const headers: Record<string, string> = {};
  res.headers.forEach((value, key) => {
    headers[key] = value;
  });
  const body = await res.text();
  const received = Date.now();
  return { status: res.status, headers, body, elapsed_ms: received - started, started_ms: started, received_ms: received };
}

interface NativeReply {
  path: string;
  status: number;
  headers: Record<string, string>;
  body: string;
}

function upstreamTurns(log: string): NativeReply[] {
  return readFileSync(log, "utf8").trim().split("\n").filter(Boolean)
    .map((line) => JSON.parse(line) as NativeReply).filter((row) => row.path === "/v1/messages");
}

function nativeReply(answer: Answer, expected: NativeReply | undefined, label: string): void {
  check(expected !== undefined, `${label}: the mock recorded no upstream reply`);
  check(expected.status === 429 && answer.status === 429, `${label}: native HTTP 429 required, got ${answer.status}`);
  check(answer.body === expected.body, `${label}: the native refusal body changed`);
  const selected = (name: string) => name.startsWith("anthropic-ratelimit-") || name === "retry-after" || name === "x-should-retry";
  const source = Object.entries(expected.headers).filter(([name]) => selected(name));
  check(source.length > 0, `${label}: the mock recorded no rate-limit headers`);
  for (const [name, value] of source) {
    if (name === UNIFIED_RESET) recheckReset(answer, value, label);
    else if (name === RETRY_AFTER) recheckRetryAfter(answer, value, label);
    else check(answer.headers[name] === value, `${label}: ${name} changed: expected '${value}', got '${answer.headers[name]}'`);
  }
  check(Object.keys(answer.headers).filter(selected).length === source.length, `${label}: splice invented rate-limit headers`);
  console.log(`${label} ${answer.status} after ${answer.elapsed_ms} ms; native body and ${source.length} headers relayed, `
    + `retry deadlines at splice's ${CLIENT_RECHECK_S} s recheck`);
  for (const [name, value] of source) {
    const named = answer.headers[name];
    console.log(`  ${name}: ${named}${named === value ? "" : ` (native ${value})`}`);
  }
  console.log(`  ${answer.body}`);
}

/** ClientRecheck.unifiedReset: a native reset later than floor(now) + 30 s is written as that recheck, `now` being when the
 *  head wrote the answer, between the request's start and the answer's arrival. */
function recheckReset(answer: Answer, native: string, label: string): void {
  const named = answer.headers[UNIFIED_RESET];
  const earliest = Math.floor(answer.started_ms / 1000) + CLIENT_RECHECK_S;
  const latest = Math.floor(answer.received_ms / 1000) + CLIENT_RECHECK_S;
  check(named !== undefined && Number(named) >= earliest && Number(named) <= latest,
    `${label}: ${UNIFIED_RESET} should name the ${CLIENT_RECHECK_S} s recheck, between ${earliest} and ${latest}, got '${named}'`);
  check(Number(named) < Number(native), `${label}: ${UNIFIED_RESET} '${named}' is not earlier than the native '${native}'`);
}

/** ClientRecheck.retryAfter: a native wait of more than 30 s (seconds, as the mock sends it) is written as "30"; a shorter one stays. */
function recheckRetryAfter(answer: Answer, native: string, label: string): void {
  const expected = /^\d+$/.test(native) && Number(native) > CLIENT_RECHECK_S ? String(CLIENT_RECHECK_S) : native;
  check(answer.headers[RETRY_AFTER] === expected,
    `${label}: ${RETRY_AFTER} expected '${expected}' for native '${native}', got '${answer.headers[RETRY_AFTER]}'`);
}

const verbs: Record<string, (argv: readonly string[]) => Promise<void> | void> = {
  async probe(argv) {
    const [port, out, log] = argv;
    check(port && out && log, "usage: probe <port> <out> <upstream-log>");
    const first = await turn(port);
    writeFileSync(out, JSON.stringify({ first }, null, 2));
    let attempts = upstreamTurns(log);
    check(attempts.length === 1, `the first native refusal made ${attempts.length} upstream attempts, expected one`);
    nativeReply(first, attempts[0], "FIRST");

    const second = await turn(port);
    writeFileSync(out, JSON.stringify({ first, second }, null, 2));
    attempts = upstreamTurns(log);
    check(attempts.length === 1, "the held follower reached upstream");
    nativeReply(second, attempts[0], "SECOND");

    // The head holds from the upstream's own reset, bounded by its ceiling; the reset on the client's answer names only
    // splice's 30 s recheck, so the wait is scheduled from the native reply the mock recorded.
    const namedReset = Number(attempts[0]?.headers[UNIFIED_RESET] ?? "0") * 1000;
    const holdUntil = Math.min(namedReset, second.received_ms + HEAD_HOLD_CEILING_MS);
    await Bun.sleep(Math.max(0, holdUntil - Date.now()) + 1_000);
    const third = await turn(port, false);
    writeFileSync(out, JSON.stringify({ first, second, third }, null, 2));
    attempts = upstreamTurns(log);
    check(attempts.length === 2, `the buffered re-probe made ${attempts.length - 1} attempts, expected one`);
    nativeReply(third, attempts[1], "THIRD");
    console.log("native 429 contract: one observer attempt, zero follower attempts, one bounded re-probe");
  },

  pooled(argv) {
    const [log, client, transcripts, started, ended, exit] = argv;
    check(log && client && transcripts && started && ended && exit, "usage: pooled <log> <client> <transcripts> <start-ms> <end-ms> <exit>");
    const rows = readFileSync(log, "utf8").trim().split("\n").filter(Boolean)
      .map(line => JSON.parse(line) as Record<string, unknown>).filter(row => row["path"] === "/v1/messages");
    check(rows.length === 2, `pooled request made ${rows.length} attempts, expected one per login`);
    const [first, second] = rows;
    check(first?.["login"] === "one" && first["status"] === 429, "the first login did not meet its native plan limit");
    check(second?.["login"] === "two" && second["status"] === 200, "the same request did not reach the free second login");
    check(Number(first["reset_at_s"]) * 1000 > Number(ended) + 7_100_000, "the first login was not limited two hours out");
    check(typeof first["request_sha256"] === "string" && first["request_sha256"] === second["request_sha256"],
      "handoff changed the prepared request bytes");
    check(exit === "0" && Number(ended) - Number(started) < 25_000, "the real client slept or failed rather than completing");
    const output = readFileSync(client, "utf8");
    check(output.includes("completed on the next login"), "the real client did not receive the second login's answer");
    const files = readdirSync(transcripts).filter(path => path.endsWith(".jsonl"));
    check(files.length > 0, "the real client transcript is absent");
    for (const text of [output, ...files.map(path => readFileSync(join(transcripts, path), "utf8"))]) {
      check(!text.includes('"api_error"') && !text.includes('"retryInMs"') && !text.includes('"rate_limit_error"')
        && !text.includes('"api_retry"') && !text.includes('"retry_delay_ms"'),
        "a rate-limit refusal or retry wait reached the client");
    }
    console.log("pooled request: one then two, identical bytes, completed in seconds, no client rate-limit error PASS");
  },

  upstream(argv) {
    const [log, max] = argv;
    check(log, "usage: upstream <log> [max]");
    const rows = readFileSync(log, "utf8").trim().split("\n").filter(Boolean).map((l) => JSON.parse(l) as Record<string, unknown>);
    const turns = rows.filter((r) => r["path"] === "/v1/messages");
    const [first] = turns;
    check(first !== undefined, "the upstream saw no turn at all");
    const resetMs = Number(first["reset_at_s"]) * 1000;
    const firstMs = Number(first["at_ms"]);
    for (const r of turns) {
      console.log(`  +${((Number(r["at_ms"]) - firstMs) / 1000).toFixed(1)}s ${r["status"]} stream=${r["stream"]}`);
    }
    const limited = turns.filter((r) => Number(r["at_ms"]) < resetMs);
    console.log(`reset at +${((resetMs - firstMs) / 1000).toFixed(1)}s; ${limited.length} of ${turns.length} turns reached the upstream before it`);
    if (max !== undefined) check(limited.length <= Number(max), `${limited.length} turns reached the upstream before its reset, more than ${max}`);
  },

  resumed(argv) {
    const [log, endMs] = argv;
    check(log && endMs, "usage: resumed <log> <end-ms>");
    const turns = readFileSync(log, "utf8").trim().split("\n").filter(Boolean).map((l) => JSON.parse(l) as Record<string, unknown>)
      .filter((r) => r["path"] === "/v1/messages");
    const [first] = turns;
    check(first !== undefined, "the upstream saw no turn at all");
    const resetMs = Number(first["reset_at_s"]) * 1000;
    const served = turns.filter((r) => r["status"] === 200);
    console.log(`reset ${new Date(resetMs).toISOString()}, turn ended ${new Date(Number(endMs)).toISOString()}, ${served.length} answered 200`);
    check(served.length > 0, "the upstream never answered 200, so the client never resumed through it");
    check(Number(endMs) >= resetMs, "the turn ended before the upstream's reset");
  },
};

if (import.meta.main) {
  const [verb = "", ...rest] = process.argv.slice(2);
  const run = verbs[verb];
  if (run === undefined) {
    console.log(`plan_limit.ts: unknown verb '${verb}' (${Object.keys(verbs).join(", ")})`);
    process.exit(2);
  }
  try {
    await run(rest);
  } catch (e) {
    console.log(e instanceof CheckFailed ? e.message : `plan_limit.ts ${verb}: ${String(e)}`);
    process.exit(1);
  }
}
