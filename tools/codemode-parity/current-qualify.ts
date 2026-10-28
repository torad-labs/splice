import { randomBytes } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { artifact, identity, sealReceipt, type Receipt, type CaseId } from "./qualification";
import { currentSealKey, loadCurrentInputs, observeCurrentReference, inspectCurrentReceipt, sealCurrentReceipt, writeCurrentProgress, type CurrentReceipt } from "./current-readiness";
import { observeCurrentInstallation } from "./current-installed";
import type { CurrentVerifierId, ExecutedVerifier } from "./current-coverage";
import { runCurrentCore, capturedCommand } from "./current-core";
import { runCurrentProtocol } from "./current-protocol";
import { appendCurrentDiscovery } from "./current-discovery";
import { runInstalledCase } from "./installed-case";
import { appendCurrentApiFlows } from "./current-api-flows";
import { runCurrentClient } from "./current-client";
import { appendCurrentCli } from "./current-cli-proof";
import { runCurrentSourceCli } from "./current-core-cli";

/** Explicit qualification runs real production drivers; readiness only validates their sealed raw evidence. */
export async function qualifyCurrent(options: Map<string, string>): Promise<void> {
  const required = (key: string) => { const value = options.get(key); if (!value) throw new Error(`qualify-current requires ${key}`); return value; };
  const inputs = await loadCurrentInputs(options.get("--source-repo"));
  if (inputs.findings.length) throw new Error(inputs.findings.join("; "));
  const { lock, inventory } = inputs;
  const [candidateJar, candidateNativeHost, reference, key] = await Promise.all([
    identity(required("--candidate-jar")), identity(required("--candidate-host")), observeCurrentReference(lock), currentSealKey(true),
  ]);
  if (candidateNativeHost.sha256 !== lock.nativeHostSha256) throw new Error("candidate host is not the source-verified current native engine");
  const evidence = resolve(required("--evidence")), java = required("--java"), runId = randomBytes(16).toString("hex");
  await mkdir(evidence, { recursive: true });
  const floorDirectory = resolve(evidence, `${runId}.regression-floor`); await mkdir(floorDirectory, { recursive: true });
  const floorOptions = new Map(options); floorOptions.set("--evidence", floorDirectory); floorOptions.set("--reference-host", reference.nativeHost.path);
  for (const name of ["--installed-pid", "--host-pid", "--jar-fd"]) floorOptions.delete(name);
  const floorArgs = [...floorOptions.entries()].flatMap(([name, value]) => [name, value]);
  const floorRun = await capturedCommand([process.execPath, resolve(import.meta.dir, "index.ts"), "qualify-floor-current", ...floorArgs], resolve(import.meta.dir, "../.."), undefined, 1_200_000);
  await writeFile(resolve(evidence, `${runId}.regression-run.json`), JSON.stringify(floorRun, null, 2) + "\n");
  const floor: Receipt = JSON.parse(await readFile(resolve(floorDirectory, "receipt.json"), "utf8"));
  const executions: ExecutedVerifier[] = [];
  const groups: Array<[CurrentVerifierId, CaseId[]]> = [
    ["current.output-and-media", ["runtime.output-content"]],
    ["current.gateway-history", ["history.append-only", "history.restart", "history.replay-is-not-steering", "history.real-steering", "history.cleared-results"]],
    ["current.interactive-delivery", ["runtime.item-boundary", "delivery.readable-reasoning", "delivery.interactive-cadence", "delivery.late-failure-recovery"]],
    ["current.packaged-identities", ["packaging.native-host"]],
  ];
  for (const [id, cases] of groups) {
    const found = cases.map(id => floor.cases.find(item => item.id === id));
    executions.push({ id, runId, startedAt: floor.startedAt, finishedAt: floor.finishedAt, inventorySha256: inventory.denominatorSha256, sourceRevision: lock.sourceRevision,
      candidateJarSha256: candidateJar.sha256, nativeHostSha256: lock.nativeHostSha256, state: found.every(item => item?.state === "executed" && !item.findings.length) ? "executed" : "failed",
      findings: found.flatMap((item, index) => item ? item.findings : [`missing ${cases[index]}`]), artifacts: found.flatMap(item => item?.artifacts ?? []) });
  }
  executions.push(await runCurrentProtocol(java, candidateJar.path, candidateNativeHost.path, reference.nativeHost.path, evidence, runId, lock, inventory));
  executions.push(await runCurrentClient(java, candidateJar.path, reference.nativeHost.path, evidence, runId, lock, inventory));
  const core = await runCurrentCore(java, candidateJar.path, evidence, runId, lock, inventory);
  await appendCurrentDiscovery(java, candidateJar.path, candidateNativeHost.path, evidence, runId, core);
  await appendCurrentApiFlows(java, candidateJar.path, candidateNativeHost.path, evidence, runId, core);
  executions.push(core);
  executions.push(await runCurrentSourceCli({ java, jar: candidateJar, nativeHost: candidateNativeHost, lock, inventory, build: await artifact(required("--source-cli-build")), applyPatchBuild: await artifact(required("--apply-patch-build")), evidence, runId }));
  await appendCurrentCli(java, candidateJar, reference, evidence, runId, lock, executions.find(item => item.id === "current.gateway-history")!);
  const receipt: CurrentReceipt = { schema: 2, claim: "current-complete-CodeMode", runId, startedAt: floor.startedAt, finishedAt: new Date().toISOString(),
    sourceRevision: lock.sourceRevision, inventorySha256: inventory.denominatorSha256, ...inputs.hashes, reference, candidateJar, candidateNativeHost,
    nativeProvenance: await artifact(resolve(import.meta.dir, "../..", lock.nativeProvenanceDefault)), regressionFloor: await artifact(resolve(floorDirectory, "receipt.json")),
    executions, java, candidateConformant: true, qualified: false, seal: "", known_soft_spots: [
      "Deployment and interactive client execution are Linux only. Other packaged platforms are proven by source-linked native executable identity, not interactive deployment evidence.",
      "Ported function witnesses bind exact source bodies to named input cases and actual function-entry counters. This is not statement/branch coverage or an exhaustive scheduler interleaving proof; unlinked behavioral declarations block qualification.",
      "Source-based standalone oracles retain data-type shims. Production gateway discovery, persistence and delivery are separate mandatory executions.",
      ...(inputs.provenance?.known_soft_spots ?? []),
      ...executions.flatMap(execution => execution.known_soft_spots ?? []),
    ] };
  receipt.seal = sealCurrentReceipt(receipt, key);
  const findings = await inspectCurrentReceipt(receipt, inputs, options, key, "candidate");
  receipt.candidateConformant = findings.length === 0; receipt.seal = sealCurrentReceipt(receipt, key);
  await writeFile(resolve(evidence, "receipt-current.json"), JSON.stringify(receipt, null, 2) + "\n");
  await writeCurrentProgress(receipt, [...findings, "Installed configured listener attestation is pending; product readiness remains unqualified."], evidence, inputs);
  console.log(`${receipt.candidateConformant ? "CANDIDATE_CONFORMANT" : "UNQUALIFIED"}: current Codex ${lock.release}, ${inventory.units.length} discovered source units, ${executions.filter(item => item.state === "executed").length}/${executions.length} production verifiers; installed attestation remains required.`);
  if (findings.length) console.log(findings.join("\n")); process.exitCode = findings.length ? 1 : 0;
}

