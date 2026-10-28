import { randomBytes } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { artifact, identity, type Artifact, type Identity } from "./qualification";
import type { CurrentInventory } from "./current-inventory";
import type { CurrentReference } from "./current-readiness";
import type { ExecutedVerifier } from "./current-coverage";
import { executeSourceCliWitnesses, sourceCliCaseArtifacts, sourceCliWitnessFindings } from "./current-cli-source-profile";

/** Actual source counter execution is additional to the signed release controls. */
export async function runCurrentSourceCli(options: { java: string; jar: Identity; nativeHost: Identity; lock: CurrentReference; inventory: CurrentInventory; build: Artifact; evidence: string; runId: string }): Promise<ExecutedVerifier> {
  const { jar, nativeHost, lock, inventory, runId } = options;
  const execution: ExecutedVerifier = { id: "current.core-gateway", runId, startedAt: new Date().toISOString(), finishedAt: "", inventorySha256: inventory.denominatorSha256,
    sourceRevision: lock.sourceRevision, candidateJarSha256: jar.sha256, nativeHostSha256: nativeHost.sha256, state: "failed", findings: [], artifacts: [], cases: [],
    known_soft_spots: ["Source function-entry counters prove named calls, not exhaustive branch or scheduler coverage. Typed MCP bridge cases do not establish that ordinary Claude frontend text callbacks preserve absent raw MCP metadata."] };
  try {
    const cases = await executeSourceCliWitnesses(options);
    execution.cases = cases;
    execution.artifacts = [...new Map(cases.flatMap(sourceCliCaseArtifacts).map(proof => [proof.path, proof])).values()];
    execution.findings.push(...await sourceCliWitnessFindings(cases, inventory, runId, jar, lock));
    if (!execution.findings.length) execution.state = "executed";
  } catch (error) { execution.findings.push(error instanceof Error ? error.message : String(error)); }
  execution.finishedAt = new Date().toISOString();
  const path = resolve(options.evidence, `${runId}.current-core-gateway.execution.json`);
  await mkdir(options.evidence, { recursive: true }); await writeFile(path, JSON.stringify(execution, null, 2) + "\n");
  execution.artifacts.push(await artifact(path));
  return execution;
}

// Development capture can design exact plans before the full ledger is closed.
// This creates an execution record, never a qualification receipt or readiness PASS.
if (import.meta.main) {
  const args = process.argv.slice(2), options = new Map<string, string>();
  if (args.length % 2) throw new Error("arguments must be --name VALUE pairs");
  for (let index = 0; index < args.length; index += 2) {
    const name = args[index]!, value = args[index + 1]!;
    if (!["--java", "--candidate-jar", "--candidate-host", "--source-cli-build", "--evidence"].includes(name) || options.has(name)) throw new Error(`unknown or duplicate argument: ${name}`);
    options.set(name, value);
  }
  const required = (name: string) => { const value = options.get(name); if (!value) throw new Error(`required ${name}`); return value; };
  const execution = await runCurrentSourceCli({ java: required("--java"), jar: await identity(required("--candidate-jar")), nativeHost: await identity(required("--candidate-host")),
    lock: await Bun.file(resolve(import.meta.dir, "current-reference.json")).json(), inventory: await Bun.file(resolve(import.meta.dir, "current-inventory.json")).json(),
    build: await artifact(required("--source-cli-build")), evidence: resolve(required("--evidence")), runId: randomBytes(16).toString("hex") });
  console.log(`${execution.state}: ${execution.cases?.length ?? 0} actual-source CLI/gateway cases; current qualification remains separate.`);
  if (execution.findings.length) console.log(execution.findings.join("\n")); process.exitCode = execution.findings.length ? 1 : 0;
}
