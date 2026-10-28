import assert from "node:assert/strict";
import { mkdir, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { basename, dirname, resolve } from "node:path";
import { gzipSync, gunzipSync } from "node:zlib";
import { artifact, sha256, type Artifact, type Identity } from "./qualification";
import { capturedCommand } from "./current-command";
import { llvmSourceEntries, type ClientProfileProof } from "./current-client-profile";
import { controlScenarios, mcpScenarios } from "./current-cli/cases";
import { runCliScenario } from "./current-cli/run";
import { compareGatewayScenario } from "./current-cli/gateway";
import { compareControlPackets, sourceGatewayInputBytes } from "./current-cli/compare";
import { isTitleRequest } from "./current-cli/scripted-upstream";
import { nativeCompletion } from "./current-cli/native-events";
import { candidateCommand } from "./candidate-command";
import type { CliRun } from "./current-cli/types";
import type { CurrentInventory } from "./current-inventory";
import type { CurrentReference } from "./current-readiness";
import type { FunctionWitness } from "./current-witness";

export const sourceCliFlags = ["-Cinstrument-coverage", "-Cdebuginfo=1", "-Copt-level=0", "-Cdebug-assertions=no", "-Coverflow-checks=no"];
export type CliSourceBuildProof = {
  schema: 1; sourceRevision: string; sourceTree: string; sourceDirectory: string; sourceRepository: string;
  compiler: string; flags: string[]; binary: Artifact; command: Artifact;
  tools: { cov: Artifact; profdata: Artifact }; cleanBefore: Artifact; cleanAfter: Artifact; sourceIdentity: Artifact;
  overlay: { originalLock: Artifact; derivedLock: Artifact; metadata: Artifact; patch: Artifact; reconciliation: Artifact };
};
export type CliSourceCaseWitness = {
  producer: "core-cli-llvm"; caseId: string; inputSha256: string; productionEntrypoint: string;
  referenceFunctions: FunctionWitness[]; input: Artifact; reference: Artifact; candidate: Artifact; build: Artifact; profile: Artifact;
  sourceFiles: Artifact[]; candidateFiles: Artifact[];
};
type Metadata = { workspace_root: string; packages: Array<{ name: string; version: string; source: string | null; manifest_path: string }> };
const sourceNames = ["case-input.json", "catalog.json", "launch.json", "requests.json", "frames.json", "observations.json", "native-events.jsonl", "client-state/config.toml", "terminal/terminal.raw", "terminal/terminal-events.jsonl", "terminal/terminal-screen.txt", "terminal/process.json"];
const candidateNames = ["scenario.json", "launch.json", "process.json", "stderr.txt", "comparison.json"];

export function sourceCliPlans() {
  return [...controlScenarios, ...mcpScenarios].map(scenario => ({ caseId: `cli-source/${scenario.id}`, inputSha256: sha256(JSON.stringify(scenario)),
    productionEntrypoint: "splice.provider.codex.diagnostics.current.CodeModeGatewayScenarioProbe.run", scenario }));
}
export function sourceCliCaseArtifacts(witness: CliSourceCaseWitness): Artifact[] {
  return [witness.input, witness.reference, witness.candidate, witness.build, witness.profile, ...witness.sourceFiles, ...witness.candidateFiles];
}
/** Export every inventoried Rust source file, including context and native files.
 * This avoids external-library expansion without selecting a named function/crate. */
export function sourceCliExportArgs(build: Pick<CliSourceBuildProof, "binary" | "sourceDirectory" | "tools">, inventory: CurrentInventory, profdata: string): string[] {
  const files = [...new Set(inventory.files.filter(file => file.path.endsWith(".rs")).map(file => resolve(build.sourceDirectory, file.path)))].sort();
  assert.ok(files.length, "current source inventory has no Rust source files");
  return [build.tools.cov.path, "export", build.binary.path, `-instr-profile=${profdata}`, "-skip-expansions", ...files];
}
async function checked(proof: Artifact): Promise<Buffer> {
  const bytes = await readFile(proof.path); assert.equal(bytes.length, proof.bytes); assert.equal(sha256(bytes), proof.sha256); return bytes;
}
async function git(repository: string, ...args: string[]): Promise<string> {
  const command = await capturedCommand(["git", "-C", repository, ...args], import.meta.dir); assert.equal(command.exitCode, 0, command.stderr); return command.stdout;
}

/** Reproduce the release-tag lock mismatch repair without changing an external
 * package object, edge, comment or byte outside exact local version lines. */
export function reconcileSourceCliLock(original: string, metadata: Metadata, officialVersions: Map<string, string>, sourceDirectory: string) {
  const packages = metadata.packages.filter(pkg => pkg.source === null), local = new Map(packages.map(pkg => [pkg.name, pkg]));
  assert.equal(local.size, packages.length, "duplicate metadata local package");
  assert.equal(metadata.workspace_root, resolve(sourceDirectory, "codex-rs"));
  const changed: Array<{ name: string; from: string; to: string }> = [];
  const derived = original.split(/(?=^\[\[package\]\])/m).map(block => {
    if (!block.startsWith("[[package]]")) return block;
    const pkg = (Bun.TOML.parse(block) as any).package[0]; if (pkg.source) return block;
    const declared = local.get(pkg.name); assert.ok(declared, `unrecognized local package ${pkg.name}`);
    assert.ok(declared.manifest_path.startsWith(resolve(sourceDirectory) + "/"), "local manifest escaped immutable source");
    assert.equal(declared.version, officialVersions.get(pkg.name), "metadata version differs from official manifest");
    assert.equal(pkg.version, "0.0.0", "release reconciliation only replaces upstream placeholder versions");
    assert.match(declared.version, /^\d+\.\d+\.\d+$/);
    changed.push({ name: pkg.name, from: pkg.version, to: declared.version });
    const replacement = block.replace(/^version = "0\.0\.0"$/m, `version = "${declared.version}"`);
    assert.notEqual(replacement, block); return replacement;
  }).join("");
  assert.equal(changed.length, local.size, "local lock and metadata package sets differ");
  const before = Bun.TOML.parse(original) as any, after = Bun.TOML.parse(derived) as any;
  assert.equal(before.package.length, after.package.length);
  assert.deepEqual(after.package.filter((pkg: any) => pkg.source), before.package.filter((pkg: any) => pkg.source), "external package fields and dependency edges changed");
  return { derived, changed: changed.sort((a, b) => a.name.localeCompare(b.name)) };
}

export async function assertSourceCliBuild(build: CliSourceBuildProof, inventory: CurrentInventory): Promise<void> {
  assert.equal(build.schema, 1); assert.equal(build.sourceRevision, inventory.sourceRevision); assert.equal(build.sourceTree, inventory.sourceTree);
  assert.match(build.compiler, /commit-hash: 58cf8f9c35b355199727627d07ae34a4e2846d67\b/); assert.deepEqual(build.flags, sourceCliFlags);
  for (const proof of [build.binary, build.command, build.tools.cov, build.tools.profdata, build.cleanBefore, build.cleanAfter, build.sourceIdentity, ...Object.values(build.overlay)]) await checked(proof);
  const command = JSON.parse((await checked(build.command)).toString());
  assert.equal(command.exitCode, 0); assert.ok(Number.isSafeInteger(command.pid) && command.pid > 0);
  assert.ok(command.argv.includes("build") && command.argv.includes("--locked") && command.argv.includes("codex-cli") && command.argv.includes("codex"), "source CLI must be built as the locked official application");
  assert.equal((await checked(build.sourceIdentity)).toString().trim(), `${inventory.sourceRevision}\n${inventory.sourceTree}`);
  assert.equal((await git(build.sourceDirectory, "rev-parse", "HEAD")).trim(), inventory.sourceRevision);
  assert.equal((await git(build.sourceDirectory, "rev-parse", "HEAD^{tree}")).trim(), inventory.sourceTree);
  const original = (await checked(build.overlay.originalLock)).toString(), derived = (await checked(build.overlay.derivedLock)).toString();
  assert.equal(original, await git(build.sourceRepository, "show", `${inventory.sourceRevision}:codex-rs/Cargo.lock`));
  assert.equal(sha256(original), inventory.sourceCargoLockSha256);
  const metadata: Metadata = JSON.parse((await checked(build.overlay.metadata)).toString()), versions = new Map<string, string>();
  const workspace = Bun.TOML.parse(await git(build.sourceRepository, "show", `${inventory.sourceRevision}:codex-rs/Cargo.toml`)) as any;
  for (const pkg of metadata.packages.filter(pkg => pkg.source === null)) {
    assert.ok(pkg.manifest_path.startsWith(resolve(build.sourceDirectory) + "/"));
    const manifestPath = pkg.manifest_path.slice(resolve(build.sourceDirectory).length + 1);
    const manifest = Bun.TOML.parse(await git(build.sourceRepository, "show", `${inventory.sourceRevision}:${manifestPath}`)) as any;
    assert.equal(manifest.package.name, pkg.name);
    const version = typeof manifest.package.version === "string" ? manifest.package.version : manifest.package.version.workspace === true ? workspace.workspace.package.version : undefined;
    assert.equal(typeof version, "string"); versions.set(pkg.name, version);
  }
  const repair = reconcileSourceCliLock(original, metadata, versions, build.sourceDirectory); assert.equal(derived, repair.derived, "lock overlay was not the exact declared local-version repair");
  const receipt = JSON.parse((await checked(build.overlay.reconciliation)).toString());
  assert.equal(receipt.sourceRevision, inventory.sourceRevision); assert.equal(receipt.originalLockSha256, sha256(original)); assert.equal(receipt.derivedLockSha256, sha256(derived));
  assert.deepEqual([...receipt.changedWorkspaceVersions].sort((a, b) => a.name.localeCompare(b.name)), repair.changed);
  const allowedStatus = " M codex-rs/Cargo.lock\n", before = (await checked(build.cleanBefore)).toString();
  assert.ok(before === "" || before === allowedStatus, "source was dirty before the CLI build");
  assert.equal((await checked(build.cleanAfter)).toString(), allowedStatus, "CLI build changed tracked source");
  assert.equal(await git(build.sourceDirectory, "status", "--porcelain", "--untracked-files=all"), allowedStatus, "current source differs from the audited lock-only overlay");
  assert.equal((await readFile(resolve(build.sourceDirectory, "codex-rs/Cargo.lock"))).toString(), derived);
  assert.equal(await git(build.sourceDirectory, "diff", "--binary", "--", "codex-rs/Cargo.lock"), (await checked(build.overlay.patch)).toString());
}

async function exportSourceProfile(source: CliRun, build: CliSourceBuildProof, inventory: CurrentInventory, directory: string): Promise<{ proof: Artifact; functions: FunctionWitness[] }> {
  const rawDirectory = resolve(source.directory, "source-profile"), rawPath = resolve(rawDirectory, `${source.cliProcess.pid}.profraw`);
  assert.deepEqual((await readdir(rawDirectory)).sort(), [basename(rawPath)], "source counter input is not isolated to the attested CLI PID");
  const merged = resolve(directory, "profile.profdata");
  const merge = await capturedCommand([build.tools.profdata.path, "merge", "-sparse", rawPath, "-o", merged], directory); assert.equal(merge.exitCode, 0, merge.stderr);
  const exported = await capturedCommand(sourceCliExportArgs(build, inventory, merged), directory, undefined, 180_000); assert.equal(exported.exitCode, 0, exported.stderr);
  const decoded = Buffer.from(exported.stdout), bytes = gzipSync(decoded), path = resolve(directory, "profile.json.gz"); await writeFile(path, bytes);
  const entries = await llvmSourceEntries(JSON.parse(decoded.toString()), inventory, build.sourceDirectory, build.sourceRepository);
  const mappingPath = resolve(directory, "mappings.json"); await writeFile(mappingPath, JSON.stringify(entries, null, 2) + "\n");
  const proof: ClientProfileProof = { schema: 1, profraw: await artifact(rawPath), profdata: await artifact(merged), mappings: await artifact(mappingPath), exported: { ...await artifact(path), decodedSha256: sha256(decoded), decodedBytes: decoded.length } };
  const proofPath = resolve(directory, "profile-proof.json"); await writeFile(proofPath, JSON.stringify(proof, null, 2) + "\n");
  return { proof: await artifact(proofPath), functions: entries.linked };
}

/** Additional actual-source runs; signed release CLI controls remain mandatory. */
export async function executeSourceCliWitnesses(options: { java: string; jar: Identity; nativeHost: Identity; lock: CurrentReference; inventory: CurrentInventory; build: Artifact; evidence: string; runId: string }): Promise<CliSourceCaseWitness[]> {
  const build: CliSourceBuildProof = JSON.parse((await checked(options.build)).toString()); await assertSourceCliBuild(build, options.inventory);
  assert.equal(sha256(await readFile(options.nativeHost.path)), options.lock.nativeHostSha256);
  assert.equal(dirname(build.binary.path), dirname(options.nativeHost.path), "instrumented CLI and verified official host must be frozen siblings");
  const catalog = Buffer.from(await git(build.sourceRepository, "show", `${options.inventory.sourceRevision}:codex-rs/models-manager/models.json`));
  const witnesses: CliSourceCaseWitness[] = []; await mkdir(options.evidence, { recursive: true });
  for (const plan of sourceCliPlans()) {
    const directory = resolve(options.evidence, `${options.runId}.${plan.scenario.id}.source-cli`); await mkdir(directory);
    const sourceDirectory = resolve(directory, "reference"), candidateDirectory = resolve(directory, "candidate");
    const source = await runCliScenario({ reference: { cli: { path: build.binary.path, sha256: build.binary.sha256 }, host: options.nativeHost, sourceRevision: build.sourceRevision }, scenario: plan.scenario,
      directory: sourceDirectory, catalog, sourceProfilePattern: resolve(sourceDirectory, "source-profile/%p.profraw") });
    await compareGatewayScenario({ java: options.java, jar: options.jar, source, directory: candidateDirectory });
    const profile = await exportSourceProfile(source, build, options.inventory, directory);
    const names = [...sourceNames, ...(plan.scenario.mcp ? ["mcp-peer.jsonl"] : [])];
    const witness: CliSourceCaseWitness = { producer: "core-cli-llvm", caseId: plan.caseId, inputSha256: plan.inputSha256, productionEntrypoint: plan.productionEntrypoint,
      referenceFunctions: profile.functions, input: await artifact(resolve(sourceDirectory, "case-input.json")), reference: await artifact(resolve(sourceDirectory, "result.json")), candidate: await artifact(resolve(candidateDirectory, "stdout.json")),
      build: options.build, profile: profile.proof, sourceFiles: await Promise.all(names.map(name => artifact(resolve(sourceDirectory, name)))), candidateFiles: await Promise.all(candidateNames.map(name => artifact(resolve(candidateDirectory, name)))) };
    const path = resolve(directory, "case-witness.json"); await writeFile(path, JSON.stringify(witness, null, 2) + "\n"); witnesses.push(witness);
  }
  return witnesses;
}

export async function sourceCliWitnessFindings(cases: CliSourceCaseWitness[], inventory: CurrentInventory, runId: string, candidateJar: Identity, lock: CurrentReference): Promise<string[]> {
  const findings: string[] = [], seen = new Set<string>(), builds = new Map<string, CliSourceBuildProof>();
  for (const witness of cases) try {
    const plan = sourceCliPlans().find(plan => plan.caseId === witness.caseId); assert.ok(plan, "unrecognized actual-source CLI case");
    assert.ok(!seen.has(witness.caseId), "duplicate actual-source CLI case"); seen.add(witness.caseId);
    assert.equal(witness.producer, "core-cli-llvm"); assert.equal(witness.inputSha256, plan.inputSha256); assert.equal(witness.productionEntrypoint, plan.productionEntrypoint);
    for (const proof of sourceCliCaseArtifacts(witness)) await checked(proof);
    let build = builds.get(witness.build.sha256);
    if (!build) { build = JSON.parse((await checked(witness.build)).toString()); await assertSourceCliBuild(build!, inventory); builds.set(witness.build.sha256, build!); }
    assert.ok(build);
    const source: CliRun = JSON.parse((await checked(witness.reference)).toString()), candidate = JSON.parse((await checked(witness.candidate)).toString());
    assert.deepEqual(source.scenario, plan.scenario); assert.equal(source.inputSha256, plan.inputSha256); assert.match(source.nonce, /^[a-f0-9]{32}$/);
    assert.equal((await checked(witness.input)).toString(), JSON.stringify(plan.scenario) + "\n");
    assert.equal(basename(dirname(source.directory)), `${runId}.${plan.scenario.id}.source-cli`, "source CLI witness belongs to a different qualification run");
    assert.equal(witness.reference.path, resolve(source.directory, "result.json")); assert.equal(witness.input.path, resolve(source.directory, "case-input.json"));
    assert.equal(source.reference.sourceRevision, inventory.sourceRevision); assert.deepEqual(source.reference.cli, { path: build.binary.path, sha256: build.binary.sha256 });
    assert.equal(source.reference.host.sha256, lock.nativeHostSha256); assert.equal(dirname(source.reference.host.path), dirname(build.binary.path));
    assert.equal(sha256(await readFile(source.reference.host.path)), lock.nativeHostSha256);
    const sourceFile = async (name: string) => { const files = witness.sourceFiles.filter(file => file.path === resolve(source.directory, name)); assert.equal(files.length, 1, `missing or duplicated source CLI raw artifact ${name}`); return checked(files[0]!); };
    const names = [...sourceNames, ...(plan.scenario.mcp ? ["mcp-peer.jsonl"] : [])]; assert.equal(witness.sourceFiles.length, names.length); for (const name of names) await sourceFile(name);
    const launch = JSON.parse((await sourceFile("launch.json")).toString());
    assert.equal(launch.argv[0], build.binary.path); assert.ok(launch.argv.includes("--no-daemon") && launch.argv.includes("--no-alt-screen") && !launch.argv.includes("exec") && !launch.argv.includes("app-server"), "source proof requires the real interactive PTY entrypoint");
    assert.equal(launch.modelBackend, "deterministic-loopback-no-inference"); assert.deepEqual(launch.terminal, { implementation: "Bun.Terminal", emulator: "@xterm/headless@5.5.0" });
    assert.equal(launch.nonce, source.nonce); assert.equal(launch.inputSha256, plan.inputSha256); assert.equal(launch.catalogSha256, sha256(await sourceFile("catalog.json")));
    assert.equal((await sourceFile("catalog.json")).toString(), await git(build.sourceRepository, "show", `${inventory.sourceRevision}:codex-rs/models-manager/models.json`));
    assert.equal(launch.sourceProfilePattern, resolve(source.directory, "source-profile/%p.profraw"));
    const config = (await sourceFile("client-state/config.toml")).toString(); assert.match(config, /base_url = "http:\/\/127\.0\.0\.1:\d+\/v1"/); assert.ok(config.includes("requires_openai_auth = false"));
    const processProof = JSON.parse((await sourceFile("terminal/process.json")).toString());
    assert.ok(Number.isSafeInteger(source.cliProcess.pid) && source.cliProcess.pid > 0); assert.equal(source.cliProcess.exit, 0); assert.match(source.cliProcess.startTicks, /^\d+$/); assert.equal(source.cliProcess.sha256, build.binary.sha256);
    for (const key of ["pid", "sha256", "startTicks", "exit"] as const) assert.equal(processProof[key], source.cliProcess[key]);
    assert.ok(source.hostProcesses.length && source.hostProcesses.every(host => host.pid > 0 && host.sha256 === lock.nativeHostSha256));
    assert.deepEqual(nativeCompletion(resolve(source.directory, "client-state")), source.completion); assert.equal(source.completion.finalText, `CODEMODE_CURRENT_${source.nonce}`);
    assert.ok((await sourceFile("native-events.jsonl")).equals(await readFile(source.completion.sourceFile)));
    const requests = JSON.parse((await sourceFile("requests.json")).toString()), tasks = requests.filter((body: any) => !isTitleRequest(body));
    assert.equal(tasks.length, plan.scenario.actions.length + 1); assert.equal(source.taskRequests, tasks.length); assert.equal(source.auxiliaryRequests, requests.length - tasks.length);
    assert.deepEqual(source.observations, tasks.map((body: any, index: number) => ({ request: index + 1, input: body.input }))); assert.deepEqual(JSON.parse((await sourceFile("observations.json")).toString()), source.observations);
    const candidateDirectory = dirname(witness.candidate.path), candidateFile = async (name: string) => { const files = witness.candidateFiles.filter(file => file.path === resolve(candidateDirectory, name)); assert.equal(files.length, 1); return checked(files[0]!); };
    assert.equal(witness.candidateFiles.length, candidateNames.length); for (const name of candidateNames) await candidateFile(name);
    assert.equal((await candidateFile("scenario.json")).toString(), sourceGatewayInputBytes(source));
    const candidateLaunch = JSON.parse((await candidateFile("launch.json")).toString()), candidateProcess = JSON.parse((await candidateFile("process.json")).toString());
    assert.deepEqual(candidateLaunch.jar, candidateJar); assert.equal(sha256(await readFile(candidateJar.path)), candidateJar.sha256); assert.equal(candidateLaunch.nonce, source.nonce);
    assert.deepEqual(candidateLaunch.argv.slice(1), candidateCommand("java", candidateJar.path, "--native-gateway-fixture", "runtime.current-scenario", source.nonce, candidateDirectory, source.reference.host.path).slice(1));
    assert.equal(candidateProcess.exitCode, 0); assert.equal(candidateProcess.failure, undefined); assert.ok(Number.isSafeInteger(candidateProcess.pid) && candidateProcess.pid > 0); assert.equal(candidate.pid, candidateProcess.pid);
    assert.equal(candidate.caseId, "runtime.current-scenario"); assert.equal(candidate.scope, "real-native-runtime-through-production-gateway"); assert.ok(candidate.outcomes.length && candidate.outcomes.every((outcome: string) => outcome === "Success"));
    const stderr = (await candidateFile("stderr.txt")).toString(); assert.ok(stderr.split("\n").some(line => { try { const event = JSON.parse(line); return event.type === "parity/identity" && Number.isSafeInteger(event.nativeHostPid) && event.nativeHostPid > 0; } catch { return false; } }));
    compareControlPackets(source, candidate);
    const profile: ClientProfileProof = JSON.parse((await checked(witness.profile)).toString()); assert.equal(profile.schema, 1);
    assert.equal(profile.profraw.path, resolve(source.directory, `source-profile/${source.cliProcess.pid}.profraw`));
    for (const proof of [profile.profraw, profile.profdata, profile.mappings, profile.exported]) await checked(proof);
    const saved = gunzipSync(await checked(profile.exported)); assert.equal(saved.length, profile.exported.decodedBytes); assert.equal(sha256(saved), profile.exported.decodedSha256);
    const merged = `${profile.profdata.path}.verify-${crypto.randomUUID()}`;
    const merge = await capturedCommand([build.tools.profdata.path, "merge", "-sparse", profile.profraw.path, "-o", merged], import.meta.dir); assert.equal(merge.exitCode, 0, merge.stderr);
    try {
      const exported = await capturedCommand(sourceCliExportArgs(build, inventory, merged), import.meta.dir, undefined, 180_000); assert.equal(exported.exitCode, 0, exported.stderr);
      const actual = await llvmSourceEntries(JSON.parse(exported.stdout), inventory, build.sourceDirectory, build.sourceRepository), stored = await llvmSourceEntries(JSON.parse(saved.toString()), inventory, build.sourceDirectory, build.sourceRepository);
      assert.deepEqual(stored, actual); assert.deepEqual(JSON.parse((await checked(profile.mappings)).toString()), actual); assert.deepEqual(witness.referenceFunctions, actual.linked);
    } finally { await rm(merged, { force: true }); }
  } catch (error) { findings.push(`${witness.caseId}: invalid actual-source CLI witness: ${error instanceof Error ? error.message : String(error)}`); }
  for (const plan of sourceCliPlans()) if (!seen.has(plan.caseId)) findings.push(`mandatory actual-source CLI case was omitted: ${plan.caseId}`);
  return findings;
}
