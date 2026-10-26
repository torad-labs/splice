import { describe, expect, test } from "bun:test";
import { createHash } from "node:crypto";
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { compareControlPackets, gatewayInputBytes, protocolSequence } from "./compare";
import { nativeCompletion } from "./native-events";
import { actionItems, isTitleRequest, yieldedCell } from "./scripted-upstream";
import type { CliRun, CliScenario, ObjectValue } from "./types";

const scenario: CliScenario = { schema: 1, id: "guard", actions: [{ kind: "exec", source: 'text("value");' }] };
const hash = (value: string) => createHash("sha256").update(value).digest("hex");
const header = "Script completed\nWall time 0.0 seconds\nOutput:\n";
const input: ObjectValue[] = [
  { type: "custom_tool_call", call_id: "probe-guard-0", name: "exec", input: scenario.actions[0]!.kind === "exec" ? scenario.actions[0]!.source : "" },
  { type: "custom_tool_call_output", call_id: "probe-guard-0", id: "generated-output", output: [{ type: "input_text", text: header }, { type: "input_text", text: "value" }] },
];
const source = (): CliRun => ({
  schema: 1, scenarioId: "guard", scenario, nonce: "a".repeat(32), inputSha256: hash(JSON.stringify(scenario)),
  reference: { cli: { path: "cli", sha256: "b".repeat(64) }, host: { path: "host", sha256: "c".repeat(64) }, sourceRevision: "d".repeat(40) },
  directory: "owned", completion: { sessionId: "native", finalText: "final", sourceFile: "native.jsonl" },
  observations: [{ request: 2, input: structuredClone(input) }], taskRequests: 2, auxiliaryRequests: 0, hostProcesses: [{ pid: 1, sha256: "c".repeat(64) }],
  cliProcess: { pid: 2, sha256: "b".repeat(64), startTicks: "3", exit: 0 },
});
const candidate = (): ObjectValue => ({ nonce: "a".repeat(32), inputSha256: hash(gatewayInputBytes(scenario)), posted: [structuredClone(input)] });

describe("real-client evidence guards", () => {
  test("native completion cannot come from terminal text, aborted turns or a second conversation", () => {
    const directory = mkdtempSync(join(tmpdir(), "codemode-cli-events-"));
    try {
      const sessions = join(directory, "sessions"); mkdirSync(sessions);
      const path = join(sessions, "native.jsonl");
      const rows = [{ type: "session_meta", payload: { id: "native" } }, { type: "event_msg", payload: { type: "task_complete", last_agent_message: "actual" } }];
      writeFileSync(path, rows.map(row => JSON.stringify(row)).join("\n") + "\n");
      expect(nativeCompletion(directory)?.finalText).toBe("actual");
      writeFileSync(path, rows.concat([{ type: "event_msg", payload: { type: "turn_aborted", last_agent_message: "" } }]).map(row => JSON.stringify(row)).join("\n") + "\n");
      expect(nativeCompletion(directory)).toBeNull();
      writeFileSync(path, rows.map(row => JSON.stringify(row)).join("\n") + "\n");
      writeFileSync(join(sessions, "helper.jsonl"), JSON.stringify(rows[1]) + "\n");
      expect(nativeCompletion(directory)).toBeNull();
    } finally { rmSync(directory, { recursive: true }); }
  });

  test("title classification requires both the exact instruction and the sole title schema", () => {
    const body: ObjectValue = { text: { format: { type: "json_schema", schema: { type: "object", properties: { title: { type: "string" } } } } }, input: [{ type: "message", content: [{ text: "ordinary task" }] }] };
    expect(isTitleRequest(body)).toBe(false);
    body.input = [{ type: "message", content: [{ text: "Generate a concise, single-line task title of at most 36 characters for this task" }] }];
    expect(isTitleRequest(body)).toBe(true);
    body.text = { format: { type: "json_schema", schema: { type: "object", properties: { title: { type: "string" }, another: { type: "string" } } } } };
    expect(isTitleRequest(body)).toBe(false);
  });

  test("wait binds the actual primary status rather than a notification or arbitrary later text", () => {
    const yielded = "Script running with cell ID native-7\nWall time 0.0 seconds\nOutput:\n";
    const rows: ObjectValue[] = [
      { type: "custom_tool_call_output", call_id: "exec", output: yielded },
      { type: "custom_tool_call_output", call_id: "exec", name: "exec", output: "notification" },
    ];
    expect(yieldedCell(rows, "exec")).toBe("native-7");
    expect(() => yieldedCell([{ ...rows[0]!, output: [{ type: "input_text", text: header }, { type: "input_text", text: yielded }] }], "exec")).toThrow();
    expect(() => yieldedCell([rows[0]!, rows[0]!], "exec")).toThrow();
    expect(actionItems({ schema: 1, id: "wait", actions: [{ kind: "wait", terminate: true }] }, 0, rows, "exec")[0]?.arguments).toBe('{"cell_id":"native-7","yield_time_ms":1000,"terminate":true}');
  });

  test("caller bytes, output types, result text, sequence and bound input identity remain material", () => {
    expect(() => compareControlPackets(source(), candidate())).not.toThrow();
    const changed = candidate(); changed.nonce = "e".repeat(32);
    expect(() => compareControlPackets(source(), changed)).toThrow();
    const stale = candidate(); stale.inputSha256 = "0".repeat(64);
    expect(() => compareControlPackets(source(), stale)).toThrow();
    for (const replacement of [header, [{ type: "input_text", text: header }, { type: "input_text", text: "wrong" }]]) {
      const value = candidate(); (value.posted as ObjectValue[][])[0]![1]!.output = replacement;
      expect(() => compareControlPackets(source(), value)).toThrow();
    }
    const wrongSource = candidate(); (wrongSource.posted as ObjectValue[][])[0]![0]!.input = 'text("different");';
    expect(() => compareControlPackets(source(), wrongSource)).toThrow();
    const wrongOrder = candidate(); (wrongOrder.posted as ObjectValue[][])[0]!.reverse();
    expect(() => compareControlPackets(source(), wrongOrder)).toThrow();
    const callerItemId = structuredClone(input); callerItemId[0]!.id = "caller-owned-item";
    expect(protocolSequence(callerItemId)).not.toEqual(protocolSequence(input));
  });
});
