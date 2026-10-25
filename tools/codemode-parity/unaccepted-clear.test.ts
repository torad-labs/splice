import { expect, test } from "bun:test";
import { assertUnacceptedClear } from "./gateway-cases";

const nonce = "0123456789abcdef0123456789abcdef";
function observation(): any {
  const record = { id: "retained-durable-record", outerCallId: `outer-${nonce}`, source: "text(await tools.Read({}));",
    updatedAt: 1000,
    results: { owned: { output: "accepted" } }, pending: [], issued: [{ calls: [{ clientId: "owned" }] }],
    nativeSegments: [{ items: [{ type: "reasoning", id: `rs-${nonce}` }] }] };
  const packet = [
    { type: "function_call", call_id: `toolu_splice_unaccepted-clear-${nonce}`, name: "Read", arguments: "{}" },
    { type: "function_call_output", call_id: `toolu_splice_unaccepted-clear-${nonce}`, output: "[Old tool result content cleared]" },
  ];
  return structuredClone({ clientInput: packet, postedInput: packet, beforeRecords: [record], afterRecords: [record],
    callbackId: `toolu_splice_unaccepted-clear-${nonce}`, oldRecordId: record.id, oldOuterCallId: record.outerCallId });
}
test("accepts an actual unknown clear pair preserved upstream with the durable branch retained", () => {
  expect(() => assertUnacceptedClear(observation(), nonce)).not.toThrow();
});
test("rejects missing observations and a clear pair replaced by the old canonical source", () => {
  expect(() => assertUnacceptedClear(undefined, nonce)).toThrow();
  const old = observation(); old.postedInput = [{ type: "custom_tool_call", call_id: old.oldOuterCallId, input: "old source" }];
  expect(() => assertUnacceptedClear(old, nonce)).toThrow();
  const appended = observation(); appended.postedInput.push({ type: "custom_tool_call_output", call_id: appended.oldOuterCallId, output: "old result" });
  expect(() => assertUnacceptedClear(appended, nonce)).toThrow();
});
test("rejects changed callback identity, fabricated clear text or missing raw marker", () => {
  for (const edit of [
    (value: any) => value.callbackId = "different-callback",
    (value: any) => value.clientInput[1].output = "cleared",
    (value: any) => value.postedInput.splice(1, 1),
  ]) {
    const value = observation(); edit(value);
    expect(() => assertUnacceptedClear(value, nonce)).toThrow();
  }
});
test("rejects destroying retained authority or claiming an owned callback was unaccepted", () => {
  const removed = observation(); removed.afterRecords = [];
  expect(() => assertUnacceptedClear(removed, nonce)).toThrow();
  const mutated = observation(); mutated.afterRecords = structuredClone(mutated.beforeRecords); mutated.afterRecords[0].source = "changed";
  expect(() => assertUnacceptedClear(mutated, nonce)).toThrow();
  const owned = observation(); owned.beforeRecords[0].results[owned.callbackId] = { output: "accepted" };
  expect(() => assertUnacceptedClear(owned, nonce)).toThrow();
  const clock = observation(); clock.afterRecords = structuredClone(clock.beforeRecords); clock.afterRecords[0].updatedAt = 999;
  expect(() => assertUnacceptedClear(clock, nonce)).toThrow();
});
