import { createReadStream, existsSync, readFileSync, writeFileSync } from "node:fs";
import { createInterface } from "node:readline";
import { join, resolve } from "node:path";

type Row = Record<string, any>;
type Event = { at: number; data: Row };
type Exchange = {
  connection: number; requestAt: number; generate: boolean; inputReasoning: number; events: Event[];
  model: string | null; reasoning: { effort: unknown; summary: unknown; context: unknown }; continued: boolean;
};

/** Assign an SSE event the receipt time of the chunk that completes it. Preserve UTF-8 and frame boundaries. */
class EventStream {
  private decoder = new TextDecoder();
  private pending = "";
  push(bytes: Uint8Array, at: number): Event[] {
    this.pending += this.decoder.decode(bytes, { stream: true });
    const events: Event[] = [];
    let end: RegExpExecArray | null;
    while ((end = /\r?\n\r?\n/.exec(this.pending))) {
      const frame = this.pending.slice(0, end.index);
      this.pending = this.pending.slice(end.index + end[0].length);
      const data = frame.split(/\r?\n/).filter(line => line.startsWith("data:")).map(line => line.slice(5).trimStart()).join("\n");
      if (data && data !== "[DONE]") {
        try { events.push({ at, data: JSON.parse(data) }); } catch { /* Retained raw capture remains authoritative. */ }
      }
    }
    return events;
  }
}

async function exchanges(file: string, client: boolean): Promise<Exchange[]> {
  if (!existsSync(file)) return [];
  const current = new Map<number, Exchange>();
  const streams = new Map<number, EventStream>();
  const all: Exchange[] = [];
  for await (const line of createInterface({ input: createReadStream(file), crlfDelay: Infinity })) {
    if (!line.trim()) continue;
    const row: Row = JSON.parse(line);
    if (!Number.isFinite(row.at_ms) || !Number.isFinite(row.connection)) continue;
    if (row.kind === "request") {
      let body: Row;
      try { body = JSON.parse(row.text); } catch { continue; }
      body = body.type === "response.create" && body.response ? { ...body.response, type: body.type, generate: body.generate ?? body.response.generate } : body;
      if (client ? !Array.isArray(body.messages) : !(Array.isArray(body.input) || body.type === "response.create")) continue;
      const exchange: Exchange = {
        connection: row.connection, requestAt: row.at_ms, generate: body.generate !== false,
        inputReasoning: (body.input ?? []).filter((item: Row) => item.type === "reasoning").length, events: [],
        model: typeof body.model === "string" ? body.model : null,
        reasoning: { effort: body.reasoning?.effort ?? null, summary: body.reasoning?.summary ?? null, context: body.reasoning?.context ?? null },
        continued: typeof body.previous_response_id === "string",
      };
      current.set(row.connection, exchange);
      streams.set(row.connection, new EventStream());
      all.push(exchange);
    } else if (row.kind === "response_frame" || row.kind === "response_chunk") {
      const exchange = current.get(row.connection);
      if (!exchange) continue;
      if (row.kind === "response_frame") {
        try { exchange.events.push({ at: row.at_ms, data: JSON.parse(row.text) }); } catch { /* Malformed payload retained in capture. */ }
      } else {
        const bytes = typeof row.raw_base64 === "string" ? Buffer.from(row.raw_base64, "base64") : Buffer.from(row.text ?? "");
        exchange.events.push(...streams.get(row.connection)!.push(bytes, row.at_ms));
      }
    }
  }
  return all;
}

function modelFacts(exchange: Exchange) {
  const terminal = exchange.events.find(event => ["response.completed", "response.failed", "response.incomplete"].includes(event.data.type));
  const summaries = exchange.events.filter(event => event.data.type === "response.reasoning_summary_text.delta");
  const summaryDone = exchange.events.filter(event => event.data.type === "response.reasoning_summary_text.done");
  const rawReasoning = exchange.events.filter(event => event.data.type === "response.reasoning_text.delta");
  return {
    request_at_ms: exchange.requestAt,
    model: exchange.model,
    requested_reasoning: exchange.reasoning,
    continued_request: exchange.continued,
    terminal_at_ms: terminal?.at ?? null,
    terminal_type: terminal?.data.type ?? null,
    input_reasoning_items: exchange.inputReasoning,
    summary_parts: summaryDone.length,
    summary_characters: summaryDone.reduce((count, event) => count + (event.data.text?.length ?? 0), 0),
    live_summary_characters: summaries.reduce((count, event) => count + (event.data.delta?.length ?? 0), 0),
    raw_reasoning_characters: rawReasoning.reduce((count, event) => count + (event.data.delta?.length ?? 0), 0),
    first_readable_at_ms: [...summaries, ...rawReasoning].sort((a, b) => a.at - b.at)[0]?.at ?? null,
    exec_items: exchange.events.filter(event => event.data.type === "response.output_item.done" && event.data.item?.type === "custom_tool_call").map(event => ({
      item_done_at_ms: event.at,
      response_tail_ms: terminal ? terminal.at - event.at : null,
      utf8_bytes: Buffer.byteLength(event.data.item.input ?? ""),
    })),
  };
}

