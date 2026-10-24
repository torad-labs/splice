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
  { id: "history.real-steering", phases: ["single"], fullFamily: false, validate(values, nonce) {
    const observed = values[0]!.observed;
    for (const variant of ["newUser", "editedOldResult", "unknownReasoning"]) assert.equal(observed[variant].classification, "STEERING", variant);
    assert.deepEqual(observed.newUser.client.at(-1), user(`steer-${nonce}`));
    assert.ok(observed.editedOldResult.client.some((item: any) => item.call_id === "old" && item.output === `edited-${nonce}`));
    const branches = observed.historyBranches;
    assert.deepEqual(branches.newUserModel, [...nextModel(nonce), user(`steer-${nonce}`)]);
    for (const variant of ["edited", "rewind", "compacted"]) assert.deepEqual(branches[`${variant}Model`], branches[`${variant}Client`], `${variant} must create its intended history boundary`);
    assert.deepEqual(branches.editedModel, [user(`edited-${nonce}`)]);
    assert.deepEqual(branches.rewindModel, []);
    assert.deepEqual(branches.compactedModel, [user(`compact-summary-${nonce}`)]);
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
      if (!driver.fullFamily) item.findings.push({
        "history.restart": "Provider components survived a real JVM restart; daemon/client restart recovery remains unexecuted.",
        "history.replay-is-not-steering": "Classifier accepted callback-only replay; captured splice-lead script resumption remains unexecuted.",
        "history.real-steering": "History replacement boundaries ran; actual compaction/rewind/edited-result routing remains unexecuted.",
        "history.cleared-results": "Supplied authorization classification ran; actual session witness and retained canonical chain remain unexecuted.",
      }[driver.id as "history.restart"] ?? "Required integration dimensions remain unexecuted.");
    } catch (error) { item.findings.push(error instanceof Error ? error.message : String(error)); }
    item.state = item.findings.length ? "failed" : "executed";
    console.log(`${item.state === "executed" ? "EXECUTED" : "UNQUALIFIED"} ${item.id}`);
  }
}
