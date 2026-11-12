import { expect, test } from "bun:test";
import { createHash } from "node:crypto";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { resolve } from "node:path";
import { candidateOutcomesMatch, gatewayInput, sourceGatewayInputBytes } from "./compare";
import { compareInterruption, interruptionScenario, sourceInterruption } from "./interruption-contract";
import type { CliRun, ObjectValue } from "./types";

function fixture() {
  const directory = mkdtempSync(resolve(tmpdir(), "codemode-interruption-contract-"));
  mkdirSync(resolve(directory, "client-state/sessions"), { recursive: true }); mkdirSync(resolve(directory, "terminal"));
  const callId = "probe-user-interrupt-1", notification = interruptionScenario.interruption!.notification;
  const sourceFile = resolve(directory, "client-state/sessions/owned.jsonl");
  const rows = [{ type: "session_meta", payload: { id: "owned" } },
    { type: "response_item", payload: { type: "custom_tool_call", name: "exec", call_id: callId, input: (interruptionScenario.actions[0] as { source: string }).source } },
    { type: "response_item", payload: { type: "custom_tool_call_output", name: "exec", call_id: callId, output: notification } },
    { type: "event_msg", payload: { type: "turn_aborted" } }];
  writeFileSync(sourceFile, rows.map(row => JSON.stringify(row)).join("\n") + "\n");
  const hold = { schema: 1, request: 3, cellId: "7", startedAt: 1, abortedAt: 3 };
  writeFileSync(resolve(directory, "interruption-hold.json"), JSON.stringify(hold));
  const events = [
    { kind: "interrupt_after_native_notification", at_ms: 2, raw_base64: Buffer.from("\x03").toString("base64") },
    { kind: "resume_after_native_abort", at_ms: 4, raw_base64: Buffer.from(interruptionScenario.interruption!.resumePrompt).toString("base64") },
    { kind: "submit_resume_after_native_abort", at_ms: 5, raw_base64: Buffer.from("\r").toString("base64") },
  ];
  writeFileSync(resolve(directory, "terminal/terminal-events.jsonl"), events.map(event => JSON.stringify(event)).join("\n") + "\n");
  const yielded: ObjectValue = { type: "custom_tool_call_output", call_id: callId, output: "Script running with cell ID 7\nWall time 0.0 seconds\nOutput:\n" };
  const source: CliRun = { schema: 1, scenarioId: "user-interrupt", scenario: structuredClone(interruptionScenario), nonce: "a".repeat(32),
    inputSha256: createHash("sha256").update(JSON.stringify(interruptionScenario)).digest("hex"), directory,
    reference: { sourceRevision: "d".repeat(40), cli: { path: "cli", sha256: "b".repeat(64) }, host: { path: "host", sha256: "c".repeat(64) } },
    completion: { sessionId: "owned", sourceFile, finalText: "final" }, taskRequests: 5, auxiliaryRequests: 0,
    cliProcess: { pid: 1, sha256: "b".repeat(64), startTicks: "2", exit: 0 }, hostProcesses: [{ pid: 3, sha256: "c".repeat(64) }],
    observations: [{ request: 1, input: [] }, { request: 2, input: [] }, { request: 3, input: [yielded] },
      { request: 4, input: [yielded, { role: "user", content: [{ type: "input_text", text: interruptionScenario.interruption!.resumePrompt }] }] },
      { request: 5, input: [yielded, { type: "custom_tool_call_output", call_id: "probe-user-interrupt-2", output: [
        { type: "input_text", text: "Script completed\nWall time 0.0 seconds\nOutput:\n" }, { type: "input_text", text: '{"tag":"after-interruption","stable":"completed-before-interruption"}' },
      ] }] }],
  };
  return { directory, source, sourceFile, rows, events, hold, close: () => rmSync(directory, { recursive: true, force: true }) };
}

