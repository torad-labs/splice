#!/usr/bin/env bun
/** An OpenAI-compatible chat-completions upstream for the fresh-machine e2e.
 *
 *  Serves exactly what the openai-chat dialect needs from a vendor: a streaming
 *  /chat/completions that emits role + content deltas, a finish_reason, a usage
 *  chunk and [DONE]; a non-stream /chat/completions; and /models. Deterministic
 *  content so the wire probe's assertions are about the PROXY, never the model.
 *
 *  Scenarios ride in the request text as `SCENARIO:<name>` (any message, any
 *  position), so the harness selects them from a plain `claude -p` prompt:
 *    hold        sleep MOCK_CHAT_HOLD_S (default 30) before answering, which keeps
 *                a session registered while another head lists its peers
 *    listagents  if the request offers a `ListAgents` tool, answer with one call
 *                to it; once the tool result comes back (role "tool" messages),
 *                answer "PEERS: <tool result>" so the caller prints what the
 *                tool actually returned inside its head
 *  Anything else answers the fixed reply. The server serves requests CONCURRENTLY:
 *  a `hold` must not block the other head's turn.
 *
 *  Usage: mock_chat.ts <port>   — prints {"port": N} once listening, then serves.
 *
 *  V4-145: converted to TypeScript (bun). The artifact here is the SERVED RESPONSE, not stdout, so
 *  that is what the differential compares: status, content-type and BODY BYTES, against the Python
 *  server driven over the same request matrix. The bodies go through checks/e2e/pyjson.ts because
 *  Python's json.dumps writes `{"a": 1, "b": 2}` and JSON.stringify writes `{"a":1,"b":2}` — the
 *  proxy under test would not care, but a byte-differential would, and matching costs one import.
 *  The `hold` scenario awaits a timer rather than blocking, which is what keeps the threading
 *  property the docstring requires: Bun.serve serves concurrently, so one held turn does not stop
 *  another head listing its peers.
 */
import { loads, dumps, obj, num, objGet, isPyObj, type PyValue, type PyObj } from "../pyjson.ts";

const REPLY_WORDS = ["Hello", " from", " the", " chat", " mock", ".", " 1,", " 2,", " 3", " END"];
const MODELS = ["mock-chat", "mock-chat-2", "mock-chat-2-big"];
const USAGE = obj([
  ["prompt_tokens", num("12")],
  ["completion_tokens", num("10")],
  ["total_tokens", num("22")],
]);
const HOLD_SECONDS = Number(process.env["MOCK_CHAT_HOLD_S"] ?? "30");
const SCENARIO = /SCENARIO:([a-z_]+)/;

function scenario(raw: string): string {
  const m = SCENARIO.exec(raw);
  return m === null ? "basic" : m[1];
}

function toolText(message: PyObj): string {
  const content = objGet(message, "content");
  if (Array.isArray(content)) {
    return content
      .map((p) => (isPyObj(p) ? str(objGet(p, "text")) : str(p)))
      .join("");
  }
  return str(content);
}

function str(v: PyValue): string {
  if (v === null) return "None";
  if (typeof v === "string") return v;
  if (typeof v === "boolean") return v ? "True" : "False";
  if (typeof v === "number") return String(v);
  return dumps(v);
}

function jsonResponse(status: number, body: PyValue): Response {
  const bytes = new TextEncoder().encode(dumps(body));
  return new Response(bytes, {
    status,
    headers: { "Content-Type": "application/json", "Content-Length": String(bytes.length) },
  });
}

function completion(model: string, message: PyValue, finish: string): PyObj {
  return obj([
    ["id", "chatcmpl-mock"],
    ["object", "chat.completion"],
    ["model", model],
    ["choices", [obj([["index", num("0")], ["message", message], ["finish_reason", finish]])]],
    ["usage", USAGE],
  ]);
}

function chunk(model: string, delta: PyValue, finish: string | null, usage: PyValue | null): string {
  const fields: [string, PyValue][] = [
    ["id", "chatcmpl-mock"],
    ["object", "chat.completion.chunk"],
    ["model", model],
    ["choices", [obj([["index", num("0")], ["delta", delta], ["finish_reason", finish]])]],
  ];
  if (usage !== null) fields.push(["usage", usage]);
  return `data: ${dumps(obj(fields))}\n\n`;
}

function sseStream(pieces: string[]): Response {
  const encoder = new TextEncoder();
  const stream = new ReadableStream({
    start(controller) {
      for (const p of pieces) controller.enqueue(encoder.encode(p));
      controller.close();
    },
  });
  // The Python handler sets Content-Type and Cache-Control on the SSE head and no Content-Length.
  return new Response(stream, {
    status: 200,
    headers: { "Content-Type": "text/event-stream", "Cache-Control": "no-cache" },
  });
}

