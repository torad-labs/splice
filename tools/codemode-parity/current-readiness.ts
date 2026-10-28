import { createHmac, timingSafeEqual, randomBytes } from "node:crypto";
import { readFile, writeFile, realpath } from "node:fs/promises";
import { resolve } from "node:path";
import { homedir } from "node:os";
import { artifact, sha256, harnessHash, localSealKey, identity, structuralFindings, rawArtifactFindings, type Artifact, type Identity, type Receipt } from "./qualification";
import { generateCurrentInventory, inventoryFindings, type CurrentInventory } from "./current-inventory";
import { dispositionFindings, executionFindings, nativeProvenanceFindings, type Dispositions, type ExecutedVerifier, type NativeProvenance } from "./current-coverage";
import { listenerFindings, observeCurrentInstallation, type CurrentInstalledIdentity } from "./current-installed";
import { resolveCurrentSource } from "./current-source";
import { executedCaseWitnessFindings } from "./current-witness";
import type { CoreCaseWitness } from "./current-witness";
import { clientWitnessFindings, type ClientCaseWitness } from "./current-client-profile";
import { coreCorpusFindings } from "./current-core-corpus";
import { apiWitnessFindings, type ApiCaseWitness } from "./current-core-api-witness";
import { cliControlFindings } from "./current-cli-proof";
import { currentHistoryFindings } from "./current-history";
import { sourceCliWitnessFindings, type CliSourceCaseWitness } from "./current-cli-source-profile";
import type { CompilerScopeIdentity } from "./current-compiler-scope";

