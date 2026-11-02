#!/usr/bin/env bun
/** The structured half of plan-limit.sh: what the client is told, and what the upstream saw.
 *
 *  probe <port> <out> <log> three turns compared with the mock's recorded native responses: FIRST
 *                           and SECOND streamed, THIRD buffered, each sent to the upstream once and
 *                           refused with HTTP 429, with no proxy retries. A turn waits only on its own
 *                           failure (2026-10-26, Marcos's call): the next turn on a limited login is
 *                           still sent to the provider, so a lifted limit is noticed at once and one
 *                           that holds reaches the client the same way the first turn's did. Each
 *                           answer is the hand-over of failure-handling slice 5 (2026-10-27,
 *                           final-design.md sections 9 and 10):
 *                           the native envelope and error type; splice's own line (HoldSentences.kt)
 *                           naming the head, the window it met and the native reset as a clock time;
 *                           and exactly the headers of HandOverHeaders.kt: retry-after, the unified
 *                           status, the unified reset and the per-window reset with the native value.
 *                           Retry-After and the unified reset name one instant: the native reset with
 *                           one login, splice's 30 s recheck where another login could serve the head.
 *                           The mock's per-bucket and utilization headers are not relayed.
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

/** How soon a handed-over limit tells the client to ask again where another login could serve the head: RECHECK_SECONDS in
 *  core's ClientWire.kt, written by features/turns' HandOverHeaders.kt. */
const CLIENT_RECHECK_S = 30;
const UNIFIED_RESET = "anthropic-ratelimit-unified-reset";
const UNIFIED_STATUS = "anthropic-ratelimit-unified-status";
const FIVE_HOUR_RESET = "anthropic-ratelimit-unified-5h-reset";
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

interface Envelope {
  type?: string;
  error?: { type?: string; message?: string };
}

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** An epoch second as a person reads it in a hold line: LocalTimeText's "MMM d, h:mm a z" in the machine's zone, which is
 *  UTC in the e2e image ("Nov 2, 10:28 PM UTC"). Spelled by hand so a locale's spacing cannot differ from the JDK's. */
function clockTime(epochSeconds: number): string {
  const at = new Date(epochSeconds * 1000);
  const hours = at.getUTCHours();
  const clock = `${hours % 12 === 0 ? 12 : hours % 12}:${String(at.getUTCMinutes()).padStart(2, "0")} ${hours < 12 ? "AM" : "PM"}`;
  return `${MONTHS[at.getUTCMonth()]} ${at.getUTCDate()}, ${clock} UTC`;
}

/** The refusal body as Claude Code reads it: the native envelope and error type, so the client handles the 429 it is, and
 *  in place of the upstream's sentence splice's line, which says the head met a limit and when it resets, in the
 *  clock time the native reset header names. */
function heldLine(answer: Answer, expected: NativeReply, label: string): void {
  const native = JSON.parse(expected.body) as Envelope;
  let got: Envelope;
  try {
    got = JSON.parse(answer.body) as Envelope;
  } catch {
    throw new CheckFailed(`${label}: the refusal body is not JSON: ${answer.body}`);
  }
  check(got.type === native.type && got.error?.type === native.error?.type,
    `${label}: the refusal's type changed: native ${native.type}/${native.error?.type}, got ${got.type}/${got.error?.type}`);
  const line = got.error?.message ?? "";
  check(line.startsWith("[splice] "), `${label}: the message is not splice's line: ${line}`);
  check(/\blimit\b/.test(line), `${label}: the line does not say the head met a limit: ${line}`);
  const reset = expected.headers[UNIFIED_RESET];
  if (reset !== undefined) {
    const spelled = clockTime(Number(reset));
    check(line.includes(spelled), `${label}: the line does not name the native reset ${spelled}: ${line}`);
  }
}

function nativeReply(answer: Answer, expected: NativeReply | undefined, label: string): void {
  check(expected !== undefined, `${label}: the mock recorded no upstream reply`);
  check(expected.status === 429 && answer.status === 429, `${label}: native HTTP 429 required, got ${answer.status}`);
  heldLine(answer, expected, label);
  handOverHeaders(answer, expected, label);
  console.log(`${label} ${answer.status} after ${answer.elapsed_ms} ms; native envelope, splice's line and the hand-over headers`);
  for (const name of limitHeaders(answer.headers)) {
    const native = expected.headers[name];
    console.log(`  ${name}: ${answer.headers[name]}${answer.headers[name] === native ? "" : ` (native ${native ?? "none"})`}`);
  }
  console.log(`  ${answer.body}`);
}

