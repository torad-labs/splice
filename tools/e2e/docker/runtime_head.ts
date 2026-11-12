#!/usr/bin/env bun
/** The structured half of runtime-head.sh (V4-232): a local runtime the way `splice setup` adds one, and
 *  what the real client and that runtime each saw of a turn through it.
 *
 *  serve <log>                      a llama-server-shaped OpenAI chat runtime on 127.0.0.1: one model,
 *                                   RUNTIME_MODEL, served for any id; every request appended to <log>
 *                                   with the status it got. Like the Bonsai chat template it refuses a
 *                                   `reasoning_effort` (the row's quirk says it raises). Answers ANSWER
 *                                   with PROMPT_TOKENS of prompt usage. Prints {"port": N} once listening.
 *  quiet <file>                     no [claude-code:unrecognized_model] line in a print-mode stderr or an
 *                                   interactive session's debug log.
 *  answer <stdout>                  print-mode text output is the runtime's answer and nothing else.
 *  window <stdout-json> <served> <log> <from>
 *                                   print-mode JSON: the client's window for the row and the input it
 *                                   counted for every prompt the runtime saw since line <from> of <log>
 *                                   put its compaction at <served> of the runtime's own tokens (a
 *                                   presented row: its table's 200k with the counts scaled to match).
 *  served <log> <model> <from>      every chat request since line <from> named <model>, carried nothing
 *                                   the runtime refuses or does not speak, and was answered 200.
 *
 *  Contract as lib.ts: evidence on stdout and exit 0, or the reason and exit 1.
 */
import { appendFileSync, readFileSync } from "node:fs";

class CheckFailed extends Error {}

function check(condition: unknown, message: string): asserts condition {
  if (!condition) throw new CheckFailed(message);
}

export const ANSWER = "Hello from the runtime.";
/** 24576 x 200000 / 245760 = 20000 exactly: scaled to a presented row's 200k, the count stays whole. */
export const PROMPT_TOKENS = 24_576;
const UNRECOGNIZED = "[claude-code:unrecognized_model]";
/** Keys an Anthropic request carries that an OpenAI chat runtime does not speak: the head translates
 *  them or drops them, and one arriving here would be the head passing the client's shape through. */
const ANTHROPIC_ONLY = ["thinking", "output_config", "context_management", "anthropic_beta", "system"];

type Logged = { path: string; status: number; body: Record<string, unknown> };

function logged(log: string, from: number): Logged[] {
  return readFileSync(log, "utf8").split("\n").filter(Boolean).slice(from)
    .map((l) => JSON.parse(l) as Logged)
    .filter((r) => r.path.endsWith("/chat/completions"));
}

function sse(chunk: unknown): string {
  return `data: ${JSON.stringify(chunk)}\n\n`;
}

function completion(model: string, stream: boolean): Response {
  const usage = { prompt_tokens: PROMPT_TOKENS, completion_tokens: 5, total_tokens: PROMPT_TOKENS + 5 };
  const base = { id: "chatcmpl-runtime", object: "chat.completion", created: 0, model };
  if (!stream) {
    const choice = { index: 0, message: { role: "assistant", content: ANSWER }, finish_reason: "stop" };
    return Response.json({ ...base, choices: [choice], usage });
  }
  const chunk = { ...base, object: "chat.completion.chunk" };
  const body = [
    sse({ ...chunk, choices: [{ index: 0, delta: { role: "assistant", content: "" }, finish_reason: null }] }),
    sse({ ...chunk, choices: [{ index: 0, delta: { content: ANSWER }, finish_reason: null }] }),
    sse({ ...chunk, choices: [{ index: 0, delta: {}, finish_reason: "stop" }] }),
    sse({ ...chunk, choices: [], usage }),
    "data: [DONE]\n\n",
  ].join("");
  return new Response(body, { headers: { "content-type": "text/event-stream" } });
}

