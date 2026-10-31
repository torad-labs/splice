import assert from "node:assert/strict";
import { assertAcceptedCallbackText } from "./interactive-output";

const calls = ["call_probe", "call_probe_second"] as const;
const markers = ["native-batch-first", "native-batch-second"] as const;
type BatchObservation = { marks: any[]; upstreamRequests: any[]; scriptAttempts: number; callbackTimes: number[] };

export function assertScriptCallbacks(value: BatchObservation, secondOutput: unknown): void {
  const callbacks = value.marks.filter(mark => mark.event === "client_sse" && mark.frame?.type === "content_block_start" &&
    mark.frame.content_block?.type === "tool_use").map(mark => ({ ...mark.frame.content_block, epochMs: mark.epochMs }));
  assert.deepEqual(callbacks.map(call => call.name), ["Bash", "Read"], "both outer scripts must issue their actual client callback");
  assert.ok(callbacks.every(call => typeof call.id === "string" && call.id.length > 0));
  assert.equal(new Set(callbacks.map(call => call.id)).size, 2, "later script reused the first callback identity");
  const accepted = callbacks.map(call => value.marks.filter(mark => mark.event === "client_callback_result_received" &&
    mark.results?.some((result: any) => result.tool_use_id === call.id)).at(-1));
  assert.ok(accepted.every(Boolean), "both actual client callback results must reach the relay");
  for (const [index, call] of callbacks.entries()) {
    const result = accepted[index].results.find((item: any) => item.tool_use_id === call.id);
    assert.notEqual(result.is_error, true, `${call.name}: real client callback failed`);
    assert.ok(Number.isFinite(call.epochMs) && Number.isFinite(accepted[index].epochMs));
  }
  const firstAccepted = value.marks.find(mark => mark.event === "client_callback_result_received" &&
    mark.results?.some((result: any) => result.tool_use_id === callbacks[0].id));
  assert.ok(firstAccepted.epochMs <= callbacks[1].epochMs, "Read callback preceded acceptance of the first script's Bash result");
  const read = accepted[1].results.find((item: any) => item.tool_use_id === callbacks[1].id);
  const epoch = String(value.callbackTimes[0]);
  assert.ok(JSON.stringify(read.content).includes(epoch), "Read callback did not return the actual first Bash effect file");
  assert.ok(Array.isArray(secondOutput) && secondOutput.some((part: any) => part.type === "input_text" &&
    typeof part.text === "string" && part.text.includes(epoch)), "second native output lost the accepted Read result");
}

/** Inspect canonical model items and real output arrays; source text cannot prove execution. */
function batchOutputs(value: BatchObservation): any[] {
  assert.equal(value.scriptAttempts, 1, "the two-item batch must execute without response source retry");
  const done = value.marks.filter(mark => mark.event === "upstream_sse" && mark.frame?.type === "response.output_item.done")
    .map(mark => mark.frame.item).filter(item => item.type === "custom_tool_call" && calls.includes(item.call_id));
  assert.equal(done.length, 2, "fixture must emit exactly two complete outer exec items");
  assert.deepEqual(done.map(item => item.call_id), calls);
  const terminal = value.marks.find(mark => mark.event === "response_completed_sent")?.epochMs;
  const completedBatch = value.marks.find(mark => mark.event === "batch_items_done")?.epochMs;
  assert.ok(Number.isFinite(terminal) && Number.isFinite(completedBatch) && terminal - completedBatch >= 1900,
    "batch response terminal was not held for two seconds");
  assert.equal(value.callbackTimes.length, 1, "batch must produce exactly one Bash effect");
  assert.ok(Number.isFinite(value.callbackTimes[0]) && value.callbackTimes[0]! < terminal,
    "first batch effect did not execute before response terminal");
  const next = value.upstreamRequests.find(request => request.nativeInput.some((item: any) =>
    item.type === "custom_tool_call_output" && calls.includes(item.call_id)));
  assert.ok(next, "no model continuation received the batch outputs");
  const input: any[] = next.nativeInput;
  assertFirstResponseReasoning(value, input);
  const positions: number[] = [];
  for (const original of done) {
    const found = input.filter(item => item.type === "custom_tool_call" && item.call_id === original.call_id);
    assert.equal(found.length, 1, `${original.call_id}: original source item missing or duplicated`);
    assert.deepEqual(found[0], original, `${original.call_id}: original native source item changed`);
    positions.push(input.indexOf(found[0]));
  }
  const outputs: any[] = [];
  for (const call of calls) {
    const found = input.filter(item => item.type === "custom_tool_call_output" && item.call_id === call);
    assert.equal(found.length, 1, `${call}: batch output missing or duplicated`);
    outputs.push(found[0].output);
    positions.push(input.indexOf(found[0]));
  }
  assert.ok(positions.every((position, index) => index === 0 || position > positions[index - 1]!),
    "all original model batch items must precede both ordered outputs");
  assertScriptCallbacks(value, outputs[1]);
  return outputs;
}

