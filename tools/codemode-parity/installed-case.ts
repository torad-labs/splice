import assert from "node:assert/strict";
import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { artifact, sha256, type Receipt } from "./qualification";

export async function runInstalledCase(receipt: Receipt, options: Map<string, string>, evidence: string): Promise<void> {
  const family = receipt.cases.find(item => item.id === "evidence.installed-identity")!;
  family.findings = [];
  if (!["--installed-pid", "--host-pid", "--jar-fd"].every(name => options.has(name))) {
    family.state = "missing"; family.findings.push("Actual installed daemon PID, native host PID and open jar descriptor are required after installation."); return;
  }
  let observation: any;
  try {
    const pid = Number(options.get("--installed-pid")), hostPid = Number(options.get("--host-pid"));
    const jarFd = options.get("--jar-fd")!;
    assert.ok(Number.isSafeInteger(pid) && pid > 0 && Number.isSafeInteger(hostPid) && hostPid > 0);
    assert.match(jarFd, new RegExp(`^/proc/${pid}/fd/[0-9]+$`));
    const jarSha256 = sha256(await readFile(jarFd));
    const hostExeSha256 = sha256(await readFile(`/proc/${hostPid}/exe`));
    const command = await readFile(`/proc/${pid}/cmdline`);
    const arguments_ = command.toString().split("\0");
    assert.ok(arguments_.some(value => value.includes("splice")) && !arguments_.some(value => value.includes("CodeModeParityProbe")),
      "installed identity must address a Splice daemon, not its diagnostic adapter");
    const ancestry: number[] = [];
    let ancestor = hostPid;
    while (ancestor !== pid && ancestor > 1 && ancestry.length < 12) {
      ancestry.push(ancestor);
      const status = await readFile(`/proc/${ancestor}/status`, "utf8");
      const parent = status.match(/^PPid:\s+(\d+)$/m); assert.ok(parent); ancestor = Number(parent[1]);
    }
    assert.equal(ancestor, pid, "native host is not owned by the installed daemon");
    receipt.installed = { pid, hostPid, jarFd, jarSha256, hostExeSha256 };
    observation = { ...receipt.installed, commandSha256: sha256(command), ancestry, observedAt: new Date().toISOString() };
    assert.equal(jarSha256, receipt.candidateJar.sha256, "installed open jar is not the qualified candidate");
    assert.equal(hostExeSha256, receipt.candidateNativeHost.sha256, "installed native executable is not the qualified candidate");
  } catch (error) { family.findings.push(error instanceof Error ? error.message : String(error)); }
  const path = resolve(evidence, `${receipt.runId}.installed-identity.${Date.now()}.json`);
  await writeFile(path, JSON.stringify({ schema: 1, runId: receipt.runId, caseId: family.id, observation, findings: family.findings }, null, 2) + "\n");
  family.artifacts = [await artifact(path)];
  family.state = family.findings.length ? "failed" : "executed";
}
