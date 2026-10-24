import assert from "node:assert/strict";

const calls = ["call_probe", "call_probe_second"] as const;
const markers = ["native-batch-first", "native-batch-second"] as const;
type BatchObservation = { marks: any[]; upstreamRequests: any[]; scriptAttempts: number; callbackTimes: number[] };

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
  return outputs;
}

function assertCompleted(content: any, call: string, marker: string): void {
  assert.ok(Array.isArray(content), `${call}: actual typed output array missing`);
  assert.match(content[0]?.text ?? "", /^Script completed\n/, `${call}: source did not complete`);
  assert.equal(content.filter((item: any) => item.type === "input_text" && item.text === marker).length, 1,
    `${call}: distinct executed output missing or duplicated`);
}

export function assertNativeBatch(value: BatchObservation): void {
  const outputs = batchOutputs(value);
  outputs.forEach((content, index) => assertCompleted(content, calls[index]!, markers[index]!));
  const terminal = value.marks.find(mark => mark.event === "response_completed_sent")?.epochMs;
  const admitted = value.marks.find(mark => mark.event === "upstream_sse" &&
    mark.frame?.type === "response.output_item.done" && mark.frame.item?.call_id === calls[1])?.epochMs;
  const callbackReturned = value.marks.find(mark => mark.event === "client_callback_result_received")?.epochMs;
  assert.ok(Number.isFinite(callbackReturned) && callbackReturned < terminal,
    "fixture did not return the first callback before the held response terminal");
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
  assert.deepEqual(interrupted.results, [{ id: callbacks[0], output: "(Bash completed with no output)", isError: false }],
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