function reply(model: string, stream: boolean, words: string[]): Response {
  if (!stream) {
    return jsonResponse(
      200,
      completion(model, obj([["role", "assistant"], ["content", words.join("")]]), "stop"),
    );
  }
  const pieces = [chunk(model, obj([["role", "assistant"], ["content", ""]]), null, null)];
  for (const word of words) pieces.push(chunk(model, obj([["content", word]]), null, null));
  pieces.push(chunk(model, obj([]), "stop", USAGE));
  pieces.push("data: [DONE]\n\n");
  return sseStream(pieces);
}

function toolCall(model: string, stream: boolean, callId: string, name: string, args: string): Response {
  const call = obj([
    ["id", callId],
    ["type", "function"],
    ["function", obj([["name", name], ["arguments", args]])],
  ]);
  if (!stream) {
    const message = obj([["role", "assistant"], ["content", null], ["tool_calls", [call]]]);
    return jsonResponse(200, completion(model, message, "tool_calls"));
  }
  const opened = obj([
    ["index", num("0")],
    ["id", callId],
    ["type", "function"],
    ["function", obj([["name", name], ["arguments", ""]])],
  ]);
  const pieces = [
    chunk(model, obj([["role", "assistant"], ["content", null]]), null, null),
    chunk(model, obj([["tool_calls", [opened]]]), null, null),
    chunk(
      model,
      obj([["tool_calls", [obj([["index", num("0")], ["function", obj([["arguments", args]])]])]]]),
      null,
      null,
    ),
    chunk(model, obj([]), "tool_calls", USAGE),
    "data: [DONE]\n\n",
  ];
  return sseStream(pieces);
}

async function handle(req: Request): Promise<Response> {
  const path = new URL(req.url).pathname;
  const trimmed = path.replace(/\/+$/, "");
  if (req.method === "GET") {
    if (trimmed.endsWith("/models")) {
      return jsonResponse(
        200,
        obj([
          ["object", "list"],
          ["data", MODELS.map((m) => obj([["id", m], ["object", "model"]]))],
        ]),
      );
    }
    return jsonResponse(404, obj([["error", obj([["message", "no such route"], ["type", "invalid_request_error"]])]]));
  }
  if (req.method !== "POST") {
    return jsonResponse(404, obj([["error", obj([["message", "no such route"], ["type", "invalid_request_error"]])]]));
  }

  const raw = await req.text();
  let body: PyValue;
  try {
    body = raw === "" ? loads("{}") : loads(raw);
  } catch {
    return jsonResponse(400, obj([["error", obj([["message", "bad json"], ["type", "invalid_request_error"]])]]));
  }
  if (!trimmed.endsWith("/chat/completions")) {
    return jsonResponse(404, obj([["error", obj([["message", "no such route"], ["type", "invalid_request_error"]])]]));
  }
  if ((req.headers.get("Authorization") ?? "") !== "Bearer mock-chat-key") {
    return jsonResponse(401, obj([["error", obj([["message", "bad key"], ["type", "authentication_error"]])]]));
  }

  const reqObj = body as PyObj;
  const modelValue = objGet(reqObj, "model");
  const model = typeof modelValue === "string" ? modelValue : "mock-chat";
  const stream = Boolean(objGet(reqObj, "stream"));
  const scen = scenario(raw);
  if (scen === "hold") {
    // AWAIT, never block: this is the whole threading property the docstring asks for.
    await Bun.sleep(HOLD_SECONDS * 1000);
  }
  if (scen === "listagents") {
    const messages = objGet(reqObj, "messages");
    const results = (Array.isArray(messages) ? messages : []).filter(
      (m): m is PyObj => isPyObj(m) && objGet(m, "role") === "tool",
    );
    if (results.length > 0) {
      return reply(model, stream, ["PEERS: ", ...results.map((m) => toolText(m))]);
    }
    const tools = objGet(reqObj, "tools");
    const offered = (Array.isArray(tools) ? tools : [])
      .filter((t): t is PyObj => isPyObj(t))
      .map((t) => {
        const fn = objGet(t, "function");
        const name = isPyObj(fn) ? objGet(fn, "name") : null;
        return typeof name === "string" ? name : null;
      });
    if (offered.includes("ListAgents")) {
      return toolCall(model, stream, "call_la1", "ListAgents", "{}");
    }
    return reply(model, stream, [
      "NO ListAgents TOOL OFFERED; tools=" + offered.filter((n) => n !== null).join(","),
    ]);
  }
  return reply(model, stream, REPLY_WORDS);
}

function main(): void {
  const port = process.argv.length > 2 ? Number(process.argv[2]) : 0;
  const server = Bun.serve({ hostname: "127.0.0.1", port, fetch: handle });
  process.stdout.write(dumps(obj([["port", num(String(server.port))]])) + "\n");
  // serve_forever(): nothing else to do; Bun keeps the loop alive on the listener.
}

main();