const verbs: Record<string, (argv: string[]) => void | Promise<void>> = {
  serve(argv) {
    const [log] = argv;
    check(log, "usage: serve <log>");
    const model = process.env["RUNTIME_MODEL"] ?? "bonsai-2-27b";
    const server = Bun.serve({
      hostname: "127.0.0.1",
      port: 0,
      async fetch(req) {
        const path = new URL(req.url).pathname;
        const text = req.method === "POST" ? await req.text() : "";
        const body = text === "" ? {} : (JSON.parse(text) as Record<string, unknown>);
        let res: Response;
        if (path.endsWith("/models")) {
          res = Response.json({ object: "list", data: [{ id: model, object: "model", owned_by: "llamacpp" }] });
        } else if (!path.endsWith("/chat/completions")) {
          res = Response.json({ error: { code: 404, message: "File Not Found", type: "not_found_error" } }, { status: 404 });
        } else if ("reasoning_effort" in body) {
          // The Bonsai chat template's own refusal, as llama-server returns it.
          const message = `raise_exception('Unexpected reasoning effort ${String(body["reasoning_effort"])}')`;
          res = Response.json({ error: { code: 500, message, type: "server_error" } }, { status: 500 });
        } else {
          res = completion(model, body["stream"] === true);
        }
        appendFileSync(log, JSON.stringify({ path, status: res.status, body }) + "\n");
        return res;
      },
    });
    console.log(JSON.stringify({ port: server.port }));
  },

  quiet(argv) {
    const [file] = argv;
    check(file, "usage: quiet <stderr-or-debug-log>");
    const lines = readFileSync(file, "utf8").split("\n");
    const hits = lines.filter((l) => l.includes(UNRECOGNIZED));
    console.log(hits.length > 0 ? hits.join("\n") : `no such line in ${lines.filter(Boolean).length} line(s)`);
    check(hits.length === 0, `the client printed ${UNRECOGNIZED}`);
  },

  answer(argv) {
    const [stdout] = argv;
    check(stdout, "usage: answer <stdout>");
    const text = readFileSync(stdout, "utf8");
    console.log(JSON.stringify(text));
    check(text.trim() === ANSWER, `stdout is not the answer alone: ${JSON.stringify(text.slice(0, 400))}`);
  },

  window(argv) {
    const [stdout, served, log, from] = argv;
    check(stdout && served && log && from, "usage: window <stdout-json> <served> <log> <from>");
    const result = JSON.parse(readFileSync(stdout, "utf8")) as { modelUsage?: Record<string, Record<string, number>> };
    const rows = Object.entries(result.modelUsage ?? {});
    const [only] = rows;
    check(rows.length === 1 && only !== undefined, `the client reported ${rows.length} modelUsage rows, not the one row`);
    const [id, row] = only;
    const window = row["contextWindow"] ?? 0;
    const counted = (row["inputTokens"] ?? 0) + (row["cacheReadInputTokens"] ?? 0) + (row["cacheCreationInputTokens"] ?? 0);
    const raw = logged(log, Number(from)).filter((r) => r.status === 200).length * PROMPT_TOKENS;
    // The client compacts when counted / window reaches 1, and counted is raw x scale, so in the
    // runtime's own tokens it compacts at window x raw / counted.
    const ceiling = counted > 0 ? (window * raw) / counted : 0;
    console.log(`${id}: contextWindow ${window}, the runtime saw ${raw} prompt tokens, the client counted ${counted}; ` +
      `it compacts at ${ceiling} of the runtime's tokens`);
    check(raw > 0, "the runtime answered no prompt in this run");
    check(Math.abs(ceiling - Number(served)) <= 1, `the client compacts at ${ceiling}, not the runtime's ${served}`);
  },

  served(argv) {
    const [log, model, from] = argv;
    check(log && model && from, "usage: served <log> <model> <from>");
    const turns = logged(log, Number(from));
    for (const t of turns) {
      const keys = Object.keys(t.body).sort().join(",");
      console.log(`${t.status} model=${String(t.body["model"])} max_tokens=${String(t.body["max_tokens"] ?? t.body["max_completion_tokens"])} keys=${keys}`);
    }
    check(turns.length > 0, "no chat request reached the runtime");
    for (const t of turns) {
      check(t.body["model"] === model, `the runtime was asked for ${String(t.body["model"])}, not ${model}`);
      const foreign = ANTHROPIC_ONLY.filter((k) => k in t.body);
      check(foreign.length === 0, `the runtime was sent ${foreign.join(", ")}, which it does not speak`);
      check(t.status === 200, `the runtime answered ${t.status}`);
    }
  },
};

if (import.meta.main) {
  const [verb = "", ...rest] = process.argv.slice(2);
  const run = verbs[verb];
  if (run === undefined) {
    console.log(`runtime_head.ts: unknown verb '${verb}' (${Object.keys(verbs).join(", ")})`);
    process.exit(2);
  }
  try {
    await run(rest);
  } catch (e) {
    console.log(e instanceof CheckFailed ? e.message : `runtime_head.ts ${verb}: ${String(e)}`);
    process.exit(1);
  }
}
