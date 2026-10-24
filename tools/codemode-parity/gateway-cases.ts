import assert from "node:assert/strict";
import { mkdir, readdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { artifact, type CaseEvidence, type CaseId } from "./qualification";

type Observation = { schema: number; caseId: string; nonce: string; phase: string; pid: number; scope: string; observed: any };
const user = (content: string) => ({ role: "user", content });
const assistant = (content: string) => ({ role: "assistant", content });
const native = (nonce: string) => [
  { type: "reasoning", id: `rs-${nonce}`, summary: [], encrypted_content: `opaque-${nonce}` },
  assistant("Same words. "), assistant("Same words."),
];
const nextModel = (nonce: string) => [user(`begin-${nonce}`), ...native(nonce), user(`next-${nonce}`)];
function validateHistory(observed: any, nonce: string): void {
  assert.deepEqual(observed.initialClient, [user(`begin-${nonce}`)]);
  assert.deepEqual(observed.nativeResponse, native(nonce));
  assert.deepEqual(observed.nextClient, [user(`begin-${nonce}`), assistant("Same words. Same words."), user(`next-${nonce}`)]);
  assert.deepEqual(observed.nextModelInput, nextModel(nonce), "merged display text must acknowledge exact native items without reconstructing them");
}
const drivers: Array<{ id: CaseId; phases: string[]; fullFamily: boolean; validate(values: Observation[], nonce: string): void }> = [
  { id: "history.append-only", phases: ["single"], fullFamily: true, validate(values, nonce) { validateHistory(values[0]!.observed, nonce); } },
  { id: "history.replay-is-not-steering", phases: ["single"], fullFamily: true, validate(values, nonce) {
    const observed = values[0]!.observed;
    assert.deepEqual(observed.accepted, [{ id: observed.callbackId, output: `accepted-client-result-${nonce}`, isError: false }]);
    assert.ok(observed.postedInput.some((item: any) => item.id === `rs-${nonce}` && item.encrypted_content === `opaque-${nonce}`));
    assert.ok(observed.postedInput.some((item: any) => item.type === "custom_tool_call" && item.call_id === `outer-${nonce}`));
    assert.ok(observed.postedInput.some((item: any) => item.type === "custom_tool_call_output" && JSON.stringify(item).includes(`accepted-native-output-${nonce}`)));
    assert.equal(observed.postedInput.some((item: any) => item.call_id === observed.callbackId), false);
    assert.equal(JSON.stringify(observed.postedInput).includes("Script terminated"), false);
    assert.equal(observed.records.length, 1); assert.equal(observed.records[0].phase, "COMPLETED");
  } },
  { id: "history.real-steering", phases: ["single"], fullFamily: true, validate(values, nonce) {
    const observed = values[0]!.observed;
    assert.ok(observed.newUser.postedInput.some((item: any) => item.role === "user" && item.content === `steer-${nonce}`));
    assert.ok(JSON.stringify(observed.newUser.records).includes("Script terminated"), "real new user steering must interrupt the pending source");
    const edited = observed.editedOldResult;
    assert.ok(edited.postedInput.some((item: any) => item.type === "function_call_output" && item.call_id === edited.callbackId && item.output === `edited-client-result-${nonce}`));
    assert.equal(edited.postedInput.some((item: any) => item.call_id === `outer-${nonce}` || item.id === `rs-${nonce}`), false,
      "edited accepted result must fork rather than recover another canonical script");
    assert.ok(observed.rewind.postedInput.some((item: any) => item.role === "user" && item.content === `start-${nonce}`));
    assert.equal(observed.rewind.postedInput.some((item: any) => item.role === "user" && item.content === `branch-${nonce}`), false);
    assert.equal(observed.compaction.compaction, true, "actual prepared turn must carry the compaction boundary");
    assert.ok(observed.compaction.postedInput.some((item: any) => item.role === "user" && item.content === `You have been tasked with summarizing conversations. Summary-${nonce}`));
    for (const variant of ["rewind", "compaction"]) assert.equal(observed[variant].postedInput.some((item: any) =>
      item.call_id === `outer-${nonce}` || item.id === `rs-${nonce}`), false, `${variant} retained native authority from the abandoned branch`);
  } },
  { id: "history.restart", phases: ["write", "read"], fullFamily: false, validate(values, nonce) {
    assert.notEqual(values[0]!.pid, values[1]!.pid, "restart fixture must execute in different JVMs");
    for (const value of values) {
      validateHistory(value.observed.history, nonce);
      assert.deepEqual(value.observed.unknownCallbackIds, []);
      assert.equal(value.observed.records.length, 1);
      const record = value.observed.records[0];
      assert.equal(record.id, `record-${nonce}`);
      assert.equal(record.results.one.output, `result-${nonce}`); assert.equal(record.results.two.output, `result-${nonce}`);
      assert.deepEqual(record.issued[0].calls.map((call: any) => call.clientId), ["one", "two"]);
    }
    for (const field of ["id", "key", "source", "results", "issued", "replayAnchors", "nativeSegments"]) {
      assert.deepEqual(values[1]!.observed.records[0][field], values[0]!.observed.records[0][field], `restart must preserve exact ${field}`);
    }
  } },
  { id: "history.cleared-results", phases: ["single"], fullFamily: true, validate(values, nonce) {
    const observed = values[0]!.observed;
    assert.deepEqual(observed.postedInput, [...observed.canonical, user(`continue-${nonce}`)]);
    assert.ok(observed.canonical.some((item: any) => item.type === "reasoning" && item.encrypted_content === `opaque-${nonce}`));
    assert.ok(observed.canonical.some((item: any) => item.type === "custom_tool_call" && item.call_id === `outer-${nonce}`));
    assert.ok(observed.canonical.some((item: any) => item.type === "custom_tool_call_output" && JSON.stringify(item).includes(`accepted-native-output-${nonce}`)));
    assert.equal(observed.postedInput.some((item: any) => item.call_id === observed.callbackId), false);
    assert.equal(JSON.stringify(observed.postedInput).includes("[Old tool result content cleared]"), false);
    assert.deepEqual(observed.accepted, [{ id: observed.callbackId, output: `accepted-client-result-${nonce}`, isError: false }]);
  } },
];
async function stateArtifacts(directory: string): Promise<string[]> {
  const paths: string[] = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = resolve(directory, entry.name);
    if (entry.isDirectory()) paths.push(...await stateArtifacts(path)); else if (entry.isFile()) paths.push(path);
  }
  return paths;
}
export async function runGatewayCases(java: string, jar: string, evidence: string, runId: string, cases: CaseEvidence[]): Promise<void> {
  for (const driver of drivers) {
    const item = cases.find(candidate => candidate.id === driver.id)!;
    item.findings = [];
    const stateDirectory = resolve(evidence, `${runId}.${driver.id}.state`);
    await mkdir(stateDirectory, { recursive: true });
    const values: Observation[] = [];
    try {
      for (const phase of driver.phases) {
        const command = [java, "-cp", jar, "splice.app.diagnostics.CodeModeParityProbe", "--gateway-fixture", driver.id, runId, stateDirectory, phase];
        const started = performance.now();
        const child = Bun.spawn(command, { stdout: "pipe", stderr: "pipe" });
        const timer = setTimeout(() => child.kill("SIGKILL"), 15_000);
        let stdout = "", stderr = "", exitCode = -1;
        try { [stdout, stderr, exitCode] = await Promise.all([new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited]); }
        finally { clearTimeout(timer); }
        const path = resolve(evidence, `${runId}.${driver.id}.${phase}.json`);
        await writeFile(path, JSON.stringify({ schema: 1, runId, caseId: driver.id, command, elapsedMs: performance.now() - started,
          processPid: child.pid, exitCode, stdout, stderr }, null, 2) + "\n");
        item.artifacts.push(await artifact(path));
        assert.equal(exitCode, 0, stderr || "gateway diagnostic failed");
        const value: Observation = JSON.parse(stdout);
        assert.equal(value.schema, 1); assert.equal(value.caseId, driver.id); assert.equal(value.nonce, runId); assert.equal(value.phase, phase);
        assert.equal(value.pid, child.pid); assert.equal(value.scope, "production-provider-components");
        values.push(value);
      }
      driver.validate(values, runId);
      for (const path of await stateArtifacts(stateDirectory)) item.artifacts.push(await artifact(path));
      if (!driver.fullFamily) item.findings.push("Provider components survived a real JVM restart; daemon/client restart recovery remains unexecuted.");
    } catch (error) { item.findings.push(error instanceof Error ? error.message : String(error)); }
    item.state = item.findings.length ? "failed" : "executed";
    console.log(`${item.state === "executed" ? "EXECUTED" : "UNQUALIFIED"} ${item.id}`);
  }
}
