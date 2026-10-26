import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { chmod, copyFile, mkdir, readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { homedir } from "node:os";
import { gunzipSync } from "node:zlib";
import { artifact, sha256, type Artifact } from "./qualification";
import type { CurrentInventory } from "./current-inventory";
import type { CurrentReference } from "./current-readiness";
import type { ExecutedVerifier } from "./current-coverage";
import { resolveCurrentSource } from "./current-source";
import { capturedCommand } from "./current-command";
import { matchingRustToolchain, assertRustDependencyClosure, compileCandidateWrapper } from "./current-rust-build";
import { currentClientCases, expandedClientInput } from "./current-client/cases";
import { clientCaseIdentity, llvmSourceEntries, assertCandidateClientOrigin, clientAdapterMain, type ClientBuildProof, type ClientCaseWitness, type ClientProfileProof } from "./current-client-profile";
import { parseLosslessJson } from "./current-witness";
import { compareClientObservations, readPeerTrace } from "./current-client-observable";

export { currentClientCases } from "./current-client/cases";
const flags = ["-Cinstrument-coverage", "-Cdebuginfo=1", "-Copt-level=0", "-Cdebug-assertions=no", "-Coverflow-checks=no"];
export async function exportProfile(argv: string[], directory: string, path: string): Promise<Artifact & { decodedSha256: string; decodedBytes: number }> {
  const child = Bun.spawn(argv, { cwd: directory, stdin: "ignore", stdout: "pipe", stderr: "pipe" }), hash = createHash("sha256"); let bytes = 0;
  const counted = new TransformStream<Uint8Array, Uint8Array>({ transform(chunk, controller) { hash.update(chunk); bytes += chunk.length; controller.enqueue(chunk); } });
  const timeout = setTimeout(() => child.kill("SIGKILL"), 180_000);
  try {
    const compression = new CompressionStream("gzip") as unknown as ReadableWritablePair<Uint8Array, Uint8Array>;
    const compressed = child.stdout.pipeThrough(counted).pipeThrough(compression);
    const [exitCode, _written, stderr] = await Promise.all([child.exited, Bun.write(path, new Response(compressed)), new Response(child.stderr).text()]);
    assert.equal(exitCode, 0, stderr); return { ...await artifact(path), decodedSha256: hash.digest("hex"), decodedBytes: bytes };
  } finally { clearTimeout(timeout); }
}
export async function runCurrentClient(java: string, jar: string, nativeHost: string, evidence: string, runId: string, lock: CurrentReference, inventory: CurrentInventory): Promise<ExecutedVerifier> {
  const result: ExecutedVerifier = { id: "current.rust-client", runId, startedAt: new Date().toISOString(), finishedAt: "", inventorySha256: inventory.denominatorSha256,
    sourceRevision: lock.sourceRevision, candidateJarSha256: sha256(await readFile(jar)), nativeHostSha256: lock.nativeHostSha256, state: "failed", findings: [], artifacts: [], cases: [] };
  const directory = resolve(evidence, `${runId}.current-client`), driver = resolve(directory, "driver"), source = resolve(directory, "source");
  await mkdir(driver, { recursive: true });
  const save = async (name: string, data: string | Uint8Array) => { const path = resolve(directory, name); await mkdir(resolve(path, ".."), { recursive: true }); await writeFile(path, data); const proof = await artifact(path); result.artifacts.push(proof); return proof; };
  let sourceRepository: string | undefined, worktreeCreated = false;
  try {
    assert.equal(sha256(await readFile(nativeHost)), lock.nativeHostSha256, "actual Rust-client reference host is not the verified frozen executable");
    const toolchain = await matchingRustToolchain(directory, true, save);
    sourceRepository = await resolveCurrentSource(lock, lock.sourceRepositoryDefault ?? undefined);
    const checkout = await capturedCommand(["git", "-C", sourceRepository, "worktree", "add", "--detach", source, lock.sourceRevision], directory); assert.equal(checkout.exitCode, 0, checkout.stderr); worktreeCreated = true;
    const before = await capturedCommand(["git", "-C", source, "status", "--porcelain", "--untracked-files=all"], directory); assert.equal(before.exitCode, 0); assert.equal(before.stdout, "");
    const cleanBefore = await save("source-clean-before.txt", before.stdout);
    const revision = await capturedCommand(["git", "-C", source, "rev-parse", "HEAD", "HEAD^{tree}"], directory); assert.equal(revision.stdout.trim(), `${lock.sourceRevision}\n${inventory.sourceTree}`);
    const sourceIdentity = await save("source-identity.txt", revision.stdout);
    const manifest = await save("driver/Cargo.toml", await readFile(resolve(import.meta.dir, "current-client/Cargo.toml")));
    const sourceDriver = await save("driver/src/main.rs", await readFile(resolve(import.meta.dir, "current-client/main.rs")));
    const lockBytes = await readFile(resolve(import.meta.dir, "current-client/Cargo.lock"), "utf8"), officialLock = await readFile(resolve(source, "codex-rs/Cargo.lock"), "utf8");
    assert.equal(sha256(officialLock), inventory.sourceCargoLockSha256); assertRustDependencyClosure(officialLock, lockBytes);
    const cargoLock = await save("driver/Cargo.lock", lockBytes);
    const target = process.env.CODEMODE_CLIENT_TARGET_DIR ?? resolve(homedir(), ".cache/splice/codemode-client-build", lock.sourceRevision);
    const environment: Record<string, string> = { ...process.env as Record<string, string>, RUSTFLAGS: flags.join(" "), CARGO_BUILD_JOBS: "4", CARGO_TARGET_DIR: target, LLVM_PROFILE_FILE: resolve(directory, "build-%p.profraw") };
    for (const name of ["CARGO_ENCODED_RUSTFLAGS", "RUSTC_WRAPPER", "RUSTC_WORKSPACE_WRAPPER", "RUSTC", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"]) delete environment[name];
    let build = await capturedCommand(["cargo", "+1.99.1", "build", "--offline", "--locked", "--manifest-path", manifest.path], directory, undefined, 1_200_000, environment);
    if (build.exitCode !== 0 && /no matching package|failed to download|attempting to make an HTTP request/i.test(build.stderr)) {
      await save("dependency-cache-miss.json", JSON.stringify(build));
      const fetch = await capturedCommand(["cargo", "+1.99.1", "fetch", "--locked", "--manifest-path", manifest.path], directory, undefined, 180_000, environment); await save("dependency-bootstrap.json", JSON.stringify(fetch)); assert.equal(fetch.exitCode, 0, fetch.stderr);
      build = await capturedCommand(["cargo", "+1.99.1", "build", "--offline", "--locked", "--manifest-path", manifest.path], directory, undefined, 1_200_000, environment);
    }
    await save("reference-build.json", JSON.stringify(build, null, 2) + "\n"); assert.equal(build.exitCode, 0, build.stderr);
    const after = await capturedCommand(["git", "-C", source, "status", "--porcelain", "--untracked-files=all"], directory); assert.equal(after.exitCode, 0); assert.equal(after.stdout, "", "reference source was modified during build");
    const cleanAfter = await save("source-clean-after.txt", after.stdout);
    // The wrappers are integrations-codemode's parityClient source set, where that module's check compiles them too.
    const wrapperDirectory = resolve(import.meta.dir, "../../integrations/codemode/src/parityClient/kotlin");
    const wrapperSources = await Promise.all(["ClientCandidate", "ClientCandidateLogical", "ClientCandidateWait", "ClientCandidateOpening"].map(async name => save(`${name}.kt`, await readFile(resolve(wrapperDirectory, `${name}.kt`)))));
    const wrapper = await compileCandidateWrapper(java, jar, wrapperSources.map(source => source.path), directory);
    result.artifacts.push(wrapper.compilerArchive, wrapper.build, await artifact(wrapper.jar));
    const binaryPath = resolve(directory, "reference-client"); await copyFile(resolve(target, "debug/splice-current-client-witness"), binaryPath); await chmod(binaryPath, 0o755);
    const binary = await artifact(binaryPath); result.artifacts.push(binary);
    const buildProof: ClientBuildProof = { schema: 1, sourceRevision: lock.sourceRevision, sourceTree: inventory.sourceTree, sourceDirectory: source, sourceRepository,
      compiler: toolchain.compiler, flags, binary, driver: sourceDriver, cargoToml: manifest, cargoLock, tools: { cov: await artifact(resolve(toolchain.llvmDirectory, "llvm-cov")), profdata: await artifact(resolve(toolchain.llvmDirectory, "llvm-profdata")) },
      wrapper: await artifact(wrapper.jar), wrapperSources, wrapperBuild: wrapper.build, wrapperEntries: wrapper.entries, candidateJar: await artifact(jar), cleanBefore, cleanAfter, sourceIdentity };
    const buildArtifact = await save("build-proof.json", JSON.stringify(buildProof, null, 2) + "\n");
    const fakeHost = resolve(import.meta.dir, "current-client/fake-host.ts"), completionHost = resolve(import.meta.dir, "current-client/completion-fake-host.ts"), hostLaunch = resolve(import.meta.dir, "current-client/host-launch.sh");
    for (const path of [fakeHost, completionHost, hostLaunch]) { await chmod(path, 0o755); result.artifacts.push(await artifact(path)); }
    for (const case_ of currentClientCases) {
      const input = expandedClientInput(case_, runId), identity = clientCaseIdentity(input), slug = identity.caseId.replaceAll("/", "__"), caseDirectory = resolve(directory, slug); await mkdir(caseDirectory);
      const prefix = `${slug}/`, inputArtifact = await save(`${prefix}input.json`, JSON.stringify(input, null, 2) + "\n");
      const peer = case_.fault_mode === "callback-completion-boundary" ? completionHost : case_.fault_mode ? fakeHost : hostLaunch, profraw = resolve(caseDirectory, "case.profraw");
      const referenceTrace = await save(`${prefix}reference.peer.jsonl`, ""), candidateTrace = await save(`${prefix}candidate.peer.jsonl`, ""), referencePolicy = await save(`${prefix}reference.launch.jsonl`, ""), candidatePolicy = await save(`${prefix}candidate.launch.jsonl`, "");
      const caseEnvironment: Record<string, string> = { ...environment, SPLICE_HOST_PROGRAM: nativeHost, SPLICE_HOST_RECORD_SCRIPT: resolve(import.meta.dir, "current-client/launch-policy.ts"), SPLICE_HOST_PID_FILE: resolve(caseDirectory, "reference.host-pid"), SPLICE_GRPC_TRACE_PATH: referenceTrace.path, SPLICE_HOST_LAUNCH_POLICY_PATH: referencePolicy.path, LLVM_PROFILE_FILE: profraw, ...(case_.fault_mode ? { SPLICE_GRPC_FAULT: case_.fault_mode } : {}) };
      if (input.provider === "process") for (const name of ["CODEX_EXEC_SERVER_NOISE_AUTH_TOKEN", "NODE_REPL_AUTH_TOKEN", "CODEX_GUARDIAN_DECISIONS_API_KEY", "OPENAI_FEDERATION_RULE_ID", "OPENAI_IDENTITY_TOKEN_FILE", "OPENAI_WORKLOAD_IDENTITY_CONTEXT"]) for (const variant of [name, name.toLowerCase(), name[0] + name.slice(1).toLowerCase()]) caseEnvironment[variant] = "nonsecret-parity-sentinel";
      const timeout = (input.case_timeout_ms ?? 30_000) + 15_000;
      const reference = await capturedCommand([binary.path, peer], directory, JSON.stringify(input), timeout, caseEnvironment);
      const candidateEnvironment: Record<string, string> = { ...caseEnvironment, SPLICE_HOST_PID_FILE: resolve(caseDirectory, "candidate.host-pid"), SPLICE_GRPC_TRACE_PATH: candidateTrace.path, SPLICE_HOST_LAUNCH_POLICY_PATH: candidatePolicy.path }; delete candidateEnvironment.LLVM_PROFILE_FILE;
      const adapter = input.adapter ?? "connection", candidate = await capturedCommand([java, "-cp", `${wrapper.jar}:${jar}`, clientAdapterMain[adapter as keyof typeof clientAdapterMain], peer], directory, JSON.stringify(input), timeout, candidateEnvironment);
      const referenceArtifact = await save(`${prefix}reference.json`, JSON.stringify(reference, null, 2) + "\n"), candidateArtifact = await save(`${prefix}candidate.json`, JSON.stringify(candidate, null, 2) + "\n");
      try {
        for (const output of [reference, candidate]) assert.equal(output.exitCode, 0, output.stderr);
        const expected = parseLosslessJson(reference.stdout), actual = parseLosslessJson(candidate.stdout);
        for (const row of [expected, actual]) { assert.equal(row.id, identity.caseId); assert.equal(row.nonce, runId); }
        assertCandidateClientOrigin(actual, jar, `${wrapper.jar}:${jar}`, adapter);
        const soft = compareClientObservations(expected.result, actual.result, input, readPeerTrace(await readFile(referenceTrace.path, "utf8")), readPeerTrace(await readFile(candidateTrace.path, "utf8")));
        result.known_soft_spots = [...new Set([...(result.known_soft_spots ?? []), ...soft])];
        const peerTrace = { reference: await artifact(referenceTrace.path), candidate: await artifact(candidateTrace.path), referenceLaunchPolicy: await artifact(referencePolicy.path), candidateLaunchPolicy: await artifact(candidatePolicy.path) };
        if (input.fault_mode) assert.ok(peerTrace.reference.bytes && peerTrace.candidate.bytes, "scripted peer transcript missing");
        if (input.provider === "process") for (const proof of [peerTrace.referenceLaunchPolicy, peerTrace.candidateLaunchPolicy]) { const rows = (await readFile(proof.path, "utf8")).trimEnd().split("\n").map(line => JSON.parse(line)); assert.ok(rows.length); for (const row of rows) assert.deepEqual(row.restrictedNamesPresent, [], "process-owned worker inherited caller credentials"); }
        result.artifacts = result.artifacts.filter(item => !Object.values(peerTrace).some(proof => proof.path === item.path)); result.artifacts.push(...Object.values(peerTrace));
        const profdata = resolve(caseDirectory, "case.profdata"), merged = await capturedCommand([buildProof.tools.profdata.path, "merge", "-sparse", profraw, "-o", profdata], directory); assert.equal(merged.exitCode, 0, merged.stderr);
        const exported = await exportProfile([buildProof.tools.cov.path, "export", binary.path, `-instr-profile=${profdata}`, "-skip-expansions"], directory, resolve(caseDirectory, "llvm-cov.json.gz"));
        result.artifacts.push(exported);
        const entries = await llvmSourceEntries(JSON.parse(gunzipSync(await readFile(exported.path)).toString()), inventory, source, sourceRepository);
        const mappings = await save(`${prefix}entry-mappings.json`, JSON.stringify(entries, null, 2) + "\n");
        const profile: ClientProfileProof = { schema: 1, profraw: await artifact(profraw), profdata: await artifact(profdata), exported, mappings }; result.artifacts.push(profile.profraw, profile.profdata);
        const profileArtifact = await save(`${prefix}profile.json`, JSON.stringify(profile, null, 2) + "\n");
        const witness: ClientCaseWitness = { producer: "rust-client-llvm", ...identity, referenceFunctions: entries.linked, input: inputArtifact, reference: referenceArtifact, candidate: candidateArtifact, build: buildArtifact, profile: profileArtifact, peerTrace };
        result.cases!.push(witness);
      } catch (error) { result.findings.push(`${identity.caseId}: ${error instanceof Error ? error.message : String(error)}`); }
    }
    assert.equal(result.cases?.length, currentClientCases.length, "every real-host and scripted-peer case must pair; an omitted/failing case cannot be filtered out");
    assert.equal(sha256(await readFile(jar)), result.candidateJarSha256, "candidate changed while the actual-client corpus was running");
  } catch (error) { result.findings.push(error instanceof Error ? error.message : String(error)); }
  finally {
    if (worktreeCreated) { const cleanup = await capturedCommand(["git", "-C", sourceRepository!, "worktree", "remove", "--force", source], directory); if (cleanup.exitCode !== 0) result.findings.push(`reference worktree cleanup failed: ${cleanup.stderr}`); }
  }
  result.finishedAt = new Date().toISOString(); result.state = result.findings.length ? "failed" : "executed"; return result;
}
