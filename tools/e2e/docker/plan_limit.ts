#!/usr/bin/env bun
/** The structured half of plan-limit.sh: what the client is told, and what the upstream saw.
 *
 *  probe <port> <out> <log> three turns compared with the mock's recorded native responses:
 *                           FIRST, streamed: HTTP 429 with unchanged body and rate-limit headers,
 *                           one upstream attempt; SECOND, streamed at once: that same native
 *                           refusal, held locally with no upstream attempt; THIRD, buffered after
 *                           the bounded hold: one new attempt, again relayed unchanged.
 *  upstream <log> [max]     the mock's request log as a timeline, and how many turns reached the
 *                           upstream while it was still at its limit. With [max], more than that
 *                           many fails.
 *  resumed <log> <end-ms>   the turn ended after the upstream's reset, i.e. it waited it out.
 *
 *  Contract as lib.ts: evidence on stdout and exit 0, or the reason and exit 1.
 */
import { readFileSync, writeFileSync } from "node:fs";

class CheckFailed extends Error {}

/** The longest the head refuses on its own before a turn probes the upstream again: NF-01's clamp,
 *  MAX_RATE_LIMIT_COOLDOWN_MS in integrations/upstream's RateLimitCooldown.kt. */
const HEAD_HOLD_CEILING_MS = 120_000;
function check(condition: unknown, message: string): asserts condition {
  if (!condition) throw new CheckFailed(message);
}

interface Answer {
  status: number;
  headers: Record<string, string>;
  body: string;
  elapsed_ms: number;
  /** When the answer was whole (epoch ms): the clock a reset it carries is judged against. */
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
  return { status: res.status, headers, body, elapsed_ms: received - started, received_ms: received };
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
    check(answer.headers[name] === value, `${label}: ${name} changed: expected '${value}', got '${answer.headers[name]}'`);
  }
  check(Object.keys(answer.headers).filter(selected).length === source.length, `${label}: splice invented rate-limit headers`);
  console.log(`${label} ${answer.status} after ${answer.elapsed_ms} ms; native body and ${source.length} headers unchanged`);
  for (const [name, value] of source) console.log(`  ${name}: ${value}`);
  console.log(`  ${answer.body}`);
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

    // The native plan reset remains on the wire, but splice's local horizon still ends at its ceiling.
    const namedReset = Number(second.headers["anthropic-ratelimit-unified-reset"] ?? "0") * 1000;
    const holdUntil = Math.min(namedReset, second.received_ms + HEAD_HOLD_CEILING_MS);
    await Bun.sleep(Math.max(0, holdUntil - Date.now()) + 1_000);
    const third = await turn(port, false);
    writeFileSync(out, JSON.stringify({ first, second, third }, null, 2));
    attempts = upstreamTurns(log);
    check(attempts.length === 2, `the buffered re-probe made ${attempts.length - 1} attempts, expected one`);
    nativeReply(third, attempts[1], "THIRD");
    console.log("native 429 contract: one observer attempt, zero follower attempts, one bounded re-probe");
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
