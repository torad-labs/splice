import assert from "node:assert/strict";
import { assertAcceptedCallbackText } from "./interactive-output";
import { assertScriptCallbacks } from "./interactive-batch";

/** A second model response creates its own execution after the first script's output was already canonical. */
export function assertNativeSequential(value: any): void {
  assert.equal(value.scriptAttempts, 2, "sequential fixture must execute exactly two distinct model script responses");
  assert.equal(value.callbackTimes.length, 1, "sequential scripts must perform exactly one Bash effect");
  const frames = value.marks.filter((mark: any) => mark.event === "upstream_sse");
  const done = frames.filter((mark: any) => mark.frame?.type === "response.output_item.done" &&
    mark.frame.item?.type === "custom_tool_call");
  assert.deepEqual(done.map((mark: any) => mark.frame.item.call_id), ["call_probe", "call_probe_second"]);
  const firstResponse = frames.find((mark: any) => mark.frame.type === "response.completed" &&
    mark.frame.response?.output?.some((item: any) => item.call_id === "call_probe"));
  const secondResponse = frames.find((mark: any) => mark.frame.type === "response.completed" &&
    mark.frame.response?.output?.some((item: any) => item.call_id === "call_probe_second"));
  assert.ok(firstResponse && secondResponse && firstResponse.frame.response.id !== secondResponse.frame.response.id,
    "the two exec items must come from different actual model responses");
  assert.equal(firstResponse.frame.response.output.some((item: any) => item.call_id === "call_probe_second"), false);
  const middle = value.upstreamRequests.find((request: any) => request.nativeInput.some((item: any) =>
    item.type === "custom_tool_call_output" && item.call_id === "call_probe") &&
    !request.nativeInput.some((item: any) => item.call_id === "call_probe_second"));
  assert.ok(middle, "second model request did not receive the completed first source before creating its own exec");
  assert.ok(Number.isFinite(middle.epochMs) && middle.epochMs >= firstResponse.epochMs && middle.epochMs <= done[1].epochMs,
    "second source was not created by the request following the first response's completion");
  const final = value.upstreamRequests.find((request: any) => request.nativeInput.some((item: any) =>
    item.type === "custom_tool_call_output" && item.call_id === "call_probe_second"));
  assert.ok(final, "no final model request received the second script's actual result");
  const positions: number[] = [];
  const outputs: any[] = [];
  for (const mark of done) {
    const original = mark.frame.item;
    const call = final.nativeInput.filter((item: any) => item.type === "custom_tool_call" && item.call_id === original.call_id);
    const output = final.nativeInput.filter((item: any) => item.type === "custom_tool_call_output" && item.call_id === original.call_id);
    assert.equal(call.length, 1); assert.deepEqual(call[0], original, "sequential model source was reconstructed or duplicated");
    assert.equal(output.length, 1, "sequential source lost or duplicated its canonical result");
    positions.push(final.nativeInput.indexOf(call[0]), final.nativeInput.indexOf(output[0]));
    outputs.push(output[0].output);
  }
  assert.ok(positions.every((position, index) => index === 0 || position > positions[index - 1]!),
    "sequential model history must preserve first call/result before second call/result");
  for (const type of ["custom_tool_call", "custom_tool_call_output"]) {
    assert.deepEqual(final.nativeInput.find((item: any) => item.type === type && item.call_id === "call_probe"),
      middle.nativeInput.find((item: any) => item.type === type && item.call_id === "call_probe"),
      "second script changed the first script's already canonical call/result");
  }
  assertAcceptedCallbackText(value.upstreamRequests);
  assertScriptCallbacks(value, outputs[1]);
  outputs.forEach((output, index) => {
    assert.ok(Array.isArray(output), "sequential native typed output array missing");
    assert.match(output[0]?.text ?? "", /^Script completed\n/);
    assert.equal(output.filter((part: any) => part.type === "input_text" &&
      part.text === (index === 0 ? "native-sequential-first" : "native-sequential-second")).length, 1);
  });
  const epochs = outputs[1].filter((part: any) => part.type === "input_text" &&
    /^native-sequential-second-epoch:\d+$/.test(part.text));
  assert.equal(epochs.length, 1);
  const executed = Number(epochs[0].text.slice("native-sequential-second-epoch:".length));
  assert.ok(Number.isFinite(done[1].epochMs) && Number.isSafeInteger(executed) &&
    executed >= done[1].epochMs && executed < secondResponse.epochMs, "second execution did not finish during its own held response");
  const secondReasoning = secondResponse.frame.response.output.find((item: any) => item.id === "rs_probe_second");
  assert.ok(secondReasoning?.encrypted_content === "synthetic-second-round-envelope");
  const preserved = final.nativeInput.filter((item: any) => item.id === "rs_probe_second");
  assert.equal(preserved.length, 1,
    "second response's own reasoning identity was lost or duplicated");
  assert.deepEqual(preserved[0], secondReasoning, "second response reasoning bytes changed in canonical history");
  const reasonPosition = final.nativeInput.indexOf(preserved[0]);
  const [, firstOutput, secondCall] = positions;
  assert.ok(firstOutput !== undefined && secondCall !== undefined &&
    reasonPosition > firstOutput && reasonPosition < secondCall, "second reasoning moved across a canonical script boundary");
}
