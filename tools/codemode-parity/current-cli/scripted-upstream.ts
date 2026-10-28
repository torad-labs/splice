import { writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import type { CliObservation, CliScenario, Json, ObjectValue } from "./types";

const object = (value: Json | undefined): ObjectValue | undefined => value !== null && typeof value === "object" && !Array.isArray(value) ? value : undefined;

export function isTitleRequest(body: ObjectValue): boolean {
  const format = object(object(body.text)?.format);
  const schema = object(format?.schema), properties = object(schema?.properties);
  if (format?.type !== "json_schema" || schema?.type !== "object" || Object.keys(properties ?? {}).length !== 1 || object(properties?.title)?.type !== "string") return false;
  return Array.isArray(body.input) && body.input.some(item => {
    const message = object(item);
    return message?.type === "message" && Array.isArray(message.content) && message.content.some(part => {
      const text = object(part)?.text;
      return typeof text === "string" && text.startsWith("Generate a concise, single-line task title of at most 36 characters");
    });
  });
}

function callId(scenario: CliScenario, index: number, extra = 0): string {
  return `probe-${scenario.id}-${index}${extra ? `-${extra}` : ""}`;
}

export function yieldedCell(input: ObjectValue[], id: string): string {
  const rows = input.filter(item => item.type === "custom_tool_call_output" && item.call_id === id && item.name !== "exec");
  if (rows.length !== 1) throw Error(`Expected exactly one primary exec result for ${id}`);
  const output = rows[0]!.output;
  const header = typeof output === "string" ? output : Array.isArray(output) ? object(output[0])?.text : undefined;
  if (typeof header !== "string") throw Error("Yielded cell has no typed status header");
  const match = header.match(/^Script running with cell ID ([^\n]+)\nWall time /);
  if (!match) throw Error("Wait action was not preceded by an actual yielded exec");
  return match[1]!;
}

export function actionItems(scenario: CliScenario, index: number, input: ObjectValue[], lastExecId?: string): ObjectValue[] {
  const action = scenario.actions[index];
  if (!action) return [];
  if (action.kind === "exec") return [action.source, ...(action.followingSources ?? [])].map((source, extra) => ({
    type: "custom_tool_call", id: `item-${callId(scenario, index, extra)}`, call_id: callId(scenario, index, extra), name: "exec", input: source,
  }));
  const cell_id = action.cellId ?? yieldedCell(input, lastExecId ?? "");
  const args = { cell_id, yield_time_ms: action.yieldTimeMs ?? 1000, ...(action.terminate ? { terminate: true } : {}) };
  return [{ type: "function_call", id: `item-${callId(scenario, index)}`, call_id: callId(scenario, index), name: "wait", arguments: JSON.stringify(args) }];
}

export function expectedTaskRequests(scenario: CliScenario): number {
  return scenario.actions.length + 1 + (scenario.interruption ? 1 : 0);
}

export function startScriptedUpstream(scenario: CliScenario, directory: string, nonce: string) {
  const requests: ObjectValue[] = [], frames: ObjectValue[] = [], observations: CliObservation[] = [];
  let taskRequests = 0, auxiliaryRequests = 0;
  let lastExecId: string | undefined;
  let failure: string | undefined;
  let interruptionHeld = false;
  let persistence = Promise.resolve();
  const finalText = `CODEMODE_CURRENT_${nonce}`;
  const event = (type: string, value: ObjectValue): string => {
    const frame = { type, ...value };
    frames.push(frame);
    return `event: ${type}\ndata: ${JSON.stringify(frame)}\n\n`;
  };
  const persist = () => {
    const snapshot = [requests, frames, observations].map(value => JSON.stringify(value, null, 2) + "\n");
    persistence = persistence.then(async () => {
      for (const [index, name] of ["requests", "frames", "observations"].entries()) {
        await writeFile(resolve(directory, `${name}.json`), snapshot[index]!);
      }
    });
    return persistence;
  };
  const server = Bun.serve({
    hostname: "127.0.0.1", port: 0,
    async fetch(request) {
      if (request.method !== "POST" || new URL(request.url).pathname !== "/v1/responses") {
        failure = `Unexpected mock route ${request.method} ${new URL(request.url).pathname}`;
        return Response.json({ error: failure }, { status: 404 });
      }
      try {
        const body = await request.json() as ObjectValue;
        requests.push(body);
        const title = isTitleRequest(body);
        const sequence = title ? ++auxiliaryRequests : ++taskRequests;
        const id = `${title ? "title" : "response"}-${sequence}`;
        const input = (Array.isArray(body.input) ? body.input.map(object).filter(Boolean) : []) as ObjectValue[];
        if (!title) observations.push({ request: sequence, input });
        let stream = event("response.created", { response: { id, status: "in_progress", output: [] } });
        if (!title && scenario.interruption && sequence === 2) {
          const cellId = yieldedCell(input, callId(scenario, 0));
          const receipt = { schema: 1, request: sequence, cellId, startedAt: Date.now(), abortedAt: undefined as number | undefined };
          const save = () => writeFile(resolve(directory, "interruption-hold.json"), JSON.stringify(receipt) + "\n");
          await persist(); await save();
          const aborted = () => { if (!receipt.abortedAt) { receipt.abortedAt = Date.now(); void save(); } };
          request.signal.addEventListener("abort", aborted, { once: true });
          interruptionHeld = true;
          return new Response(new ReadableStream({
            start(controller) { controller.enqueue(new TextEncoder().encode(stream)); },
            cancel() { aborted(); },
          }), { headers: { "content-type": "text/event-stream", "x-request-id": id } });
        }
        const actionIndex = sequence - 1 - (scenario.interruption && sequence > 2 ? 1 : 0);
        const items = title ? [] : actionItems(scenario, actionIndex, input, lastExecId);
        if (!title && scenario.actions[actionIndex]?.kind === "exec") {
          lastExecId = String(items.at(-1)!.call_id);
        }
        if (!items.length) {
          const text = title ? JSON.stringify({ title: "CodeMode reference check" }) : finalText;
          const item = { type: "message", id: `item-final-${sequence}`, role: "assistant", status: "completed", channel: "final", content: [{ type: "output_text", text, annotations: [] }] };
          items.push(item);
          stream += event("response.output_item.added", { output_index: 0, item: { ...item, content: [] } });
          stream += event("response.content_part.added", { item_id: item.id, output_index: 0, content_index: 0, part: { type: "output_text", text: "", annotations: [] } });
          stream += event("response.output_text.delta", { item_id: item.id, output_index: 0, content_index: 0, delta: text });
          stream += event("response.output_text.done", { item_id: item.id, output_index: 0, content_index: 0, text });
          stream += event("response.output_item.done", { output_index: 0, item });
        } else for (const [output_index, item] of items.entries()) {
          const custom = item.type === "custom_tool_call";
          const field = custom ? "input" : "arguments";
          const family = custom ? "custom_tool_call_input" : "function_call_arguments";
          stream += event("response.output_item.added", { output_index, item: { ...item, [field]: "" } });
          stream += event(`response.${family}.delta`, { item_id: item.id!, output_index, delta: item[field]! });
          stream += event(`response.${family}.done`, { item_id: item.id!, output_index, [field]: item[field]! });
          stream += event("response.output_item.done", { output_index, item });
        }
        const completion = event("response.completed", { response: { id, status: "completed", output: items, usage: { input_tokens: 1, output_tokens: 1, total_tokens: 2 } } });
        await persist();
        if (!title && sequence === 1 && scenario.completionHoldMs) {
          const encoder = new TextEncoder();
          return new Response(new ReadableStream({ async start(controller) {
            const itemDoneSentAt = Date.now();
            controller.enqueue(encoder.encode(stream));
            await Bun.sleep(scenario.completionHoldMs!);
            const completedSentAt = Date.now();
            await writeFile(resolve(directory, "completion-hold.json"), JSON.stringify({ itemDoneSentAt, completedSentAt }) + "\n");
            controller.enqueue(encoder.encode(completion)); controller.close();
          } }), { headers: { "content-type": "text/event-stream", "x-request-id": id } });
        }
        return new Response(stream + completion, { headers: { "content-type": "text/event-stream", "x-request-id": id } });
      } catch (error) {
        failure = error instanceof Error ? error.message : String(error);
        await persist();
        return Response.json({ error: failure }, { status: 500 });
      }
    },
  });
  return { server, finalText, observations, flush: persist, interruptionReady: () => interruptionHeld,
    counts: () => ({ taskRequests, auxiliaryRequests, failure }) };
}
