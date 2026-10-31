import { expect, test } from "bun:test";
import { createHash } from "node:crypto";
import { assertReplayOwnership } from "./gateway-cases";

const nonce = "0123456789abcdef0123456789abcdef";
function observation(): any {
  const callback = (id: string) => [
    { type: "function_call", call_id: id, name: "Read", arguments: "{}" },
    { type: "function_call_output", call_id: id, output: `result-${nonce}` },
  ];
  const prior = { type: "reasoning", id: `rs-${nonce}`, summary: [], encrypted_content: `opaque-${nonce}` };
  const added = { type: "reasoning", id: `rs-added-${nonce}`, summary: [], encrypted_content: `opaque-added-${nonce}` };
  const baseline = [{ role: "user", content: `begin-${nonce}` }, ...callback("old"), prior,
    { role: "user", content: `continue-${nonce}` }];
  const digest = (item: unknown) => createHash("sha256").update(JSON.stringify(item)).digest("hex");
  const declaration = [added,
    { type: "tool_search_call", call_id: `ts_decl_replay-${nonce}`, execution: "client", arguments: { query: "Read" } },
    { type: "tool_search_output", call_id: `ts_decl_replay-${nonce}`, execution: "client", tools: [
      { type: "function", name: "Read", parameters: { type: "object" } },
    ] },
  ];
  const value: any = { schema: 1, baseline };
  for (const [kind, extra, delivered, classification] of [
    ["callbackOnly", [], false, "NONE"],
    ["unknownReasoningBeforeOwnedCallback", [added], false, "STEERING"],
    ["deliveredReasoningWithValidatedDeclaration", declaration, true, "NONE"],
    ["unknownReasoningWithDeclaration", declaration, false, "STEERING"],
  ] as const) {
    value[kind] = {
      classification,
      input: [...baseline, ...extra, ...callback("one"), ...callback("two")],
      record: {
        id: `record-${nonce}`, key: `parity-${nonce}`, outerCallId: `exec-${nonce}`, source: "source", baselineLogicalCount: 1,
        issued: [{ calls: ["one", "two"].map(id => ({ clientId: id, runtimeId: id, name: "Read", arguments: {} })),
          ...(delivered ? { deliveredNative: [added] } : {}) }],
        pending: [{ clientId: "one" }, { clientId: "two" }],
        results: { one: { output: `result-${nonce}`, isError: false }, two: { output: `result-${nonce}`, isError: false } },
        nativeSegments: [{ logicalOffset: 1, items: [prior] }], continuity: [], continuityReplay: [],
        replayAnchors: { client: { count: baseline.length, digest: createHash("sha256").update(baseline.map(digest).join("")).digest("hex"), acknowledgement: {
          items: [baseline[0], baseline[1], baseline[2], baseline[4]].map((item: any) => ({ digest: digest(item), callbackId: item.type === "function_call_output" ? "old" : null })),
          native: [digest({ type: "reasoning", id: `rs-${nonce}` })],
        } } },
      },
      projection: [{ logicalOffset: 3, items: [prior] }, ...(extra.length ? [{ logicalOffset: 4, callbackId: "one", items: extra }] : [])],
    };
  }
  return structuredClone(value);
}

test("accepts a complete classifier observation with linked raw requests and persisted witnesses", () => {
  expect(() => assertReplayOwnership(observation(), nonce)).not.toThrow();
});
test("rejects the ce1 unknown-reasoning-as-owned observation", () => {
  const value = observation(); value.unknownReasoningBeforeOwnedCallback.classification = "NONE";
  expect(() => assertReplayOwnership(value, nonce)).toThrow();
});
test("rejects missing cases, malformed schemas and manually asserted PASS fields", () => {
  for (const edit of [
    (value: any) => delete value.unknownReasoningWithDeclaration,
    (value: any) => value.schema = "1",
    (value: any) => value.callbackOnly = { pass: true, expected: "NONE" },
    (value: any) => value.callbackOnly.classification = "PASS",
  ]) {
    const value = observation(); edit(value);
    expect(() => assertReplayOwnership(value, nonce)).toThrow();
  }
});
test("rejects missing or invented durable reasoning witnesses", () => {
  const missing = observation(); delete missing.deliveredReasoningWithValidatedDeclaration.record.issued[0].deliveredNative;
  expect(() => assertReplayOwnership(missing, nonce)).toThrow();
  const forged = observation(); forged.deliveredReasoningWithValidatedDeclaration.record.issued[0].deliveredNative[0].id = "unrelated";
  expect(() => assertReplayOwnership(forged, nonce)).toThrow();
  const unowned = observation(); unowned.unknownReasoningWithDeclaration.record.issued[0].deliveredNative =
    unowned.unknownReasoningWithDeclaration.input.filter((item: any) => item.id === `rs-added-${nonce}`);
  expect(() => assertReplayOwnership(unowned, nonce)).toThrow();
});
test("rejects declaration pairs that do not validate and bind to the owned callback", () => {
  for (const edit of [
    (value: any) => value.input[6].call_id = "mismatched-pair",
    (value: any) => value.input[6].execution = "server",
    (value: any) => value.input[7].tools[0].name = "Bash",
    (value: any) => value.projection[1].callbackId = "unowned",
  ]) {
    const value = observation(); edit(value.deliveredReasoningWithValidatedDeclaration);
    expect(() => assertReplayOwnership(value, nonce)).toThrow();
  }
});
test("rejects missing raw reasoning, callback ownership, acknowledgement or accepted result identity", () => {
  for (const edit of [
    (value: any) => value.input.splice(5, 1),
    (value: any) => value.record.issued[0].calls[0].clientId = "unowned",
    (value: any) => delete value.record.replayAnchors.client.acknowledgement,
    (value: any) => value.record.replayAnchors.client.acknowledgement.items[0].digest = "a".repeat(64),
    (value: any) => value.record.baselineLogicalCount = 4,
    (value: any) => value.record.results.one.output = "edited",
  ]) {
    const value = observation(); edit(value.unknownReasoningBeforeOwnedCallback);
    expect(() => assertReplayOwnership(value, nonce)).toThrow();
  }
});
