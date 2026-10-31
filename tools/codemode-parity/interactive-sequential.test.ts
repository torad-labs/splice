import { expect, test } from "bun:test";
import { assertNativeSequential } from "./interactive-sequential";

function observation(): any {
  const calls = [
    { type: "custom_tool_call", id: "exec_probe", call_id: "call_probe", name: "exec", input: "text(await tools.Bash({}));" },
    { type: "custom_tool_call", id: "exec_probe_second", call_id: "call_probe_second", name: "exec", input: "text(await tools.Read({}));" },
  ];
  const reasoning = { type: "reasoning", id: "rs_probe_second", encrypted_content: "synthetic-second-round-envelope" };
  const outputs = calls.map((call, index) => ({ type: "custom_tool_call_output", call_id: call.call_id, output: [
    { type: "input_text", text: "Script completed\nWall time 0.1 seconds\nOutput:\n" },
    { type: "input_text", text: index === 0 ? "native-sequential-first" : "native-sequential-second" },
    { type: "input_text", text: index === 0 ? "accepted" : "effect-file:1100" },
  ] }));
  outputs[1]!.output.push({ type: "input_text", text: "native-sequential-second-epoch:4200" });
  return { scriptAttempts: 2, callbackTimes: [1100], marks: [
    { event: "upstream_sse", epochMs: 1000, frame: { type: "response.output_item.done", item: calls[0] } },
    { event: "client_sse", epochMs: 1050, frame: { type: "content_block_start", content_block: { type: "tool_use", name: "Bash", id: "bash-id" } } },
    { event: "client_callback_result_received", epochMs: 1150, results: [{ tool_use_id: "bash-id", content: "accepted" }] },
    { event: "upstream_sse", epochMs: 3000, frame: { type: "response.completed", response: { id: "first-model-response", output: [calls[0]] } } },
    { event: "upstream_sse", epochMs: 4000, frame: { type: "response.output_item.done", item: calls[1] } },
    { event: "client_sse", epochMs: 4100, frame: { type: "content_block_start", content_block: { type: "tool_use", name: "Read", id: "read-id" } } },
    { event: "client_callback_result_received", epochMs: 4150, results: [{ tool_use_id: "read-id", content: "effect-file:1100" }] },
    { event: "upstream_sse", epochMs: 6000, frame: { type: "response.completed", response: { id: "second-model-response", output: [reasoning, calls[1]] } } },
  ], upstreamRequests: [
    { epochMs: 3100, nativeInput: [calls[0], outputs[0]] },
    { epochMs: 6100, nativeInput: [calls[0], outputs[0], reasoning, calls[1], outputs[1]] },
  ] };
}

test("requires distinct model responses, callbacks, accepted outputs and preserved sequential model history", () => {
  expect(() => assertNativeSequential(observation())).not.toThrow();
});
test("rejects siblings disguised as sequential model responses or no completed-first-source request", () => {
  const sibling = observation(); sibling.marks.at(-1).frame.response.id = "first-model-response";
  expect(() => assertNativeSequential(sibling)).toThrow();
  const skipped = observation(); skipped.upstreamRequests.shift();
  expect(() => assertNativeSequential(skipped)).toThrow();
});
test("rejects a steered second source, missing callback and a lost accepted Read result", () => {
  const lost = observation(); lost.upstreamRequests[1].nativeInput[4].output = "Script terminated\nsource was not rerun";
  expect(() => assertNativeSequential(lost)).toThrow();
  const noRead = observation(); noRead.marks = noRead.marks.filter((mark: any) => mark.frame?.content_block?.name !== "Read");
  expect(() => assertNativeSequential(noRead)).toThrow();
  const noResult = observation(); noResult.upstreamRequests[1].nativeInput[4].output[2].text = "Read appeared in source only";
  expect(() => assertNativeSequential(noResult)).toThrow();
});
test("rejects dropping, duplicating or reconstructing the earlier canonical script and result", () => {
  for (const edit of [
    (items: any[]) => items.splice(1, 1),
    (items: any[]) => items.push(structuredClone(items[0])),
    (items: any[]) => items[0] = { ...items[0], input: "reconstructed source" },
    (items: any[]) => [items[1], items[3]] = [items[3], items[1]],
  ]) {
    const value = observation(); edit(value.upstreamRequests[1].nativeInput);
    expect(() => assertNativeSequential(value)).toThrow();
  }
});
test("rejects lost second-round reasoning and second execution delayed beyond its own terminal", () => {
  const reason = observation(); reason.upstreamRequests[1].nativeInput.splice(2, 1);
  expect(() => assertNativeSequential(reason)).toThrow();
  const delayed = observation(); delayed.upstreamRequests[1].nativeInput[4].output.at(-1).text = "native-sequential-second-epoch:6001";
  expect(() => assertNativeSequential(delayed)).toThrow();
});
