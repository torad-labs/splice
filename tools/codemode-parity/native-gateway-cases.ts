import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { artifact, type CaseEvidence } from "./qualification";
import { candidateCommand } from "./candidate-command";

const families = ["runtime.session-store", "runtime.wait-suffix"] as const;
function output(post: any[], callId: string): any[] {
  const matches = post.filter(item => ["function_call_output", "custom_tool_call_output"].includes(item.type) && item.call_id === callId);
  assert.equal(matches.length, 1, `native gateway must retain exactly one actual output for ${callId}`);
  assert.ok(Array.isArray(matches[0].output), "native gateway discarded typed model output");
  return matches[0].output;
}
function body(items: any[]): string[] {
  assert.equal(items[0].type, "input_text");
  assert.match(items[0].text, /^Script (?:completed|running with cell ID)/);
  return items.slice(1).filter(item => item.type === "input_text").map(item => item.text);
}
export async function runNativeGatewayCases(java: string, jar: string, host: string, evidence: string, runId: string, cases: CaseEvidence[]): Promise<void> {
  for (const id of families) {
    const family = cases.find(item => item.id === id)!;
    const directory = resolve(evidence, `${runId}.${id}.native-gateway`);
    await mkdir(directory, { recursive: true });
    const argv = candidateCommand(java, jar, "--native-gateway-fixture", id, runId, directory, host);
    const child = spawn(java, argv.slice(1), { stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "", stderr = "";
    child.stdout.on("data", bytes => { stdout += bytes.toString(); }); child.stderr.on("data", bytes => { stderr += bytes.toString(); });
    const timer = setTimeout(() => child.kill("SIGKILL"), 20_000);
    try {
      const exitCode = await new Promise<number | null>((resolve, reject) => { child.once("error", reject); child.once("close", resolve); });
      const path = resolve(directory, "observed.json");
      await writeFile(path, JSON.stringify({ schema: 1, runId, caseId: id, argv, exitCode, stdout, stderr }, null, 2) + "\n");
      family.artifacts.push(await artifact(path));
      assert.equal(exitCode, 0, stderr);
      const value = JSON.parse(stdout);
      assert.equal(value.schema, 1); assert.equal(value.caseId, id); assert.equal(value.nonce, runId); assert.equal(value.pid, child.pid);
      assert.equal(value.scope, "real-native-runtime-through-production-gateway"); assert.equal(value.retentionPerConversation, 1);
      assert.equal(value.outcome.kind, "Success", JSON.stringify(value.outcome));
      const posted = value.posted.at(-1);
      if (id === "runtime.session-store") {
        assert.deepEqual(body(output(posted, `store-${runId}`)), ["native-gateway-stored"]);
        assert.deepEqual(body(output(posted, `load-${runId}`)), ["kept"], "record retention destroyed the native session store");
      } else {
        const first = output(posted, `yield-${runId}`);
        assert.match(first[0].text, /^Script running with cell ID \S+\n/);
        assert.deepEqual(body(first), ["native-gateway-prefix"]);
        assert.deepEqual(body(output(posted, `sibling-${runId}`)), ["native-gateway-sibling"]);
        assert.deepEqual(body(output(posted, `suffix-${runId}`)), ["native-gateway-suffix"], "a sibling admission lost the live background cell or repeated its prefix");
        assert.deepEqual(body(output(posted, `exhausted-${runId}`)), [], "exhausted gateway wait reran or lost the completed native receipt");
      }
    } catch (error) { family.findings.push(`Real native gateway: ${error instanceof Error ? error.message : String(error)}`); }
    finally { clearTimeout(timer); }
    family.state = family.findings.length ? "failed" : "executed";
    console.log(`${family.state === "executed" ? "EXECUTED" : "UNQUALIFIED"} ${id} (real native gateway retention/wait)`);
  }
}