test("a user-interrupt plan requires the actual owned PTY abort and resumed user turn", () => {
  const owned = fixture();
  try {
    expect(sourceInterruption(owned.source)).toEqual({ ...interruptionScenario.interruption!, callId: "probe-user-interrupt-1" });
    expect(JSON.parse(sourceGatewayInputBytes(owned.source)).interruption).toEqual(sourceInterruption(owned.source));
    expect(() => gatewayInput(interruptionScenario)).toThrow("PTY");
    const session = `session-${owned.source.nonce}`, conversation = `parity-${owned.source.nonce}`;
    const candidate: ObjectValue = { outcomes: ["Cancelled", "Success"], posted: owned.source.observations.map(value => value.input),
      nativeNotifications: [interruptionScenario.interruption!.notification],
      interruption: { ...sourceInterruption(owned.source), cancelled: true, resumed: true },
      interruptControl: { boundary: "authenticated-production-control-http", method: "POST",
        path: `/api/heads/native-interrupt-probe/sessions/${session}/interrupt`, request: { conversation_key: conversation },
        anonymousStatus: 401, sessionKeyStatus: 401, status: 200,
        response: { head: "native-interrupt-probe", session, conversation_key: conversation,
          cells: [{ cell_id: "7", terminated: true, failure: null }] } } };
    expect(candidateOutcomesMatch(owned.source, candidate)).toBe(true);
    expect(() => compareInterruption(owned.source, candidate)).not.toThrow();
    const receipt = candidate.interruptControl as ObjectValue, response = receipt.response as ObjectValue;
    for (const forged of [
      undefined,
      { ...receipt, boundary: "direct-bridge" },
      { ...receipt, method: "GET" },
      { ...receipt, path: `${receipt.path}/another` },
      { ...receipt, request: { conversation_key: "another" } },
      { ...receipt, anonymousStatus: 200 },
      { ...receipt, sessionKeyStatus: 200 },
      { ...receipt, status: 409 },
      { ...receipt, response: { ...response, head: "another" } },
      { ...receipt, response: { ...response, session: "another" } },
      { ...receipt, response: { ...response, conversation_key: "another" } },
      { ...receipt, response: { ...response, cells: [] } },
      { ...receipt, response: { ...response, cells: [{ cell_id: "another", terminated: true, failure: null }] } },
      { ...receipt, response: { ...response, cells: [{ cell_id: "7", terminated: false, failure: null }] } },
      { ...receipt, response: { ...response, cells: [{ cell_id: "7", terminated: true, failure: "termination failed" }] } },
    ]) {
      const changed = { ...candidate, interruptControl: forged };
      expect(() => compareInterruption(owned.source, changed as ObjectValue)).toThrow("authenticated production HTTP route");
    }
    candidate.outcomes = ["Success"]; expect(() => compareInterruption(owned.source, candidate)).toThrow();
    expect(candidateOutcomesMatch({ ...owned.source, scenario: { ...owned.source.scenario, interruption: undefined } }, { outcomes: ["Cancelled", "Success"] })).toBe(false);
  } finally { owned.close(); }
});

test("a wait termination, missing native abort or unsubmitted prompt cannot certify user interruption", () => {
  const owned = fixture();
  try {
    writeFileSync(owned.sourceFile, owned.rows.slice(0, -1).map(row => JSON.stringify(row)).join("\n") + "\n");
    expect(() => sourceInterruption(owned.source)).toThrow("abort");
    writeFileSync(owned.sourceFile, owned.rows.map(row => JSON.stringify(row)).join("\n") + "\n");
    writeFileSync(resolve(owned.directory, "terminal/terminal-events.jsonl"), owned.events.slice(0, -1).map(row => JSON.stringify(row)).join("\n") + "\n");
    expect(() => sourceInterruption(owned.source)).toThrow();
    writeFileSync(resolve(owned.directory, "terminal/terminal-events.jsonl"), owned.events.map(row => JSON.stringify(row)).join("\n") + "\n");
    owned.source.observations[3]!.input = []; expect(() => sourceInterruption(owned.source)).toThrow("resumed user message");
  } finally { owned.close(); }
});

test("interruption discards unfinished writes while preserving previously completed writes", () => {
  const owned = fixture();
  try {
    const output = owned.source.observations[4]!.input[1]!.output as ObjectValue[];
    for (const text of ['{"tag":"after-interruption"}', '{"tag":"after-interruption","stable":"completed-before-interruption","value":"uncommitted-before-interruption"}']) {
      output[1]!.text = text; expect(() => sourceInterruption(owned.source)).toThrow("completed-cell storage");
    }
  } finally { owned.close(); }
});
