import assert from "node:assert/strict";
import { readFile, readdir, writeFile, mkdir } from "node:fs/promises";
import { resolve } from "node:path";
import { interactiveScenario } from "./interactive-gateway";
import { assertNativeTypedMedia } from "./interactive-output";
import { assertNativeBatch } from "./interactive-batch";
import { artifact, identity, type CaseEvidence, type CaseId } from "./qualification";

const summary = "PROBE_READABLE_REASONING: inspect the callback file, then run the Bash tool exactly once.";
const envelope = "synthetic-probe-reasoning-envelope-not-a-real-credential";
const final = "PROBE_FINISHED";
type ScenarioName = "success" | "late-failure" | "native-lifecycle" | "restart" | "native-batch";
async function allFiles(directory: string): Promise<string[]> {
  const result: string[] = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    if (entry.name === "plugins" || entry.name === ".git" || entry.name.endsWith(".lock")) continue;
    const path = resolve(directory, entry.name);
    if (entry.isDirectory()) result.push(...await allFiles(path));
    else if (entry.isFile() && /\.(?:json|jsonl|log|txt)$/.test(entry.name)) result.push(path);
  }
  return result;
}
function epoch(result: any, event: string): number {
  const value = result.marks.find((mark: any) => mark.event === event)?.epochMs;
  assert.ok(Number.isFinite(value), `missing raw ${event} timestamp`); return value;
}
function modelOutputs(result: any): any[] {
  const latest = new Map<string, any>();
  for (const request of result.upstreamRequests) for (const item of request.nativeInput) {
    if (["custom_tool_call_output", "function_call_output"].includes(item.type)) latest.set(`${item.type}:${item.call_id}`, item);
  }
  return [...latest.values()];
}
async function validate(result: any, root: string): Promise<void> {
  assert.equal(result.errors.length, 0, result.errors.join("\n"));
  assert.equal(result.cliExit, 0);
  assert.ok(resolve(result.taskDir).startsWith(root + "/"));
  const effects = (await readFile(resolve(result.taskDir, "effects.txt"), "utf8")).trim().split("\n").map(Number);
  assert.equal(effects.length, 1, "accepted effects must survive all faults exactly once"); assert.ok(Number.isFinite(effects[0]));
  assert.deepEqual(result.callbackTimes, effects);
  assert.ok(effects[0]! >= epoch(result, "exec_item_done"), "source prefix executed before whole-item admission");
  const terminal = result.marks.find((mark: any) => ["response_completed_sent", "late_failure_sent"].includes(mark.event))?.epochMs;
  assert.ok(Number.isFinite(terminal));
  if (result.scenario === "native-lifecycle") {
    assert.ok(effects[0]! >= epoch(result, "wait_item_done"), "native callback must follow its model wait handoff");
  } else assert.ok(effects[0]! < terminal, "whole-item callback was held until response terminal");
  const frames = result.marks.filter((mark: any) => mark.event === "client_sse").map((mark: any) => mark.frame);
  const thinking = frames.filter((frame: any) => frame.type === "content_block_delta" && frame.delta?.type === "thinking_delta")
    .map((frame: any) => frame.delta.thinking).join("");
  assert.equal(thinking, summary, "readable wire reasoning must preserve every character exactly once");
  assert.equal(frames.some((frame: any) => frame.content_block?.type === "redacted_thinking"), false);
  const transcriptFiles = (await allFiles(resolve(root, result.scenario, "claude", "projects"))).filter(path => path.endsWith(".jsonl"));
  const assistants = new Map<string, any>();
  for (const path of transcriptFiles) for (const line of (await readFile(path, "utf8")).split("\n")) {
    if (!line) continue;
    const row = JSON.parse(line); if (row.type === "assistant" && row.uuid) assistants.set(row.uuid, row);
  }
  const blocks = [...assistants.values()].flatMap(row => row.message?.content ?? []);
  assert.equal(blocks.filter(block => block.type === "thinking").map(block => block.thinking ?? "").join(""), summary,
    "actual interactive client persisted duplicate or missing readable reasoning");
  assert.equal(blocks.some(block => block.type === "redacted_thinking"), false);
  assert.ok(blocks.some(block => block.type === "text" && block.text?.includes(final)));
  const terminalLog = await readFile(resolve(root, result.scenario, "client-terminal.log"), "utf8");
  assert.ok(terminalLog.includes(final)); assert.ok(terminalLog.includes(summary));
  assert.ok(result.upstreamRequests.some((request: any) => request.nativeInput.some((item: any) => item.type === "reasoning" && item.encrypted_content === envelope)),
    "encrypted native reasoning must reach model history");
  assert.equal(frames.some((frame: any) => JSON.stringify(frame).includes(envelope)), false, "encrypted reasoning leaked to client output");
}
export async function runInteractiveCases(jar: string, evidence: string, runId: string, cases: CaseEvidence[]): Promise<void> {
  const root = resolve(evidence, `${runId}.interactive`);
  await mkdir(root, { recursive: false });
  const expectedJar = await identity(jar);
  const results = new Map<ScenarioName, any>();
  const rawArtifacts: CaseEvidence["artifacts"] = [];
  rawArtifacts.push(await artifact("/home/marcos/.local/share/claude/versions/2.1.310"));
  const failures = new Map<ScenarioName, string>();
  for (const name of ["success", "late-failure", "native-lifecycle", "restart", "native-batch"] as const) {
    try {
      const result = await interactiveScenario(jar, name, root);
      const path = resolve(root, `${name}.observed.json`);
      // Result booleans are diagnostic only; the assertions above inspect raw
      // SSE, actual PTY transcript, effect file and model request items afresh.
      await writeFile(path, JSON.stringify({ schema: 1, runId, candidateJar: expectedJar, result }, null, 2) + "\n");
      rawArtifacts.push(await artifact(path)); results.set(name, result);
      await validate(result, root);
      assert.equal((await identity(jar)).sha256, expectedJar.sha256, "candidate changed during interactive execution");
    } catch (error) { failures.set(name, error instanceof Error ? error.message : String(error)); }
  }
  for (const path of await allFiles(root)) rawArtifacts.push(await artifact(path));
  const record = (id: CaseId, names: ScenarioName[], check: () => void) => {
    const item = cases.find(candidate => candidate.id === id)!;
    const previous = id === "history.restart" ? item.findings.filter(value => !value.startsWith("Provider components survived")) : [];
    item.findings = [...previous, ...names.flatMap(name => failures.has(name) ? [`${name}: ${failures.get(name)}`] : [])];
    item.artifacts.push(...rawArtifacts);
    if (!item.findings.length) try { check(); } catch (error) { item.findings.push(error instanceof Error ? error.message : String(error)); }
    item.state = item.findings.length ? "failed" : "executed";
    console.log(`${item.state === "executed" ? "EXECUTED" : "UNQUALIFIED"} ${id}`);
  };
  record("runtime.item-boundary", ["success", "late-failure", "native-batch"], () => {
    assertNativeBatch(results.get("native-batch")!);
  });
  record("delivery.readable-reasoning", ["success", "late-failure", "restart"], () => {});
  record("delivery.interactive-cadence", ["success", "native-lifecycle"], () => {
    const value = results.get("success")!;
    const itemDone = epoch(value, "exec_item_done"), opened = epoch(value, "tool_open_on_client_wire");
    assert.ok(opened >= itemDone && opened - itemDone < 1000, "gateway item-to-callback local hold exceeded one second");
    const stopped = value.marks.find((mark: any) => mark.event === "client_sse" && mark.frame?.type === "message_stop" && mark.epochMs >= opened)?.epochMs;
    assert.ok(Number.isFinite(stopped) && stopped - opened < 1000, "callback message_stop held the handoff");
    assert.ok(value.timing.terminalThinkingAt !== null && value.timing.terminalThinkingAt < value.timing.terminalAt);
    const lifecycle = results.get("native-lifecycle")!;
    assert.ok(lifecycle.marks.some((mark: any) => mark.event === "wait_item_done"));
    const outputs = modelOutputs(lifecycle);
    assert.ok(outputs.some(item => item.call_id === "call_probe" && item.output === "native-progress"), "native notification lost its original call ownership");
    const waited = outputs.filter(item => item.type === "function_call_output" && /^wait_call_/.test(item.call_id)).at(-1);
    assert.ok(Array.isArray(waited?.output), "real native wait never produced its typed model output");
    assert.match(waited.output[0]?.text ?? "", /^Script completed\n/, "native wait was steered/terminated instead of completed");
    assert.ok(waited.output.some((item: any) => item.type === "input_text" && item.text === "native-suffix"), "native suffix never executed after the callback");
    assert.equal(waited.output.some((item: any) => item.type === "input_text" && item.text === "native-prefix"), false, "wait repeated the already yielded prefix");
  });
  const restartCheck = () => {
    const value = results.get("restart")!;
    const boots = value.marks.filter((mark: any) => mark.event === "daemon_ready");
    assert.ok(boots.length >= 2 && boots[0].pid !== boots[1].pid, "restart must replace the actual daemon process");
    assert.ok(epoch(value, "callback_delivered_after_restart") >= epoch(value, "restart_before_callback_delivery"));
    const after = epoch(value, "callback_delivered_after_restart");
    assert.ok(value.upstreamRequests.some((request: any) => request.epochMs >= after && request.nativeInput.some((item: any) =>
      item.type === "reasoning" && item.encrypted_content === envelope)), "daemon restart lost exact native model reasoning");
  };
  record("history.restart", ["restart"], restartCheck);
  record("delivery.late-failure-recovery", ["late-failure", "restart"], () => {
    restartCheck();
    const value = results.get("restart")!;
    assert.ok(epoch(value, "late_failure_sent") <= epoch(value, "restart_before_callback_delivery"), "combined failure→restart recovery was not executed");
  });
  const output = cases.find(item => item.id === "runtime.output-content")!;
  const priorOutputFindings = output.findings.filter(value => !value.startsWith("Native host media and executed upstream formatter agree"));
  const persistence = cases.find(item => item.id === "history.cleared-results")!;
  output.findings = [...priorOutputFindings, ...persistence.findings, ...(failures.has("native-lifecycle") ? [failures.get("native-lifecycle")!] : [])];
  output.artifacts.push(...persistence.artifacts, ...rawArtifacts);
  if (!output.findings.length) try {
    assertNativeTypedMedia(results.get("native-lifecycle")!.upstreamRequests);
  } catch (error) { output.findings.push(error instanceof Error ? error.message : String(error)); }
  output.state = output.findings.length ? "failed" : "executed";
  console.log(`${output.state === "executed" ? "EXECUTED" : "UNQUALIFIED"} ${output.id}`);
}
