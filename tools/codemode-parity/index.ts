import { randomBytes } from "node:crypto";
import { readFile, writeFile, mkdir, readdir, stat, readlink } from "node:fs/promises";
import { resolve } from "node:path";
import { NativeReference } from "./native-reference";
import { CurrentGrpcReference } from "./current-grpc-reference";
import { runtimeCases, runRuntimeCase } from "./runtime-cases";
import { artifact, contractFindings, harnessHash, identity, localSealKey, requiredCaseIds,
  sealReceipt, sha256, structuralFindings, rawArtifactFindings, type Receipt, type CaseEvidence } from "./qualification";
import { missingDimensions, verifiers } from "./verifiers";
import { observeLegacyBaseline } from "./legacy-baseline";
import { explicitYieldSource } from "./programs";
import { runGatewayCases } from "./gateway-cases";
import { runFormatterCases } from "./formatter-cases";
import { runPackagingCase } from "./packaging-cases";
import { runInteractiveCases } from "./interactive-cases";
import { runAudioCases } from "./audio-cases";
import { runInstalledCase } from "./installed-case";
import { runNativeGatewayCases } from "./native-gateway-cases";
import { candidateCommand } from "./candidate-command";
import { assertCandidateWorkerLoss } from "./candidate-loss";
import { resolveEvidenceDirectory } from "./evidence-location";
import { currentReadiness, loadCurrentInputs } from "./current-readiness";

