import assert from "node:assert/strict";
import { readFileSync, statSync } from "node:fs";
import { resolve } from "node:path";
import { nativeInterruption } from "./native-events";
import { yieldedCell } from "./scripted-upstream";
import type { CliRun, CliScenario, ObjectValue } from "./types";

export const interruptionScenario: CliScenario = { schema: 1, id: "user-interrupt", interruption: {
  notification: "CODEMODE_INTERRUPT_READY", resumePrompt: "Continue the fixed local CodeMode qualification after interrupting the active cell.",
}, actions: [
  { kind: "exec", source: '// @exec: {"yield_time_ms":1}\nstore("interrupted-store","preserved-after-interrupt"); notify("CODEMODE_INTERRUPT_READY"); await new Promise(resolve=>setTimeout(resolve,30000)); text("MUST_NOT_RUN_AFTER_INTERRUPT");' },
  { kind: "exec", source: 'text({tag:"after-interruption",value:load("interrupted-store")});' },
] };

function bounded(path: string): string {
  assert.ok(statSync(path).size <= 16 * 1024 * 1024, "Interruption evidence exceeds its capture bound");
  return readFileSync(path, "utf8");
}

/** A reference user stop is an actual PTY key, native abort and new user turn, not a wait(terminate). */
export function sourceInterruption(source: CliRun): ObjectValue {
  assert.deepEqual(source.scenario, interruptionScenario);
  assert.equal(source.taskRequests, 4); assert.equal(source.observations.length, 4);
  const callId = `probe-${source.scenarioId}-0`, home = resolve(source.directory, "client-state");
  const native = nativeInterruption(home, callId, interruptionScenario.interruption!.notification);
  assert.ok(native?.notified && native.aborted, "The real reference conversation did not notify then abort");
  assert.equal(native.sessionId, source.completion.sessionId); assert.equal(native.sourceFile, source.completion.sourceFile);
  const hold = JSON.parse(bounded(resolve(source.directory, "interruption-hold.json")));
  assert.equal(hold.schema, 1); assert.equal(hold.request, 2);
  assert.equal(hold.cellId, yieldedCell(source.observations[1]!.input, callId));
  assert.ok(Number.isSafeInteger(hold.startedAt) && Number.isSafeInteger(hold.abortedAt) && hold.abortedAt >= hold.startedAt);
  const events = bounded(resolve(source.directory, "terminal/terminal-events.jsonl")).trimEnd().split("\n").map(line => JSON.parse(line));
  const interrupt = events.filter(event => event.kind === "interrupt_after_native_notification");
  const resume = events.filter(event => event.kind === "resume_after_native_abort");
  const submit = events.filter(event => event.kind === "submit_resume_after_native_abort");
  assert.equal(interrupt.length, 1); assert.equal(resume.length, 1); assert.equal(submit.length, 1);
  assert.equal(Buffer.from(interrupt[0]!.raw_base64, "base64").toString(), "\x03");
  assert.equal(Buffer.from(resume[0]!.raw_base64, "base64").toString(), interruptionScenario.interruption!.resumePrompt);
  assert.equal(Buffer.from(submit[0]!.raw_base64, "base64").toString(), "\r");
  assert.ok(hold.startedAt <= interrupt[0]!.at_ms && interrupt[0]!.at_ms <= hold.abortedAt && hold.abortedAt <= resume[0]!.at_ms && resume[0]!.at_ms < submit[0]!.at_ms);
  const resumedInput = source.observations[2]!.input;
  assert.ok(resumedInput.some(item => item.role === "user" && Array.isArray(item.content) && item.content.some(part =>
    part !== null && typeof part === "object" && !Array.isArray(part) && part.text === interruptionScenario.interruption!.resumePrompt)), "The real client did not submit the resumed user message");
  const final = source.observations[3]!.input, result = final.filter(item => item.type === "custom_tool_call_output" && item.call_id === `probe-${source.scenarioId}-1`);
  assert.equal(result.length, 1); assert.ok(Array.isArray(result[0]!.output));
  const output = result[0]!.output as ObjectValue[];
  assert.equal(output.length, 2); assert.equal(output[1]!.text, '{"tag":"after-interruption"}', "Actual native user interruption must clear the interrupted guest state");
  assert.ok(!final.some(item => item.type === "custom_tool_call_output" && JSON.stringify(item.output).includes("MUST_NOT_RUN_AFTER_INTERRUPT")), "Interrupted code continued executing");
  return { notification: interruptionScenario.interruption!.notification, resumePrompt: interruptionScenario.interruption!.resumePrompt, callId };
}

export function compareInterruption(source: CliRun, candidate: ObjectValue): void {
  const expected = sourceInterruption(source);
  assert.deepEqual(candidate.interruption, { ...expected, cancelled: true, resumed: true });
  assert.deepEqual(candidate.outcomes, ["Cancelled", "Success"]);
  assert.ok(Array.isArray(candidate.posted) && candidate.posted.length === 4, "Candidate must cancel its actual post-yield request then continue the same conversation");
}