/** A first response's completed reasoning must survive once, with the payload the server delivered. */
function assertFirstResponseReasoning(value: BatchObservation, input: any[]): void {
  const completed = value.marks.findIndex(mark => mark.event === "upstream_sse" && mark.frame?.type === "response.completed" &&
    calls.every(call => mark.frame.response?.output?.some((item: any) => item.type === "custom_tool_call" && item.call_id === call)));
  assert.ok(completed >= 0, "first batch response completion is missing");
  const responseId = value.marks[completed]!.frame.response.id;
  let created = -1;
  for (let index = 0; index < completed; index++) {
    const mark = value.marks[index];
    if (mark.event === "upstream_sse" && mark.frame?.type === "response.created" && mark.frame.response?.id === responseId) {
      created = index;
    }
  }
  assert.ok(created >= 0, "first batch response creation is missing");
  const reasoning = value.marks.slice(created + 1, completed)
    .filter(mark => mark.event === "upstream_sse" && mark.frame?.type === "response.output_item.done" &&
      mark.frame.item?.type === "reasoning")
    .map(mark => mark.frame.item);
  assert.ok(reasoning.length > 0, "first batch response emitted no completed reasoning items");
  assert.equal(new Set(reasoning.map(item => item.id)).size, reasoning.length,
    "first batch response repeated a completed reasoning identity");
  for (const item of reasoning) {
    const copies = input.filter(candidate => candidate?.type === "reasoning" && candidate.id === item.id);
    assert.equal(copies.length, 1, `${item.id}: completed first-response reasoning must appear exactly once in final history`);
    assert.deepEqual(copies[0], item, `${item.id}: completed first-response reasoning payload changed in final history`);
  }
}

function assertCompleted(content: any, call: string, marker: string): void {
  assert.ok(Array.isArray(content), `${call}: actual typed output array missing`);
  assert.match(content[0]?.text ?? "", /^Script completed\n/, `${call}: source did not complete`);
  assert.equal(content.filter((item: any) => item.type === "input_text" && item.text === marker).length, 1,
    `${call}: distinct executed output missing or duplicated`);
}

export function assertNativeBatch(value: BatchObservation): void {
  const outputs = batchOutputs(value);
  assertAcceptedCallbackText(value.upstreamRequests);
  outputs.forEach((content, index) => assertCompleted(content, calls[index]!, markers[index]!));
  const terminal = value.marks.find(mark => mark.event === "response_completed_sent")?.epochMs;
  const admitted = value.marks.find(mark => mark.event === "upstream_sse" &&
    mark.frame?.type === "response.output_item.done" && mark.frame.item?.call_id === calls[1])?.epochMs;
  const callbackReturned = value.marks.find(mark => mark.event === "client_callback_result_received")?.epochMs;
  assert.ok(Number.isFinite(callbackReturned) && callbackReturned < terminal,
    "fixture did not return the first callback before the held response terminal");
  const readId = value.marks.find(mark => mark.event === "client_sse" &&
    mark.frame?.content_block?.type === "tool_use" && mark.frame.content_block.name === "Read")?.frame.content_block.id;
  const readReturned = value.marks.find(mark => mark.event === "client_callback_result_received" &&
    mark.results?.some((result: any) => result.tool_use_id === readId))?.epochMs;
  assert.ok(Number.isFinite(readReturned) && readReturned < terminal,
    "second script's actual Read result was held until the response terminal");
  const epochs = outputs[1].filter((item: any) => item.type === "input_text" &&
    typeof item.text === "string" && /^native-batch-second-epoch:\d+$/.test(item.text));
  assert.equal(epochs.length, 1, "second source must emit one actual execution epoch");
  const executed = Number(epochs[0].text.slice("native-batch-second-epoch:".length));
  assert.ok(Number.isFinite(admitted) && Number.isSafeInteger(executed) && executed >= admitted && executed < terminal,
    "second source execution was held until the response terminal");
}