/** The rate-limit headers of an answer, the ones Claude Code reads a limit from. */
function limitHeaders(headers: Record<string, string>): string[] {
  return Object.keys(headers).filter((name) => name.startsWith("anthropic-ratelimit-") || name === RETRY_AFTER).sort();
}

/** HandOverHeaders.kt: exactly retry-after, the unified status "rejected", the unified reset and the five-hour member (the
 *  window the mock's limit is), which carries the native reset. Retry-After counts to the unified reset from when the head
 *  answered; that reset is the native one with one login, or splice's 30 s recheck where another login could serve. */
function handOverHeaders(answer: Answer, expected: NativeReply, label: string): void {
  const nativeReset = expected.headers[UNIFIED_RESET];
  const nativeFiveHour = expected.headers[FIVE_HOUR_RESET];
  check(nativeReset !== undefined && nativeFiveHour !== undefined, `${label}: the mock recorded no unified reset headers`);
  check(answer.headers[UNIFIED_STATUS] === "rejected",
    `${label}: ${UNIFIED_STATUS} expected 'rejected', got '${answer.headers[UNIFIED_STATUS]}'`);
  check(answer.headers[FIVE_HOUR_RESET] === nativeFiveHour,
    `${label}: ${FIVE_HOUR_RESET} should carry the native reset '${nativeFiveHour}', got '${answer.headers[FIVE_HOUR_RESET]}'`);
  const told = answer.headers[UNIFIED_RESET];
  const recheckEarliest = Math.floor(answer.started_ms / 1000) + CLIENT_RECHECK_S;
  const recheckLatest = Math.floor(answer.received_ms / 1000) + CLIENT_RECHECK_S;
  check(told !== undefined && /^\d+$/.test(told)
    && (told === nativeReset || (Number(told) >= recheckEarliest && Number(told) <= recheckLatest)),
    `${label}: ${UNIFIED_RESET} should be the native reset '${nativeReset}' or the ${CLIENT_RECHECK_S} s recheck `
    + `(${recheckEarliest} to ${recheckLatest}), got '${told}'`);
  const retryAfter = answer.headers[RETRY_AFTER];
  const earliest = Number(told) - Math.ceil(answer.received_ms / 1000) - 1;
  const latest = Number(told) - Math.floor(answer.started_ms / 1000) + 1;
  check(retryAfter !== undefined && /^\d+$/.test(retryAfter) && Number(retryAfter) >= earliest && Number(retryAfter) <= latest,
    `${label}: ${RETRY_AFTER} should count to ${UNIFIED_RESET} ${told} (${earliest} to ${latest} s), got '${retryAfter}'`);
  const carried = limitHeaders(answer.headers);
  const handOver = [FIVE_HOUR_RESET, RETRY_AFTER, UNIFIED_RESET, UNIFIED_STATUS].sort();
  check(JSON.stringify(carried) === JSON.stringify(handOver),
    `${label}: the hand-over should carry exactly ${handOver.join(", ")}, got ${carried.join(", ")}`);
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

    // The next turn on the limited login is sent to the provider too: it waits on its own failure, not on the first turn's.
    const second = await turn(port);
    writeFileSync(out, JSON.stringify({ first, second }, null, 2));
    attempts = upstreamTurns(log);
    check(attempts.length === 2, `the second turn made ${attempts.length - 1} upstream attempts, expected one of its own`);
    nativeReply(second, attempts[1], "SECOND");

    const third = await turn(port, false);
    writeFileSync(out, JSON.stringify({ first, second, third }, null, 2));
    attempts = upstreamTurns(log);
    check(attempts.length === 3, `the buffered turn made ${attempts.length - 2} upstream attempts, expected one of its own`);
    nativeReply(third, attempts[2], "THIRD");
    console.log("native 429 contract: one upstream attempt per turn, no proxy retries, each handed over with the native reset");
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
