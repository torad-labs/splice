import assert from "node:assert/strict";
import { chmod, copyFile, mkdir, readdir, readFile, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { resolve } from "node:path";
import { gunzipSync } from "node:zlib";
import { artifact, sha256, type Artifact } from "./qualification";
import type { CurrentInventory } from "./current-inventory";
import type { CurrentReference } from "./current-readiness";
import { capturedCommand } from "./current-command";
import { candidateCommand } from "./candidate-command";
import { resolveCurrentSource } from "./current-source";
import { matchingRustToolchain, assertRustDependencyClosure } from "./current-rust-build";
import { exportProfile } from "./current-client";
import { parseLosslessJson } from "./current-witness";
import { apiBatches, apiRustFlags, apiSourceEntries, compareApiRows, observedApiValues, type ApiBuildProof, type ApiCaseWitness, type ApiExtraction } from "./current-core-api-witness";
import type { ClientProfileProof } from "./current-client-profile";

export async function runCurrentCoreApi(java: string, jar: string, evidence: string, runId: string, lock: CurrentReference, inventory: CurrentInventory): Promise<{ cases: ApiCaseWitness[]; artifacts: Artifact[]; findings: string[] }> {
  const result: { cases: ApiCaseWitness[]; artifacts: Artifact[]; findings: string[] } = { cases: [], artifacts: [], findings: [] };
  const directory = resolve(evidence, `${runId}.current-core-api`), driver = resolve(directory, "driver"), source = resolve(directory, "source");
  await mkdir(resolve(driver, "src"), { recursive: true });
  const save = async (name: string, data: string | Uint8Array) => { const path = resolve(directory, name); await mkdir(resolve(path, ".."), { recursive: true }); await writeFile(path, data); const proof = await artifact(path); result.artifacts.push(proof); return proof; };
  let sourceRepository: string | undefined, worktreeCreated = false;
  try {
    const toolchain = await matchingRustToolchain(directory, true, save);
    sourceRepository = await resolveCurrentSource(lock, lock.sourceRepositoryDefault ?? undefined);
    const checkout = await capturedCommand(["git", "-C", sourceRepository, "worktree", "add", "--detach", source, lock.sourceRevision], directory); assert.equal(checkout.exitCode, 0, checkout.stderr); worktreeCreated = true;
    const before = await capturedCommand(["git", "-C", source, "status", "--porcelain", "--untracked-files=all"], directory); assert.equal(before.exitCode, 0); assert.equal(before.stdout, "");
    const cleanBefore = await save("source-clean-before.txt", before.stdout), identity = await capturedCommand(["git", "-C", source, "rev-parse", "HEAD", "HEAD^{tree}"], directory);
    assert.equal(identity.stdout.trim(), `${lock.sourceRevision}\n${inventory.sourceTree}`); const sourceIdentity = await save("source-identity.txt", identity.stdout);
    const root = resolve(import.meta.dir, "current-api-reference"), driverSources: Artifact[] = [];
    for (const name of await readdir(resolve(root, "src"))) if (name.endsWith(".rs")) driverSources.push(await save(`driver/src/${name}`, await readFile(resolve(root, "src", name))));
    const cargoToml = await save("driver/Cargo.toml", await readFile(resolve(root, "Cargo.toml"))), projectLock = await readFile(resolve(root, "Cargo.lock"), "utf8"), officialLock = await readFile(resolve(source, "codex-rs/Cargo.lock"), "utf8");
    assert.equal(sha256(officialLock), inventory.sourceCargoLockSha256); assertRustDependencyClosure(officialLock, projectLock);
    const cargoLock = await save("driver/Cargo.lock", projectLock), extractions: ApiExtraction[] = [];
    const manifest = JSON.parse(await readFile(resolve(root, "source-extractions.json"), "utf8")); assert.equal(manifest.sourceRevision, lock.sourceRevision);
    for (const [index, record] of manifest.records.entries()) {
      const bytes = await readFile(resolve(source, record.file)), original = bytes.subarray(record.start, record.end);
      assert.equal(sha256(bytes), inventory.files.find(file => file.path === record.file)?.sha256); assert.equal(sha256(original), record.sha256);
      const matches = await Promise.all(driverSources.map(async proof => ({ proof, bytes: await readFile(proof.path) })));
      const copies = matches.filter(candidate => candidate.bytes.indexOf(original) >= 0);
      assert.equal(copies.length, 1, "private source span must have exactly one unchanged compiled copy"); const copy = copies[0]!, localStart = copy.bytes.indexOf(original);
      assert.equal(copy.bytes.indexOf(original, localStart + 1), -1, "private source span has ambiguous copies");
      const completeFile = record.start === 0 && record.end === bytes.length;
      const completeFunction = inventory.units.some(unit => unit.kind === "function_item" && unit.file === record.file && unit.start === record.start && unit.end === record.end && unit.sha256 === record.sha256);
      extractions.push({ kind: completeFile ? "complete-file" : completeFunction ? "complete-function" : "statement-context", sourceFile: record.file, sourceStart: record.start, sourceEnd: record.end, sourceSha256: record.sha256, sourceFileSha256: sha256(bytes), localFile: copy.proof.path, localStart, original: await save(`original-extraction-${index}.rs`, original), local: copy.proof });
    }
    const target = process.env.CODEMODE_CLIENT_TARGET_DIR ?? resolve(homedir(), ".cache/splice/codemode-client-build", lock.sourceRevision);
    const environment: Record<string, string> = { ...process.env as Record<string, string>, RUSTFLAGS: apiRustFlags.join(" "), CARGO_BUILD_JOBS: "4", CARGO_TARGET_DIR: target, LLVM_PROFILE_FILE: resolve(directory, "build-%p.profraw") };
    for (const name of ["CARGO_ENCODED_RUSTFLAGS", "RUSTC_WRAPPER", "RUSTC_WORKSPACE_WRAPPER", "RUSTC", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"]) delete environment[name];
    let build = await capturedCommand(["cargo", "+1.99.1", "build", "--offline", "--locked", "--manifest-path", cargoToml.path], directory, undefined, 1_200_000, environment);
    if (build.exitCode !== 0 && /no matching package|failed to download|attempting to make an HTTP request/i.test(build.stderr)) {
      await save("dependency-cache-miss.json", JSON.stringify(build)); const fetched = await capturedCommand(["cargo", "+1.99.1", "fetch", "--locked", "--manifest-path", cargoToml.path], directory, undefined, 180_000, environment);
      await save("dependency-bootstrap.json", JSON.stringify(fetched)); assert.equal(fetched.exitCode, 0, fetched.stderr);
      build = await capturedCommand(["cargo", "+1.99.1", "build", "--offline", "--locked", "--manifest-path", cargoToml.path], directory, undefined, 1_200_000, environment);
    }
    const command = await save("reference-build.json", JSON.stringify(build, null, 2) + "\n"); assert.equal(build.exitCode, 0, build.stderr);
    const after = await capturedCommand(["git", "-C", source, "status", "--porcelain", "--untracked-files=all"], directory); assert.equal(after.exitCode, 0); assert.equal(after.stdout, ""); const cleanAfter = await save("source-clean-after.txt", after.stdout);
    const binaryPath = resolve(directory, "reference-core-api"); await copyFile(resolve(target, "debug/splice-current-core-api-reference"), binaryPath); await chmod(binaryPath, 0o755); const binary = await artifact(binaryPath); result.artifacts.push(binary);
    const proof: ApiBuildProof = { schema: 1, sourceRevision: lock.sourceRevision, sourceTree: inventory.sourceTree, sourceDirectory: source, sourceRepository, compiler: toolchain.compiler, flags: apiRustFlags, binary, driverSources, cargoToml, cargoLock, command, candidateJar: await artifact(jar), cleanBefore, cleanAfter, sourceIdentity, tools: { cov: await artifact(resolve(toolchain.llvmDirectory, "llvm-cov")), profdata: await artifact(resolve(toolchain.llvmDirectory, "llvm-profdata")) }, extractions };
    const buildProof = await save("build-proof.json", JSON.stringify(proof, null, 2) + "\n");
    for (const batch of apiBatches()) {
      const prefix = batch.caseId.replaceAll("/", "__") + "/", batchDirectory = resolve(directory, prefix); await mkdir(batchDirectory);
      const input = await save(prefix + "jobs.json", JSON.stringify(batch.jobs) + "\n"), profraw = resolve(batchDirectory, "case.profraw");
      const stdin = (side: "reference_input" | "candidate_input") => batch.jobs.map(job => JSON.stringify({ ...job[side], id: job.id })).join("\n") + "\n";
      const sourceRun = await capturedCommand([binary.path], directory, stdin("reference_input"), 180_000, { ...environment, LLVM_PROFILE_FILE: profraw });
      const candidateRun = await capturedCommand(candidateCommand(java, jar, "--current-core-fixture", runId), directory, stdin("candidate_input"), 180_000);
      const reference = await save(prefix + "reference.json", JSON.stringify(sourceRun) + "\n"), candidate = await save(prefix + "candidate.json", JSON.stringify(candidateRun) + "\n");
      assert.equal(sourceRun.exitCode, 0, sourceRun.stderr); assert.equal(candidateRun.exitCode, 0, candidateRun.stderr);
      const expected = sourceRun.stdout.trimEnd().split("\n").map(parseLosslessJson), actual = candidateRun.stdout.trimEnd().split("\n").map(parseLosslessJson); compareApiRows(batch.jobs, expected, actual, runId);
      const profdata = resolve(batchDirectory, "case.profdata"), merged = await capturedCommand([proof.tools.profdata.path, "merge", "-sparse", profraw, "-o", profdata], directory); assert.equal(merged.exitCode, 0, merged.stderr);
      const exported = await exportProfile([proof.tools.cov.path, "export", binary.path, `-instr-profile=${profdata}`, "-skip-expansions"], directory, resolve(batchDirectory, "llvm-cov.json.gz")); result.artifacts.push(exported);
      const entries = await apiSourceEntries(JSON.parse(gunzipSync(await readFile(exported.path)).toString()), inventory, proof), mappings = await save(prefix + "mappings.json", JSON.stringify(entries) + "\n");
      const profile: ClientProfileProof = { schema: 1, profraw: await artifact(profraw), profdata: await artifact(profdata), exported, mappings }; result.artifacts.push(profile.profraw, profile.profdata);
      const profileProof = await save(prefix + "profile.json", JSON.stringify(profile) + "\n");
      result.cases.push({ producer: "core-api-llvm", caseId: batch.caseId, inputSha256: batch.inputSha256, productionEntrypoint: batch.productionEntrypoint, referenceFunctions: entries.linked, referenceValues: await observedApiValues(batch.jobs, expected, actual, inventory, proof), input, reference, candidate, build: buildProof, profile: profileProof });
    }
    assert.equal(result.cases.length, apiBatches().length); assert.equal(sha256(await readFile(jar)), proof.candidateJar.sha256, "candidate changed during actual-crate API verification");
  } catch (error) { result.findings.push(error instanceof Error ? error.message : String(error)); }
  finally { if (worktreeCreated) { const cleanup = await capturedCommand(["git", "-C", sourceRepository!, "worktree", "remove", "--force", source], directory); if (cleanup.exitCode !== 0) result.findings.push(`actual-crate source cleanup failed: ${cleanup.stderr}`); } }
  return result;
}
