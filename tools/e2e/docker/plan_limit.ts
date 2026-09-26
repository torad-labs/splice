#!/usr/bin/env bun
/** The structured half of plan-limit.sh: what the client is told, and what the upstream saw.
 *
 *  probe <port> <out>       three turns at a head whose upstream is at its plan limit, each recorded
 *                           whole (status, headers, body) in <out> and held to its contract:
 *                           FIRST, streamed: the relabelled in-band error Claude Code retries (a 200
 *                           stream whose first event is `error` / `overloaded_error`, rate-limit
 *                           words kept); SECOND, streamed at once: the head's own refusal, a 429
 *                           with a reset and a Retry-After; THIRD, buffered (stream:false) once
 *                           that hold has passed: the upstream's 429 kept as a 429, which is where
 *                           Claude Code 2.1.x applies its stop test. None may carry anything that
 *                           test reads as "stop waiting" (NO_WAIT_PHRASES, NO_WAIT_HEADERS).
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

/** The phrases and header values that make Claude Code 2.1.x stop instead of retrying a 429 (its
 *  bundle: the credits / extra-usage test, then the overage and exceeded_limit test). */
export const NO_WAIT_PHRASES = [
  "extra usage is required",
  "usage credits are required",
  "credits_required",
  "service_spend_limit_reached",
  "exceeded_limit",
];
export const NO_WAIT_HEADERS = ["anthropic-ratelimit-unified-overage-disabled-reason"];

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

/** The first SSE event of a stream body: its event name and its parsed data. */
function firstEvent(body: string): { event: string; data: Record<string, unknown> } {
  const block = body.split("\n\n").find((b) => b.split("\n").some((l) => l.startsWith("data:")));
  check(block !== undefined, "the stream carried no event at all");
  const event = block.split("\n").find((l) => l.startsWith("event:"))?.slice(6).trim() ?? "";
  const data = block.split("\n").filter((l) => l.startsWith("data:")).map((l) => l.slice(5).trim()).join("");
  return { event, data: JSON.parse(data) as Record<string, unknown> };
}

function noWaitSignals(answer: Answer): string[] {
  const found: string[] = [];
  const text = answer.body.toLowerCase();
  for (const phrase of NO_WAIT_PHRASES) if (text.includes(phrase)) found.push(`body says "${phrase}"`);
  for (const name of NO_WAIT_HEADERS) if (answer.headers[name] !== undefined) found.push(`header ${name}: ${answer.headers[name]}`);
  return found;
}

const verbs: Record<string, (argv: readonly string[]) => Promise<void> | void> = {
  async probe(argv) {
    const [port, out] = argv;
    check(port && out, "usage: probe <port> <out>");
    const first = await turn(port);
    const second = await turn(port);
    // The third turn must reach the upstream, so it waits out the hold the second one was refused on.
    // V4-233: a spent PLAN window's refusal names the plan's own reset, an hour out on this mock, but
    // the head lifts its own refusal at NF-01's clamp and the next turn probes the upstream (V4-47),
    // so the wait is whichever comes first.
    const namedReset = Number(second.headers["anthropic-ratelimit-unified-reset"] ?? "0") * 1000;
    const holdUntil = Math.min(namedReset, second.received_ms + HEAD_HOLD_CEILING_MS);
    await Bun.sleep(Math.max(0, holdUntil - Date.now()) + 1_000);
    const third = await turn(port, false);
    writeFileSync(out, JSON.stringify({ first, second, third }, null, 2));

    console.log(`FIRST  ${first.status} ${first.headers["content-type"] ?? ""} after ${first.elapsed_ms} ms`);
    console.log(first.body.slice(0, 1200));
    check(first.status === 200, `the first turn must be answered in-band (200 stream), got ${first.status}`);
    const { event, data } = firstEvent(first.body);
    const error = (data["error"] ?? {}) as Record<string, unknown>;
    check(event === "error", `the first event must be the error, got '${event}'`);
    check(error["type"] === "overloaded_error", `the in-band error must be overloaded_error, got '${String(error["type"])}'`);
    const message = String(error["message"] ?? "");
    check(/rate.?limit/i.test(message), `the relabelled error must keep the rate-limit words: '${message}'`);

    console.log(`SECOND ${second.status} after ${second.elapsed_ms} ms`);
    for (const [k, v] of Object.entries(second.headers)) if (/ratelimit|retry-after/i.test(k)) console.log(`  ${k}: ${v}`);
    console.log(`  ${second.body.slice(0, 600)}`);
    check(second.status === 429, `the retry must meet the head's own 429, got ${second.status}`);
    check(second.headers["anthropic-ratelimit-unified-status"] === "rejected", "the refusal must say rejected");
    const reset = Number(second.headers["anthropic-ratelimit-unified-reset"]);
    // Judged at the refusal, not now: the third turn has already slept past this reset.
    check(reset * 1000 > second.received_ms, `the refusal's reset must be after the refusal: ${reset}`);
    check(second.headers["retry-after"] !== undefined, "the refusal must carry Retry-After");

    console.log(`THIRD  ${third.status} ${third.headers["content-type"] ?? ""} after ${third.elapsed_ms} ms (buffered)`);
    for (const [k, v] of Object.entries(third.headers)) if (/ratelimit|retry-after/i.test(k)) console.log(`  ${k}: ${v}`);
    console.log(`  ${third.body.slice(0, 600)}`);
    check(third.status === 429, `a buffered turn keeps the upstream's 429, got ${third.status}`);
    check(third.headers["anthropic-ratelimit-unified-status"] !== "allowed", "a 429 must not say the quota is allowed");

    const leaks = [
      ...noWaitSignals(first).map((s) => `first: ${s}`),
      ...noWaitSignals(second).map((s) => `second: ${s}`),
      ...noWaitSignals(third).map((s) => `third: ${s}`),
    ];
    check(leaks.length === 0, `a no-wait signal reached the client: ${leaks.join("; ")}`);
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