const directory = import.meta.dir, repo = resolve(directory, "../..");
export type CurrentReference = {
  schema: 2; repository: string; release: string; tag: string; sourceRevision: string; cliSha256: string; nativeHostSha256: string; target: string;
  sourceRepositoryDefault: string; installedPackageRootDefault: string; nativeProvenanceDefault: string; nativeProvenanceSha256: string; historicalContractSha256: string;
  cliSourceBundle: string; cliSourceBundleSha256: string;
};
export type CurrentReceipt = {
  schema: 2; claim: "current-complete-CodeMode"; runId: string; startedAt: string; finishedAt: string;
  sourceRevision: string; inventorySha256: string; dispositionsSha256: string; referenceLockSha256: string; harnessSha256: string;
  reference: { release: string; cli: Identity; nativeHost: Identity }; candidateJar: Identity; candidateNativeHost: Identity;
  nativeProvenance: Artifact; regressionFloor: Artifact; executions: ExecutedVerifier[]; installed?: CurrentInstalledIdentity;
  java: string; candidateConformant: boolean; qualified: boolean; seal: string; known_soft_spots: string[];
  compilerBindings?: CompilerScopeIdentity;
};
export function sealCurrentReceipt(receipt: CurrentReceipt, key: Uint8Array): string {
  return createHmac("sha256", key).update(JSON.stringify({ ...receipt, seal: undefined })).digest("hex");
}
export function currentReceiptFindings(receipt: any, expected: {
  sourceRevision: string; inventorySha256: string; dispositionsSha256: string; referenceLockSha256: string; harnessSha256: string;
  referenceRelease: string; referenceCliSha256: string; nativeHostSha256: string; provenanceSha256: string; now?: number; requireInstalled?: boolean;
  compilerBindings?: CompilerScopeIdentity;
}, key: Uint8Array): string[] {
  if (!receipt || receipt.schema !== 2 || receipt.claim !== "current-complete-CodeMode") return ["historical bounded receipt cannot establish current complete CodeMode parity; current schema-2 execution receipt is required"];
  const findings: string[] = [];
  if (typeof receipt.runId !== "string" || !/^[a-f0-9]{32}$/.test(receipt.runId)) findings.push("malformed current run identity");
  for (const name of ["sourceRevision", "inventorySha256", "dispositionsSha256", "referenceLockSha256", "harnessSha256"] as const) if (receipt[name] !== expected[name]) findings.push(`current ${name} changed`);
  if (JSON.stringify(receipt.compilerBindings) !== JSON.stringify(expected.compilerBindings)) findings.push("current explicit compiler binding proof or semantic source decisions changed");
  if (receipt.reference?.release !== expected.referenceRelease || receipt.reference?.cli?.sha256 !== expected.referenceCliSha256 || receipt.reference?.nativeHost?.sha256 !== expected.nativeHostSha256) findings.push("current reference version or executable identity drifted");
  if (receipt.candidateNativeHost?.sha256 !== expected.nativeHostSha256) findings.push("candidate native host is not the verified current reference engine");
  if (receipt.nativeProvenance?.sha256 !== expected.provenanceSha256) findings.push("current reference source provenance changed");
  const start = Date.parse(receipt.startedAt), finish = Date.parse(receipt.finishedAt), now = expected.now ?? Date.now();
  if (!Number.isFinite(start) || !Number.isFinite(finish) || finish < start || finish > now + 1000 || start < now - 86_400_000) findings.push("current evidence is stale or has malformed time");
  if (expected.requireInstalled !== false && !receipt.installed) findings.push("current configured listener installation proof is missing");
  const expectedSeal = sealCurrentReceipt(receipt, key);
  if (typeof receipt.seal !== "string" || !/^[a-f0-9]{64}$/.test(receipt.seal) || !timingSafeEqual(Buffer.from(receipt.seal, "hex"), Buffer.from(expectedSeal, "hex"))) findings.push("current receipt is unsealed or changed; a claimed PASS is not execution evidence");
  if (expected.requireInstalled !== false && receipt.qualified !== true) findings.push("current runner did not qualify this installed candidate");
  if (receipt.candidateConformant !== true) findings.push("current candidate did not pass complete conformance");
  if (["pass", "status", "skipped"].some(name => name in receipt)) findings.push("manually asserted current result fields are forbidden");
  return findings;
}
export async function loadCurrentInputs(sourceRepo?: string, compilerBindings?: string): Promise<{
  lock: CurrentReference; inventory: CurrentInventory; dispositions: Dispositions; provenance?: NativeProvenance;
  findings: string[]; hashes: { referenceLockSha256: string; dispositionsSha256: string; harnessSha256: string };
}> {
  const [lockBytes, inventoryBytes, dispositionBytes, contractBytes] = await Promise.all([
    readFile(resolve(directory, "current-reference.json")), readFile(resolve(directory, "current-inventory.json")),
    readFile(resolve(directory, "current-dispositions.json")), readFile(resolve(directory, "contract.json")),
  ]);
  const lock: CurrentReference = JSON.parse(lockBytes.toString()), inventory: CurrentInventory = JSON.parse(inventoryBytes.toString()), dispositions: Dispositions = JSON.parse(dispositionBytes.toString());
  const findings: string[] = [];
  if (lock.schema !== 2 || !/^[a-f0-9]{40}$/.test(lock.sourceRevision) || !/^\d+\.\d+\.\d+$/.test(lock.release) || lock.tag !== `rust-v${lock.release}`) findings.push("malformed current reference lock");
  if (sha256(contractBytes) !== lock.historicalContractSha256) findings.push("frozen 24-family historical contract was changed");
  lock.sourceRepositoryDefault = await resolveCurrentSource(lock, sourceRepo);
  const generated = await generateCurrentInventory(lock.sourceRepositoryDefault, lock.sourceRevision, undefined, undefined, {
    compilerBindings, expectedCompilerProofSha256: inventory.compilerBindings?.proofSha256,
  });
  findings.push(...inventoryFindings(generated, inventory), ...dispositionFindings(inventory, dispositions), ...await currentHistoryFindings());
  let provenance: NativeProvenance | undefined;
  try {
    const bytes = await readFile(resolve(repo, lock.nativeProvenanceDefault));
    if (sha256(bytes) !== lock.nativeProvenanceSha256) findings.push("pinned current native provenance bytes changed");
    provenance = JSON.parse(bytes.toString());
    findings.push(...await nativeProvenanceFindings(provenance, lock));
  } catch (error) { findings.push(`current native provenance unavailable: ${error instanceof Error ? error.message : String(error)}`); }
  return { lock, inventory, dispositions, provenance, findings,
    hashes: { referenceLockSha256: sha256(lockBytes), dispositionsSha256: sha256(dispositionBytes), harnessSha256: await harnessHash(directory) } };
}

/** Always resolve the currently selected Codex package symlink; archived references cannot hide updates. */
export async function observeCurrentReference(lock: CurrentReference): Promise<CurrentReceipt["reference"]> {
  const root = await realpath(lock.installedPackageRootDefault ?? process.env.CODEX_REFERENCE_PACKAGE ?? resolve(homedir(), ".codex-cli/packages/app-server-daemon/current"));
  const metadata = JSON.parse(await readFile(resolve(root, "codex-package.json"), "utf8"));
  if (metadata.version !== lock.release || metadata.target !== lock.target) throw new Error(`installed Codex reference drift: selected ${metadata.version}/${metadata.target}, pinned ${lock.release}/${lock.target}`);
  const [cli, nativeHost] = await Promise.all([identity(resolve(root, "bin/codex")), identity(resolve(root, "bin/codex-code-mode-host"))]);
  if (cli.sha256 !== lock.cliSha256 || nativeHost.sha256 !== lock.nativeHostSha256) throw new Error("installed Codex reference CLI/native executable hash drift");
  const child = Bun.spawn([cli.path, "--version"], { stdin: "ignore", stdout: "pipe", stderr: "pipe" });
  const [stdout, stderr, code] = await Promise.all([new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited]);
  if (code !== 0 || stdout.trim() !== `codex-cli ${lock.release}`) throw new Error(`selected Codex reference version probe disagrees: ${stdout.trim()} ${stderr.trim()}`);
  return { release: lock.release, cli, nativeHost };
}
async function evidenceFindings(entry: Artifact | undefined, label: string): Promise<string[]> {
  if (!entry || typeof entry.path !== "string") return [`${label}: evidence is missing`];
  try { const actual = await artifact(entry.path); return actual.sha256 === entry.sha256 && actual.bytes === entry.bytes ? [] : [`${label}: raw bytes changed`]; }
  catch { return [`${label}: raw evidence is unavailable`]; }
}
export async function currentSealKey(create: boolean): Promise<Uint8Array> {
  const git = Bun.spawnSync(["git", "rev-parse", "--git-common-dir"], { cwd: repo });
  if (git.exitCode !== 0) throw new Error("current qualification seal requires a Git checkout");
  return localSealKey(resolve(repo, git.stdout.toString().trim(), "codemode-parity-seal.key"), create);
}