function clientFacts(exchange: Exchange) {
  const starts = exchange.events.filter(event => event.data.type === "content_block_start");
  const thinking = new Map<number, string>();
  for (const event of exchange.events) {
    if (event.data.delta?.type === "thinking_delta") thinking.set(event.data.index, (thinking.get(event.data.index) ?? "") + (event.data.delta.thinking ?? ""));
  }
  const buffers = [...thinking.values()];
  const tools = starts.filter(event => event.data.content_block?.type === "tool_use");
  const realReasoning = exchange.events.filter(event => event.data.delta?.type === "thinking_delta" && !(thinking.get(event.data.index) ?? "").startsWith("[splice]"));
  return {
    request_at_ms: exchange.requestAt,
    message_complete: exchange.events.some(event => event.data.type === "message_stop"),
    opaque_thinking_blocks: starts.filter(event => event.data.content_block?.type === "redacted_thinking").length,
    readable_thinking_characters: buffers.filter(text => !text.startsWith("[splice]")).reduce((count, text) => count + text.length, 0),
    keepalive_blocks: buffers.filter(text => text.startsWith("[splice]")).length,
    first_readable_at_ms: realReasoning[0]?.at ?? null,
    tools: tools.map(event => ({ at_ms: event.at, name: event.data.content_block.name })),
  };
}

const run = resolve(process.argv[2] ?? "");
const manifest = JSON.parse(readFileSync(join(run, "manifest.json"), "utf8"));
const results: Row[] = JSON.parse(readFileSync(join(run, "results.json"), "utf8"));
const trials: Row[] = [];
for (const result of results) {
  const dir = join(run, result.task, `${result.repetition}-${result.arm}`, "requests");
  const generated = (await exchanges(join(dir, `${result.arm === "codex" ? "native" : "upstream"}.jsonl`), false)).filter(exchange => exchange.generate).map(modelFacts);
  const clients = result.arm === "splice" ? (await exchanges(join(dir, "client.jsonl"), true)).map(clientFacts) : [];
  const tools = clients.flatMap(client => client.tools).sort((a, b) => a.at_ms - b.at_ms);
  const dispatch = generated.flatMap((exchange, index) => exchange.exec_items.map(item => {
    const nextRequest = generated[index + 1]?.request_at_ms ?? Infinity;
    const tool = tools.find(tool => tool.at_ms >= item.item_done_at_ms && tool.at_ms <= nextRequest);
    return { ...item, first_tool_after_item_ms: tool ? tool.at_ms - item.item_done_at_ms : null, first_tool_before_response_terminal: tool && exchange.terminal_at_ms !== null ? tool.at_ms < exchange.terminal_at_ms : null };
  }));
  trials.push({ task: result.task, arm: result.arm, repetition: result.repetition, verified: result.verified, duration_ms: result.duration_ms, generated, clients, dispatch });
}
const report = {
  run, jar_sha256: manifest.jar_sha256, replay_reasoning: manifest.replay_reasoning ?? false,
  model: manifest.config.model, effort: manifest.config.effort,
  limits: "Capture-relay receipt times, not runtime start or UI paint. First later client callback is associated by time window, not call identity. HTTP event time is the completing chunk time; WebSocket event time is frame receipt. No content or credentials are copied. Missing captures remain unknown.",
  trials,
};
const target = process.argv[3] ? resolve(process.argv[3]) : join(run, "delivery-comparison.json");
writeFileSync(target, JSON.stringify(report, null, 2) + "\n", { mode: 0o600 });
console.log(JSON.stringify({ report: target, trials: trials.length, opaque_blocks: trials.reduce((count, trial) => count + trial.clients.reduce((n: number, client: Row) => n + client.opaque_thinking_blocks, 0), 0), exec_items: trials.reduce((count, trial) => count + trial.dispatch.length, 0) }));
