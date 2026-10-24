import assert from "node:assert/strict";

const calls = ["call_probe", "call_probe_second"] as const;
const markers = ["native-batch-first", "native-batch-second"] as const;
type BatchObservation = { marks: any[]; upstreamRequests: any[]; scriptAttempts: number; callbackTimes: number[] };

/** Inspect canonical model items and real output arrays; source text cannot prove execution. */
export function assertNativeBatch(value: BatchObservation): void {
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
  for (const [index, call] of calls.entries()) {
    const found = input.filter(item => item.type === "custom_tool_call_output" && item.call_id === call);
    assert.equal(found.length, 1, `${call}: batch output missing or duplicated`);
    const content = found[0].output;
    assert.ok(Array.isArray(content), `${call}: actual typed output array missing`);
    assert.match(content[0]?.text ?? "", /^Script completed\n/, `${call}: source did not complete`);
    assert.equal(content.filter((item: any) => item.type === "input_text" && item.text === markers[index]).length, 1,
      `${call}: distinct executed output missing or duplicated`);
    positions.push(input.indexOf(found[0]));
  }
  assert.ok(positions.every((position, index) => index === 0 || position > positions[index - 1]!),
    "all original model batch items must precede both ordered outputs");
}