export async function inspectCurrentReceipt(receipt: CurrentReceipt | undefined, inputs: Awaited<ReturnType<typeof loadCurrentInputs>>, options: Map<string, string>, key: Uint8Array, phase: "candidate" | "installed" = "installed"): Promise<string[]> {
  const { lock, inventory, dispositions, hashes } = inputs;
  const findings = [...inputs.findings];
  try { await observeCurrentReference(lock); } catch (error) { findings.push(error instanceof Error ? error.message : String(error)); }
  findings.push(...currentReceiptFindings(receipt, { ...hashes, sourceRevision: lock.sourceRevision, inventorySha256: inventory.denominatorSha256,
    referenceRelease: lock.release, referenceCliSha256: lock.cliSha256, nativeHostSha256: lock.nativeHostSha256, provenanceSha256: lock.nativeProvenanceSha256,
    ...(inventory.compilerBindings ? { compilerBindings: inventory.compilerBindings } : {}), requireInstalled: phase === "installed" }, key));
  if (!receipt || receipt.schema !== 2) return findings;
  findings.push(...executionFindings(inventory, dispositions, receipt.executions, { runId: receipt.runId, candidateJarSha256: receipt.candidateJar.sha256, nativeHostSha256: lock.nativeHostSha256 }));
  const caseWitnesses = (receipt.executions ?? []).flatMap(execution => execution.cases ?? []);
  findings.push(...await coreCorpusFindings(receipt.executions?.find(execution => execution.id === "current.tools-and-description"), receipt.runId));
  findings.push(...await executedCaseWitnessFindings(caseWitnesses.filter(witness => witness.producer === undefined || witness.producer === "core-source") as CoreCaseWitness[], inventory, receipt.runId));
  findings.push(...await apiWitnessFindings(caseWitnesses.filter(witness => witness.producer === "core-api-llvm") as ApiCaseWitness[], inventory, receipt.runId, receipt.candidateJar));
  findings.push(...await sourceCliWitnessFindings(caseWitnesses.filter(witness => witness.producer === "core-cli-llvm") as CliSourceCaseWitness[], inventory, receipt.runId, receipt.candidateJar, lock));
  findings.push(...await clientWitnessFindings(caseWitnesses.filter(witness => witness.producer === "rust-client-llvm") as ClientCaseWitness[], inventory, receipt.runId, receipt.candidateJar, { execution: receipt.executions?.find(entry => entry.id === "current.rust-client")?.known_soft_spots ?? [], receipt: receipt.known_soft_spots ?? [] }));
  findings.push(...await cliControlFindings(receipt.executions?.find(entry => entry.id === "current.gateway-history"), receipt.runId, lock, receipt.candidateJar, receipt.reference, inventory));
  findings.push(...await evidenceFindings(receipt.nativeProvenance, "native provenance"), ...await evidenceFindings(receipt.regressionFloor, "current regression floor"));
  for (const execution of receipt.executions ?? []) for (const entry of execution.artifacts ?? []) findings.push(...await evidenceFindings(entry, execution.id));
  for (const [label, entry] of [["candidate jar", receipt.candidateJar], ["candidate native host", receipt.candidateNativeHost]] as const) {
    try { if ((await identity(entry.path)).sha256 !== entry.sha256) findings.push(`${label}: artifact changed`); } catch { findings.push(`${label}: artifact is unavailable`); }
  }
  try {
    const floor: Receipt = JSON.parse(await readFile(receipt.regressionFloor.path, "utf8"));
    findings.push(...structuralFindings(floor, { contractSha256: lock.historicalContractSha256, harnessSha256: hashes.harnessSha256,
      referenceSha256: lock.nativeHostSha256, candidateJarSha256: receipt.candidateJar.sha256, candidateNativeHostSha256: lock.nativeHostSha256,
      requireVerifiedSource: true, sourceRevision: lock.sourceRevision, provenanceSha256: lock.nativeProvenanceSha256,
      requireInstalled: phase === "installed", requireRunnerQualified: phase === "installed" }, key).map(value => `current regression floor: ${value}`));
    findings.push(...await rawArtifactFindings(floor));
  } catch (error) { findings.push(`current regression floor cannot be verified: ${error instanceof Error ? error.message : String(error)}`); }
  if (phase === "installed") try {
    const current = await observeCurrentInstallation(options.get("--java") ?? receipt.java, receipt.candidateJar.sha256, lock.nativeHostSha256, randomBytes(16).toString("hex"));
    findings.push(...listenerFindings(receipt.installed?.listener, current.listener));
    if (receipt.installed?.hostPid !== current.hostPid || receipt.installed?.hostStartTicks !== current.hostStartTicks) findings.push("installed current native process changed");
  } catch (error) { findings.push(error instanceof Error ? error.message : String(error)); }
  return [...new Set(findings)];
}
export async function writeCurrentProgress(receipt: CurrentReceipt | undefined, findings: string[], evidence: string, inputs?: Awaited<ReturnType<typeof loadCurrentInputs>>): Promise<void> {
  const executions = Array.isArray(receipt?.executions) ? receipt.executions : [];
  await writeFile(resolve(directory, "progress-current.json"), JSON.stringify({ schema: 2, updatedAt: new Date().toISOString(),
    status: findings.length ? receipt?.candidateConformant && findings.length === 1 && findings[0]!.startsWith("Installed configured listener attestation is pending") ? "PENDING_INSTALL" : "UNQUALIFIED" : "CURRENT_COMPLETE", productQualified: findings.length === 0,
    candidateConformant: receipt?.candidateConformant ?? false, currentReference: inputs?.lock.release ?? null,
    sourceRevision: inputs?.lock.sourceRevision ?? null, inventorySha256: inputs?.inventory.denominatorSha256 ?? null,
    discoveredUnits: inputs?.inventory.units.length ?? 0, executedVerifiers: executions.filter(item => item?.state === "executed" && Array.isArray(item.findings) && !item.findings.length).map(item => item.id),
    findings, receiptPath: resolve(evidence, "receipt-current.json"), historicalReceipt: "The separate schema-1 24-family receipt is a bounded regression record, never current completeness.",
    scope: "Linux installed deployment execution; all packaged native executable identities by verified source linkage and reuse.", known_soft_spots: receipt?.known_soft_spots ?? [],
  }, null, 2) + "\n");
}
export async function currentReadiness(options: Map<string, string>): Promise<void> {
  const fallback = resolve(repo, "../artifacts/codemode-current-parity-2026-10-25/qualification");
  let evidence = resolve(options.get("--evidence") ?? fallback), receipt: CurrentReceipt | undefined, inputs: Awaited<ReturnType<typeof loadCurrentInputs>> | undefined;
  const findings: string[] = [];
  let locationValid = true;
  try {
    if (!options.has("--evidence")) {
      try {
        const progress = JSON.parse(await readFile(resolve(directory, "progress-current.json"), "utf8"));
        if (progress.schema !== 2 || typeof progress.receiptPath !== "string" || resolve(progress.receiptPath) !== progress.receiptPath || !progress.receiptPath.endsWith("/receipt-current.json")) { locationValid = false; throw new Error("malformed current progress location"); }
        evidence = resolve(progress.receiptPath, "..");
      } catch (error: any) { if (error.code !== "ENOENT") { locationValid = false; throw error; } }
    }
    inputs = await loadCurrentInputs(options.get("--source-repo"), options.get("--compiler-bindings"));
    try { receipt = JSON.parse(await readFile(resolve(evidence, "receipt-current.json"), "utf8")); }
    catch (error: any) { if (error.code === "ENOENT") findings.push("no current execution receipt; the historical 24-family receipt is only a bounded regression floor"); else throw error; }
    let key: Uint8Array;
    try { key = await currentSealKey(false); }
    catch { findings.push("current qualification seal key is unavailable; execute candidate conformance first"); key = Buffer.alloc(32); }
    findings.push(...await inspectCurrentReceipt(receipt, inputs, options, key));
  } catch (error) { findings.push(error instanceof Error ? error.message : String(error)); }
  if (locationValid) await writeCurrentProgress(receipt, [...new Set(findings)], evidence, inputs);
  console.log(`${findings.length ? "UNQUALIFIED" : "CURRENT_COMPLETE"}: current Codex ${inputs?.lock.release ?? "unknown"}; ${inputs?.inventory.units.length ?? 0} discovered source units; ${findings.length} finding(s).`);
  if (findings.length) console.log([...new Set(findings)].join("\n"));
  process.exitCode = findings.length ? 1 : 0;
}