/** A daemon restart destroys V8 continuations; only the sealed unstarted sibling may run afterward. */
export function assertNativeRestartBatch(value: BatchObservation): void {
  const [lost, queued] = batchOutputs(value);
  assert.equal(typeof lost, "string", "lost first native source must return its explicit interruption receipt");
  assert.match(lost, /^Script terminated\n/, "restart fabricated completion of the destroyed native continuation");
  const delimiter = "\nOutput:\n";
  assert.ok(lost.includes(delimiter), "terminated native receipt is missing its evidence payload");
  const interrupted = JSON.parse(lost.slice(lost.indexOf(delimiter) + delimiter.length));
  assert.equal(interrupted.status, "interrupted");
  assert.equal(interrupted.sourceRerun, false, "first source was rerun after native process loss");
  const callbacks = value.marks.filter(mark => mark.event === "client_sse" && mark.frame?.type === "content_block_start" &&
    mark.frame.content_block?.type === "tool_use" && mark.frame.content_block.name === "Bash")
    .map(mark => mark.frame.content_block.id);
  assert.equal(new Set(callbacks).size, 1, "restart must retain one actual Bash callback identity");
  assert.deepEqual(interrupted.results, [{ id: callbacks[0], output: "accepted", isError: false }],
    "first source lost or changed the accepted callback result");
  assert.deepEqual(interrupted.unresolved, []);
  assertCompleted(queued, calls[1], markers[1]);
}

/** Completion arrival, rather than item creation or output_index, admits each native handler. */
export function assertNativeOutOfOrderBatch(value: BatchObservation): void {
  assertNativeBatch(value);
  const frames = value.marks.filter(mark => mark.event === "upstream_sse");
  const created = frames.filter(mark => mark.frame?.type === "response.output_item.added" &&
    calls.includes(mark.frame.item?.call_id));
  const completed = frames.filter(mark => mark.frame?.type === "response.output_item.done" &&
    calls.includes(mark.frame.item?.call_id));
  assert.deepEqual(created.map(mark => [mark.frame.output_index, mark.frame.item.call_id]),
    [[0, calls[1]], [1, calls[0]]], "fixture did not create index zero before index one");
  assert.deepEqual(completed.map(mark => [mark.frame.output_index, mark.frame.item.call_id]),
    [[1, calls[0]], [0, calls[1]]], "fixture did not complete index one before index zero");
  const firstDone = completed[0]?.epochMs, secondDone = completed[1]?.epochMs;
  assert.ok(Number.isFinite(firstDone) && Number.isFinite(secondDone) && secondDone - firstDone >= 1900,
    "the earlier-created item was not held incomplete for two seconds");
  assert.ok(value.callbackTimes[0]! >= firstDone && value.callbackTimes[0]! < secondDone,
    "first-created incomplete item blocked the later-completed item's real Bash effect");
  const issued = value.marks.find(mark => mark.event === "client_sse" &&
    mark.frame?.content_block?.type === "tool_use" && mark.frame.content_block.name === "Bash")?.epochMs;
  assert.ok(Number.isFinite(issued) && issued >= firstDone && issued < secondDone,
    "the completed item's callback was held behind an incomplete sibling");
  const returned = value.marks.find(mark => mark.event === "client_callback_result_received")?.epochMs;
  assert.ok(Number.isFinite(returned) && returned < secondDone,
    "the completed item's callback result did not arrive during the incomplete-sibling hold");
  const terminal = frames.find(mark => mark.frame?.type === "response.completed" &&
    mark.frame.response?.output?.some((item: any) => item.call_id === calls[0]));
  assert.deepEqual(terminal?.frame.response.output.filter((item: any) => item.type === "custom_tool_call")
    .map((item: any) => item.call_id), [calls[1], calls[0]],
    "fixture terminal output must retain index order opposite to completion arrival");
}
