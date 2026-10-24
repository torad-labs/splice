import { NativeReference } from "./native-reference";
import assert from "node:assert/strict";
import { explicitYieldSource } from "./programs";

const binary = process.argv[2];
if (!binary) throw new Error("usage: bun tools/codemode-parity/reference-smoke.ts /absolute/codex-code-mode-host");
const reference = new NativeReference(binary);
try {
  await reference.hello();
  await reference.open("parity-reference");
  const { id, response } = reference.operation({
    method: "session/execute",
    sessionId: "parity-reference",
    request: {
      tool_call_id: "reference-cell",
      enabled_tools: [],
      source: explicitYieldSource,
      yield_time_ms: 1_000,
      max_output_tokens: 1_000,
    },
  });
  const started = await response;
  const initial = await reference.wait((frame) => frame.type === "execute/initialResponse" && frame.id === id);
  assert.equal(initial.result?.status, "ok");
  assert.deepEqual(initial.result.value.Yielded?.content_items, [{ type: "input_text", text: "reference-before" }]);
  const cellId = started.result?.value?.cellId;
  if (typeof cellId !== "string") throw new Error("native host did not return a cell identity");
  const waited = reference.operation({
    method: "session/wait",
    sessionId: "parity-reference",
    request: { cell_id: cellId, yield_time_ms: 1_000 },
  });
  const completed = await waited.response;
  assert.equal(completed.result?.status, "ok");
  const result = completed.result.value.outcome?.LiveCell?.Result;
  assert.equal(result?.error_text, null);
  assert.deepEqual(result.content_items, [{ type: "input_text", text: "reference-after" }]);
  console.log(JSON.stringify({ result: "pass", cases: ["runtime.explicit-yield", "runtime.wait-suffix"], started, initial, completed }));
} finally {
  await reference.stop();
}