const directory = import.meta.dir;
const repo = resolve(directory, "../..");
const defaultEvidence = resolve(repo, "../artifacts/codemode-parity/qualification");
const [command = "readiness", ...args] = process.argv.slice(2);
const options = new Map<string, string>();
for (let i = 0; i < args.length; i += 2) {
  const name = args[i], value = args[i + 1];
  if (!name?.startsWith("--") || !value || value.startsWith("--") || options.has(name)) throw new Error("arguments must be unique --name VALUE pairs");
  options.set(name, value);
}
const known = ["--candidate-jar", "--candidate-host", "--reference-host", "--baseline-jar", "--java", "--evidence", "--installed-pid", "--host-pid", "--jar-fd", "--source-repo"];
for (const option of options.keys()) if (!known.includes(option)) throw new Error(`unknown option ${option}`);
let evidence = resolve(options.get("--evidence") ?? defaultEvidence);
let evidenceSelected = false;
const progressPath = resolve(directory, "progress.json");
const lock = await Bun.file(resolve(directory, "reference-lock.json")).json();
const currentFloor = command === "qualify-floor-current";
const currentInputs = currentFloor ? await loadCurrentInputs(options.get("--source-repo")) : undefined;
if (currentInputs?.findings.length) throw new Error(currentInputs.findings.join("; "));
const activeReferenceHash = currentInputs?.lock.nativeHostSha256 ?? lock.nativeExecutableSha256;
const contractBytes = await readFile(resolve(directory, "contract.json"));
const contract = JSON.parse(contractBytes.toString("utf8"));
const contractProblems = contractFindings(contract);
const currentHarnessHash = await harnessHash(directory);
const contractSha256 = sha256(contractBytes);
const writeProgress = async (receipt: Receipt | undefined, findings: string[]) => {
  const observedCases = Array.isArray(receipt?.cases) ? receipt.cases : [];
  await writeFile(progressPath, JSON.stringify({
    schema: 1, status: findings.length ? "UNQUALIFIED" : "QUALIFIED", productQualified: findings.length === 0,
    updatedAt: new Date().toISOString(), contractSha256, harnessSha256: currentHarnessHash,
    requiredFamilies: requiredCaseIds.length, executedFamilies: observedCases.filter(item => item?.state === "executed").map(item => item.id),
    remainingFamilies: requiredCaseIds.filter(id => !observedCases.some(item => item?.id === id && item.state === "executed")),
    findings, receiptPath: resolve(evidence, "receipt.json"),
    referenceSourceLinkage: "unproven",
    scope: "A green harness test suite or native reference subset never qualifies the product.",
  }, null, 2) + "\n");
};
const git = Bun.spawnSync(["git", "rev-parse", "--git-common-dir"], { cwd: repo });
if (git.exitCode !== 0) throw new Error("qualification seal requires a Git checkout");
const keyPath = resolve(repo, git.stdout.toString().trim(), "codemode-parity-seal.key");
const currentExpected = async (receipt: Receipt) => ({
  contractSha256: sha256(await readFile(resolve(directory, "contract.json"))),
  harnessSha256: await harnessHash(directory), referenceSha256: activeReferenceHash,
  candidateJarSha256: (await identity(options.get("--candidate-jar") ?? receipt.candidateJar.path)).sha256,
  candidateNativeHostSha256: (await identity(options.get("--candidate-host") ?? receipt.candidateNativeHost.path)).sha256,
  ...(currentInputs ? { requireVerifiedSource: true, sourceRevision: currentInputs.lock.sourceRevision, provenanceSha256: currentInputs.lock.nativeProvenanceSha256 } : {}),
});
async function verifyFiles(receipt: Receipt): Promise<string[]> {
  const findings: string[] = [];
  for (const [name, value] of [["reference", receipt.reference], ["candidate jar", receipt.candidateJar], ["candidate native host", receipt.candidateNativeHost]] as const) {
    try { if ((await identity(value.path)).sha256 !== value.sha256) findings.push(`${name} artifact changed`); }
    catch { findings.push(`${name} artifact is unavailable`); }
  }
  findings.push(...await rawArtifactFindings(receipt));
  if (receipt.installed) {
    try { if (sha256(await readFile(receipt.installed.jarFd)) !== receipt.candidateJar.sha256) findings.push("installed open jar changed"); }
    catch { findings.push("installed open jar is no longer verifiable"); }
    try { if (sha256(await readFile(`/proc/${receipt.installed.hostPid}/exe`)) !== receipt.candidateNativeHost.sha256) findings.push("installed native host changed"); }
    catch { findings.push("installed native host is no longer verifiable"); }
  }
  return findings;
}
async function productionIdentity(host: Pick<NativeReference, "pid">, jarHash: string, nativeHash: string): Promise<any> {
  if (!host.pid) throw new Error("candidate adapter has no live process");
  const jarFds: string[] = [];
  for (const fd of await readdir(`/proc/${host.pid}/fd`)) {
    const path = `/proc/${host.pid}/fd/${fd}`;
    try { if ((await stat(path)).isFile() && sha256(await readFile(path)) === jarHash) jarFds.push(path); }
    catch { /* A pipe or descriptor may close while inspected. */ }
  }
  if (!jarFds.length) throw new Error("production diagnostic is not holding the exact candidate jar open");
  const children = new Set<number>();
  for (const task of await readdir(`/proc/${host.pid}/task`)) {
    try {
      const found = (await readFile(`/proc/${host.pid}/task/${task}/children`, "utf8")).trim().split(/\s+/).filter(Boolean).map(Number);
      found.forEach(pid => children.add(pid));
    } catch { /* JVM worker threads may exit during the census. */ }
  }
  let nativePid: number | undefined;
  for (const pid of children) {
    try { if (sha256(await readFile(`/proc/${pid}/exe`)) === nativeHash) nativePid = pid; }
    catch { /* Exited children do not establish identity. */ }
  }
  if (!nativePid) throw new Error("production adapter has no live child matching the candidate native executable");
  return { adapterPid: host.pid, jarFds, jarSha256: jarHash, nativePid,
    nativeExePath: await readlink(`/proc/${nativePid}/exe`), nativeExeSha256: nativeHash };
}
async function qualify(): Promise<void> {
  const required = (name: string) => { const value = options.get(name); if (!value) throw new Error(`qualify requires ${name}`); return value; };
  const reference = await identity(required("--reference-host"));
  const candidateJar = await identity(required("--candidate-jar"));
  const candidateNativeHost = await identity(required("--candidate-host"));
  const java = required("--java");
  if (reference.sha256 !== activeReferenceHash) throw new Error("reference executable differs from the pinned native oracle");
  if (contractProblems.length) throw new Error(contractProblems.join("; "));
  await mkdir(evidence, { recursive: true });
  const key = await localSealKey(keyPath, true);
  const runId = randomBytes(16).toString("hex");
  const cases: CaseEvidence[] = requiredCaseIds.map(id => ({ id, state: "missing", verifier: verifiers[id] ?? "unavailable", runId,
    artifacts: [], findings: [missingDimensions[id] ?? "Not executed in this run."] }));
  const receipt: Receipt = { schema: 1, runId, startedAt: new Date().toISOString(), finishedAt: "",
    contractSha256, harnessSha256: currentHarnessHash, reference, candidateJar, candidateNativeHost,
    referenceSourceLinkage: currentInputs ? "verified" : "unproven",
    ...(currentInputs ? { referenceSourceEvidence: { sourceRevision: currentInputs.lock.sourceRevision,
      provenance: await artifact(resolve(repo, currentInputs.lock.nativeProvenanceDefault)) } } : {}), cases, qualified: false, seal: "" };
  for (const test of runtimeCases) {
    const item = cases.find(entry => entry.id === test.id)!;
    item.findings = [];
    for (const side of ["reference", "candidate"] as const) {
      const host = side === "reference" ? (currentFloor ? new CurrentGrpcReference(reference.path) : new NativeReference(reference.path)) :
        new NativeReference(java, candidateCommand(java, candidateJar.path, candidateNativeHost.path).slice(1));
      let failure: string | null = null, observedIdentity: any = null;
      if (side === "candidate") host.crashNativeHost = async () => {
        observedIdentity = await productionIdentity(host, candidateJar.sha256, candidateNativeHost.sha256);
        host.recordFault({ type: "harness/native-host-kill", pid: observedIdentity.nativePid, executableSha256: candidateNativeHost.sha256, signal: "SIGKILL" });
        process.kill(observedIdentity.nativePid, "SIGKILL");
      };
      try {
        await runRuntimeCase(host, test);
        if (side === "candidate" && test.destroysHost) assertCandidateWorkerLoss(host.transcript);
        if (side === "candidate" && !test.destroysHost) observedIdentity = await productionIdentity(host, candidateJar.sha256, candidateNativeHost.sha256);
      } catch (error) { failure = error instanceof Error ? error.stack ?? error.message : String(error); item.findings.push(`${side}: ${failure.split("\n")[0]}`); }
      finally { await host.stop(); }
      const path = resolve(evidence, `${runId}.${test.id}.${side}.json`);
      await writeFile(path, JSON.stringify({ schema: 1, runId, caseId: test.id, side,
        executableSha256: side === "reference" ? reference.sha256 : candidateJar.sha256,
        nativeHostSha256: side === "reference" ? reference.sha256 : candidateNativeHost.sha256,
        observedIdentity, failure, transcript: host.transcript, stderr: host.stderr,
        ...(host instanceof CurrentGrpcReference ? { referenceTransport: "grpc+stdio", rawGrpc: host.rawGrpc } : {}),
      }, null, 2) + "\n");
      item.artifacts.push(await artifact(path));
    }
    if (test.fullFamily === false || !verifiers[test.id as keyof typeof verifiers]) item.findings.push(missingDimensions[test.id as keyof typeof missingDimensions] ?? "No complete family verifier exists.");
    item.state = item.findings.length ? "failed" : "executed";
    console.log(`${item.state === "executed" ? "EXECUTED" : "UNQUALIFIED"} ${item.id}`);
  }
  await runNativeGatewayCases(java, candidateJar.path, candidateNativeHost.path, evidence, runId, cases);
  // This is the preserved historical formatter regression, not the current formatter claim.
  // The current core verifier additionally executes the exact current output/adapter/truncation source.
  await runFormatterCases(java, candidateJar.path, evidence, runId, cases);
  await runAudioCases(java, candidateJar.path, evidence, runId, cases, currentInputs?.lock.sourceRevision);
  await runGatewayCases(java, candidateJar.path, evidence, runId, cases);
  await runPackagingCase(java, candidateJar.path, candidateNativeHost.sha256, evidence, runId, cases, currentInputs ? {
    release: currentInputs.lock.release, sourceRevision: currentInputs.lock.sourceRevision,
    platforms: Object.fromEntries(currentInputs.provenance!.artifacts.map(item => [item.platform, item.sha256])),
  } : undefined);
  await runInteractiveCases(candidateJar.path, evidence, runId, cases);
  if (options.has("--baseline-jar")) {
    const item = cases.find(entry => entry.id === "evidence.failing-baseline")!;
    item.findings = [];
    try {
      const baselineJar = await artifact(required("--baseline-jar"));
      if (baselineJar.sha256 !== lock.baselineJarSha256) throw new Error("baseline jar differs from the frozen failing implementation");
      item.artifacts.push(baselineJar);
      const observed = await observeLegacyBaseline(java, baselineJar.path);
      const path = resolve(evidence, `${runId}.evidence.failing-baseline.json`);
      await writeFile(path, JSON.stringify({ schema: 1, runId, caseId: item.id, baselineJar,
        sourceSha256: sha256(explicitYieldSource), source: explicitYieldSource, ...observed }, null, 2) + "\n");
      item.artifacts.push(await artifact(path));
      if (observed.response?.payload?.type !== "completed" ||
        !/ReferenceError: yield_control is not defined/.test(observed.response?.payload?.error ?? "") ||
        observed.response?.payload?.output !== "reference-before") throw new Error("baseline did not reproduce the frozen explicit-yield failure");
      const fixed = cases.find(entry => entry.id === "runtime.explicit-yield")!;
      if (fixed.state !== "executed") throw new Error("exact candidate did not fix the same explicit-yield source");
      item.artifacts.push(...fixed.artifacts);
    } catch (error) { item.findings.push(error instanceof Error ? error.message : String(error)); }
    item.state = item.findings.length ? "failed" : "executed";
    console.log(`${item.state === "executed" ? "EXECUTED" : "UNQUALIFIED"} ${item.id}`);
  }
  await runInstalledCase(receipt, options, evidence);
  receipt.finishedAt = new Date().toISOString();
  receipt.qualified = true; receipt.seal = sealReceipt(receipt, key);
  const findings = [...contractProblems, ...structuralFindings(receipt, await currentExpected(receipt), key), ...await verifyFiles(receipt)];
  receipt.qualified = findings.length === 0; receipt.seal = sealReceipt(receipt, key);
  await writeFile(resolve(evidence, "receipt.json"), JSON.stringify(receipt, null, 2) + "\n");
  if (!currentFloor) await writeProgress(receipt, findings);
  console.log(`${receipt.qualified ? "BOUNDED_REGRESSION_PASSED" : "UNQUALIFIED"}: ${cases.filter(item => item.state === "executed").length}/${requiredCaseIds.length} frozen regression families; current completeness requires a separate source-inventory receipt.`);
  if (findings.length) console.log(findings.join("\n"));
  process.exitCode = findings.length ? 1 : 0;
}
async function readiness(): Promise<void> {
  let receipt: Receipt | undefined;
  let findings = [...contractProblems];
  try {
    receipt = await Bun.file(resolve(evidence, "receipt.json")).json();
    const key = await localSealKey(keyPath, false);
    findings.push(...structuralFindings(receipt, await currentExpected(receipt!), key));
    findings.push(...await verifyFiles(receipt!));
  } catch (error) { findings.push(error instanceof Error ? error.message : String(error)); }
  await writeProgress(receipt, findings);
  console.log(`${findings.length ? "UNQUALIFIED" : "BOUNDED_REGRESSION_PASSED"}: ${requiredCaseIds.length} frozen families; ${findings.length} finding(s). This is historical bounded evidence, not current completeness.`);
  if (findings.length) console.log(findings.join("\n"));
  process.exitCode = findings.length ? 1 : 0;
}
async function attestInstall(): Promise<void> {
  const receipt: Receipt = await Bun.file(resolve(evidence, "receipt.json")).json();
  const key = await localSealKey(keyPath, false);
  const findings = [...contractProblems, ...structuralFindings(receipt, await currentExpected(receipt), key), ...await verifyFiles(receipt)];
  const priorFailures = findings.filter(value => !value.startsWith("evidence.installed-identity:") &&
    !value.startsWith("installed ") && value !== "runner did not qualify this candidate");
  if (priorFailures.length) throw new Error(`Cannot finalize installation before every other required family passes: ${priorFailures.join("; ")}`);
  await runInstalledCase(receipt, options, evidence);
  receipt.finishedAt = new Date().toISOString(); receipt.qualified = true; receipt.seal = sealReceipt(receipt, key);
  const remaining = [...structuralFindings(receipt, await currentExpected(receipt), key), ...await verifyFiles(receipt)];
  receipt.qualified = remaining.length === 0; receipt.seal = sealReceipt(receipt, key);
  await writeFile(resolve(evidence, "receipt.json"), JSON.stringify(receipt, null, 2) + "\n");
  await writeProgress(receipt, remaining);
  console.log(`${receipt.qualified ? "QUALIFIED" : "UNQUALIFIED"}: installed identity finalized for the unchanged executed candidate.`);
  if (remaining.length) console.log(remaining.join("\n")); process.exitCode = remaining.length ? 1 : 0;
}
try {
  if (!["qualify", "readiness", "readiness-bounded", "attest-install", "qualify-floor-current", "qualify-current", "attest-install-current", "inventory-check"].includes(command)) {
    throw new Error("usage: bun tools/codemode-parity [readiness|readiness-bounded|qualify|qualify-current|attest-install|inventory-check] [--name VALUE ...]");
  }
  if (command === "readiness-bounded" || command === "attest-install") {
    evidence = await resolveEvidenceDirectory(options.get("--evidence"), progressPath, defaultEvidence);
  }
  evidenceSelected = true;
  if (command === "qualify" || command === "qualify-floor-current") await qualify();
  else if (command === "readiness") await currentReadiness(options);
  else if (command === "qualify-current") { const { qualifyCurrent } = await import("./current-qualify"); await qualifyCurrent(options); }
  else if (command === "attest-install-current") { const { attestCurrentInstallation } = await import("./current-qualify"); await attestCurrentInstallation(options); }
  else if (command === "inventory-check") {
    const inputs = await loadCurrentInputs(options.get("--source-repo"));
    console.log(`${inputs.findings.length ? "FAIL" : "INVENTORY_CURRENT"}: ${inputs.inventory.units.length} source units; ${inputs.findings.length} finding(s). Inventory validity is not product parity.`);
    if (inputs.findings.length) console.log(inputs.findings.join("\n")); process.exitCode = inputs.findings.length ? 1 : 0;
  }
  else if (command === "readiness-bounded") await readiness();
  else if (command === "attest-install") await attestInstall();
} catch (error) {
  const finding = error instanceof Error ? error.message : String(error);
  // Preserve malformed progress unchanged: erasing its pointer could select an unrelated older receipt next time.
  if (evidenceSelected && !["readiness", "qualify-current", "attest-install-current", "inventory-check", "qualify-floor-current"].includes(command)) await writeProgress(undefined, [finding]);
  console.error(`UNQUALIFIED: ${finding}`); process.exitCode = 1;
}
