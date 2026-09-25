#!/usr/bin/env bun
/** An Anthropic Messages upstream at its plan limit until a reset, then open: the plan-limit e2e.
 *
 *  Before the reset, every POST /v1/messages gets what a subscription at its five-hour limit gets:
 *  429, `rate_limit_error`, and the unified rate-limit headers Claude Code reads. The header NAMES
 *  are the ones in the Claude Code bundle. The VALUES follow the quotaLimits object Claude Code
 *  records for a real plan-limit 429 (status rejected, rateLimitType five_hour, overageStatus
 *  rejected, overageDisabledReason out_of_credits, lowPriorityOffer control, 20 s, 1200 s; the slow-*
 *  mapping of those last three is inferred from the names). The body TEXT is not from a capture,
 *  because none exists: it is a neutral sentence a run may replace (MOCK_ANTHROPIC_MESSAGE).
 *  From the reset on, the same request gets 200: a streamed message whose text is RESUMED_TEXT, or
 *  the JSON message when the request is not streamed.
 *
 *  The window opens at the FIRST /v1/messages request, not at launch, so the time a client sleeps
 *  does not depend on how long the daemon took to boot. Every request is one JSON line in
 *  MOCK_ANTHROPIC_LOG: when, which path, streamed or not, and the status it got.
 *
 *  Usage: mock_anthropic.ts <port>  — prints {"port": N, "log": path} once listening, then serves.
 *  Env: MOCK_ANTHROPIC_LOG (required), MOCK_ANTHROPIC_RESET_S (default 90),
 *       MOCK_ANTHROPIC_RETRY_AFTER (seconds; unset sends no retry-after), MOCK_ANTHROPIC_MESSAGE.
 */
import { appendFileSync } from "node:fs";

export const RESUMED_TEXT = "resumed after the reset";
const RESET_S = Number(process.env["MOCK_ANTHROPIC_RESET_S"] ?? "90");
const RETRY_AFTER = process.env["MOCK_ANTHROPIC_RETRY_AFTER"];
const MESSAGE = process.env["MOCK_ANTHROPIC_MESSAGE"] ?? "Rate limited: this account has reached its usage limit.";
const LOG = process.env["MOCK_ANTHROPIC_LOG"];
if (!LOG) throw new Error("mock_anthropic.ts: MOCK_ANTHROPIC_LOG is required");

let firstAt: number | null = null;
let resetAtS = 0;

function planLimitHeaders(): Record<string, string> {
  const headers: Record<string, string> = {
    "content-type": "application/json",
    "request-id": "req_011CMockPlanLimit",
    "anthropic-ratelimit-unified-status": "rejected",
    "anthropic-ratelimit-unified-reset": String(resetAtS),
    "anthropic-ratelimit-unified-representative-claim": "five_hour",
    "anthropic-ratelimit-unified-5h-utilization": "1.0",
    "anthropic-ratelimit-unified-5h-reset": String(resetAtS),
    "anthropic-ratelimit-unified-7d-utilization": "0.15",
    "anthropic-ratelimit-unified-7d-reset": String(resetAtS + 5 * 86_400),
    "anthropic-ratelimit-unified-overage-status": "rejected",
    "anthropic-ratelimit-unified-overage-disabled-reason": "out_of_credits",
    "anthropic-ratelimit-unified-slow-offer": "control",
    "anthropic-ratelimit-unified-slow-retry-after": "20",
    "anthropic-ratelimit-unified-slow-max-wait": "1200",
  };
  if (RETRY_AFTER !== undefined) headers["retry-after"] = RETRY_AFTER;
  return headers;
}

function openHeaders(contentType: string): Record<string, string> {
  return {
    "content-type": contentType,
    "request-id": "req_011CMockResumed",
    "anthropic-ratelimit-unified-status": "allowed",
    "anthropic-ratelimit-unified-representative-claim": "five_hour",
    "anthropic-ratelimit-unified-5h-utilization": "0.01",
    "anthropic-ratelimit-unified-5h-reset": String(resetAtS + 5 * 3_600),
    "anthropic-ratelimit-unified-7d-utilization": "0.15",
    "anthropic-ratelimit-unified-7d-reset": String(resetAtS + 5 * 86_400),
  };
}

function sse(model: string): string {
  const events: [string, unknown][] = [
    ["message_start", {
      type: "message_start",
      message: {
        id: "msg_mock_resumed", type: "message", role: "assistant", model, content: [],
        stop_reason: null, stop_sequence: null, usage: { input_tokens: 12, output_tokens: 1 },
      },
    }],
    ["content_block_start", { type: "content_block_start", index: 0, content_block: { type: "text", text: "" } }],
    ["content_block_delta", { type: "content_block_delta", index: 0, delta: { type: "text_delta", text: RESUMED_TEXT } }],
    ["content_block_stop", { type: "content_block_stop", index: 0 }],
    ["message_delta", {
      type: "message_delta", delta: { stop_reason: "end_turn", stop_sequence: null }, usage: { output_tokens: 6 },
    }],
    ["message_stop", { type: "message_stop" }],
  ];
  return events.map(([event, data]) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`).join("");
}

function message(model: string): unknown {
  return {
    id: "msg_mock_resumed", type: "message", role: "assistant", model,
    content: [{ type: "text", text: RESUMED_TEXT }],
    stop_reason: "end_turn", stop_sequence: null, usage: { input_tokens: 12, output_tokens: 6 },
  };
}

function record(entry: Record<string, unknown>): void {
  appendFileSync(LOG!, JSON.stringify({ at_ms: Date.now(), ...entry }) + "\n");
}

const server = Bun.serve({
  hostname: "127.0.0.1",
  port: Number(process.argv[2] ?? "0"),
  async fetch(req) {
    const path = new URL(req.url).pathname;
    if (req.method === "POST" && path === "/v1/messages/count_tokens") {
      record({ path, status: 200 });
      return Response.json({ input_tokens: 12 });
    }
    if (req.method === "GET" && path === "/v1/models") {
      record({ path, status: 200 });
      return Response.json({ data: [{ type: "model", id: "claude-sonnet-5", display_name: "Claude Sonnet 5" }], has_more: false });
    }
    if (req.method !== "POST" || path !== "/v1/messages") {
      record({ path, method: req.method, status: 404 });
      return new Response("not found", { status: 404 });
    }
    const body = (await req.json().catch(() => ({}))) as { stream?: boolean; model?: string };
    const model = body.model ?? "claude-sonnet-5";
    const now = Date.now();
    if (firstAt === null) {
      firstAt = now;
      resetAtS = Math.ceil(now / 1000) + RESET_S;
    }
    const limited = now < resetAtS * 1000;
    record({ path, stream: body.stream === true, status: limited ? 429 : 200, since_first_ms: now - firstAt, reset_at_s: resetAtS });
    if (limited) {
      return new Response(
        JSON.stringify({ type: "error", error: { type: "rate_limit_error", message: MESSAGE }, request_id: "req_011CMockPlanLimit" }),
        { status: 429, headers: planLimitHeaders() },
      );
    }
    return body.stream === true
      ? new Response(sse(model), { status: 200, headers: openHeaders("text/event-stream") })
      : new Response(JSON.stringify(message(model)), { status: 200, headers: openHeaders("application/json") });
  },
});

console.log(JSON.stringify({ port: server.port, log: LOG }));