export async function attestCurrentInstallation(options: Map<string, string>): Promise<void> {
  const selected = options.get("--evidence"); if (!selected) throw new Error("attest-install-current requires --evidence from the successful candidate conformance run");
  const evidence = resolve(selected), inputs = await loadCurrentInputs(options.get("--source-repo")), key = await currentSealKey(false);
  const receipt: CurrentReceipt = JSON.parse(await readFile(resolve(evidence, "receipt-current.json"), "utf8"));
  const conformance = await inspectCurrentReceipt(receipt, inputs, options, key, "candidate");
  if (conformance.length) throw new Error(`Current candidate conformance cannot be finalized: ${conformance.join("; ")}`);
  const installed = await observeCurrentInstallation(options.get("--java") ?? receipt.java, receipt.candidateJar.sha256, inputs.lock.nativeHostSha256, randomBytes(16).toString("hex"));
  const floor: Receipt = JSON.parse(await readFile(receipt.regressionFloor.path, "utf8"));
  const installOptions = new Map<string, string>([["--installed-pid", String(installed.listener.pid)], ["--host-pid", String(installed.hostPid)], ["--jar-fd", installed.jarFd]]);
  await runInstalledCase(floor, installOptions, resolve(receipt.regressionFloor.path, ".."));
  const family = floor.cases.find(item => item.id === "evidence.installed-identity");
  if (family?.state !== "executed" || family.findings.length) throw new Error(`Current installed identity did not pass: ${family?.findings.join("; ")}`);
  floor.finishedAt = new Date().toISOString(); floor.qualified = true; floor.seal = sealReceipt(floor, key);
  await writeFile(receipt.regressionFloor.path, JSON.stringify(floor, null, 2) + "\n");
  receipt.regressionFloor = await artifact(receipt.regressionFloor.path); receipt.installed = installed;
  receipt.finishedAt = new Date().toISOString(); receipt.qualified = true; receipt.seal = sealCurrentReceipt(receipt, key);
  const findings = await inspectCurrentReceipt(receipt, inputs, options, key);
  receipt.qualified = findings.length === 0; receipt.seal = sealCurrentReceipt(receipt, key);
  await writeFile(resolve(evidence, "receipt-current.json"), JSON.stringify(receipt, null, 2) + "\n");
  await writeCurrentProgress(receipt, findings, evidence, inputs);
  console.log(`${receipt.qualified ? "CURRENT_COMPLETE" : "UNQUALIFIED"}: current installation verified against the unchanged complete candidate execution.`);
  if (findings.length) console.log(findings.join("\n")); process.exitCode = findings.length ? 1 : 0;
}
