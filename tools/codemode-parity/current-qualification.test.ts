import { describe, expect, test } from "bun:test";
import { mkdtemp, mkdir, writeFile, readFile, rm, chmod } from "node:fs/promises";
import { resolve } from "node:path";
import { tmpdir } from "node:os";
import { generateCurrentInventory, inventoryDigest, inventoryFindings, type CurrentInventory } from "./current-inventory";
import { dispositionFindings, executionFindings, generateDispositions, currentVerifierIds, nativeProvenanceFindings, contextReason, type Dispositions, type ExecutedVerifier, type NativeProvenance } from "./current-coverage";
import { currentReceiptFindings, observeCurrentReference, sealCurrentReceipt, type CurrentReceipt, type CurrentReference } from "./current-readiness";
import { listenerFindings, listeningInodes, type ListenerIdentity } from "./current-installed";
import { artifact, sha256 } from "./qualification";
import { assertNullArgumentDiscriminator, assertTimingEvidence } from "./current-protocol";
import { resolveCurrentSource } from "./current-source";
import { gzipSync } from "node:zlib";
import { assertFailedToolOutputRestart } from "./current-api-flows";
import { unitScopeSha256, instrumentRust, removeWitnessInsertions, coreCaseIdentity, executedCaseWitnessFindings } from "./current-witness";
import { verifyLinuxSigstore, assertOfficialActionsMetadata } from "./current-native-proof";

const digest = "a".repeat(64), revision = "1".repeat(40), key = Buffer.alloc(32, 4);
function inventory(): CurrentInventory {
  const value: CurrentInventory = { schema: 2, generator: "fixture", sourceRevision: revision, sourceTree: revision, sourceCargoLockSha256: digest,
    astGrepVersion: "fixture", discoveredCrates: ["codex-rs/code-mode-runtime"], files: [{ path: "codex-rs/code-mode-runtime/src/lib.rs", gitBlob: revision, sha256: digest, discovery: "crate", units: 1 }],
    units: [{ id: "codex-rs/code-mode-runtime/src/lib.rs#function_item@0", file: "codex-rs/code-mode-runtime/src/lib.rs", kind: "function_item", label: "helper", line: 1, start: 0, end: 2, sha256: digest, ownership: "native-host" }], grpcMethods: [], globalHelpers: [], denominatorSha256: "" };
  value.denominatorSha256 = inventoryDigest(value); return value;
}
function reviewed(value: CurrentInventory): Dispositions {
  return { schema: 3, sourceRevision: value.sourceRevision, denominatorSha256: value.denominatorSha256,
    units: value.units.map(unit => ({ id: unit.id, sha256: unit.sha256, scopeSha256: unitScopeSha256(unit, value), disposition: "native-reuse",
      verifiers: ["current.packaged-identities", "current.native-protocol"], witnesses: [], reason: "Explicit fixture native-host ownership reviewed against the pinned exact engine." })) };
}
describe("current completeness gate", () => {
  test("ordinary failed tool output must remain a value with durable disposition after a fresh JVM reload", () => {
    const nonce = "failure-probe", payload = `permission denied ordinary-client-value-${nonce}\nsecond-value`;
    const retained = [{ id: "owned", is_error: true, disposition: "TOOL_OUTPUT", origin: "GENERIC", payload }];
    const callbacks = [{ id: "owned", name: "hidden-tool", arguments: { value: nonce }, is_error: true }];
    const posted = [[{ type: "custom_tool_call_output", call_id: `ordinary-output-${nonce}`, output: [{ type: "input_text", text: "Script completed\n" }, { type: "input_text", text: `ordinary-value=${payload}` }] }]];
    const write = { caseId: "runtime.failed-tool-output-write", pid: 1, restored: [], retained, callbacks, posted };
    const read = { caseId: "runtime.failed-tool-output-read", pid: 2, restored: structuredClone(retained), retained: structuredClone(retained), callbacks: structuredClone(callbacks), posted: structuredClone(posted) };
    expect(() => assertFailedToolOutputRestart(write, read, nonce)).not.toThrow();
    const rejected = structuredClone(read); rejected.retained[0]!.disposition = "REJECT";
    expect(() => assertFailedToolOutputRestart(write, rejected, nonce)).toThrow();
    const reissued = structuredClone(read); reissued.callbacks.push(structuredClone(reissued.callbacks[0]!));
    expect(() => assertFailedToolOutputRestart(write, reissued, nonce)).toThrow();
  });
  test("timing values can vary but missing timing fields cannot disappear in normalization", () => {
    expect(() => assertTimingEvidence({ Result: { content_items: [], code_mode_host_duration_ns: "0", result_buffered_for_ns: "18446744073709551615" } })).not.toThrow();
    expect(() => assertTimingEvidence({ Result: { content_items: [] } })).toThrow("omitted actual");
    expect(() => assertTimingEvidence({ Result: { content_items: [], code_mode_host_duration_ns: "1" } })).toThrow("result_buffered_for_ns");
  });
  test("shared null/omitted serialization cannot pass the current independent argument oracle", () => {
    const observation: any[] = [{ tool_kind: "function" }, { tool_kind: "function" }, { output: { Result: { error_text: null, content_items: [
      { type: "input_text", text: JSON.stringify({ case: "omitted", value: { accepted: 1, input: {} } }) },
      { type: "input_text", text: JSON.stringify({ case: "null", error: "Error: tool `invoke` expects a JSON object for arguments" }) },
    ] } } }];
    expect(() => assertNullArgumentDiscriminator(observation)).not.toThrow();
    const collapsed = structuredClone(observation); collapsed.at(-1).output.Result.content_items[1].text = JSON.stringify({ case: "null", value: { accepted: 2, input: {} } });
    expect(() => assertNullArgumentDiscriminator(collapsed)).toThrow("literal JSON null");
  });
  test("rejects omitted, unsupported, unknown and weakened source dispositions", () => {
    const generated = inventory(), dispositions = generateDispositions(generated, reviewed(generated));
    expect(dispositionFindings(generated, dispositions)).toEqual([]);
    expect(dispositionFindings(generated, { ...dispositions, units: [] }).join("\n")).toContain("missing disposition");
    const unsupported = structuredClone(dispositions); unsupported.units[0]!.disposition = "unsupported";
    expect(dispositionFindings(generated, unsupported).join("\n")).toContain("unsupported disposition");
    const unknown = structuredClone(dispositions); unknown.units[0]!.id = "made-up";
    expect(dispositionFindings(generated, unknown).join("\n")).toContain("unknown disposition");
    const context = structuredClone(dispositions); context.units[0]!.disposition = "context";
    expect(dispositionFindings(generated, context).join("\n")).toContain("cannot be removed");
  });
  test("rejects hand edited/shrunken denominator even after recomputing its own digest", () => {
    const generated = inventory(), edited = structuredClone(generated); edited.units = []; edited.denominatorSha256 = inventoryDigest(edited);
    expect(inventoryFindings(generated, edited).join("\n")).toContain("omitted reference unit");
    expect(inventoryFindings(generated, edited).join("\n")).toContain("shrunken");
    const changed = structuredClone(generated); changed.units[0]!.sha256 = "f".repeat(64); changed.denominatorSha256 = inventoryDigest(changed);
    expect(inventoryFindings(generated, changed).join("\n")).toContain("source hashes changed");
  });
  test("a reference update cannot inherit old source dispositions or execution", () => {
    const prior = inventory(), current = structuredClone(prior); current.sourceRevision = "2".repeat(40); current.denominatorSha256 = inventoryDigest(current);
    expect(inventoryFindings(current, prior).join("\n")).toContain("source revision/tree changed");
    expect(dispositionFindings(current, generateDispositions(prior, reviewed(prior))).join("\n")).toContain("not bound");
  });
  test("requires current executed production verifiers and rejects manually asserted PASS", () => {
    const generated = inventory(), now = new Date().toISOString();
    const executions: ExecutedVerifier[] = currentVerifierIds.map(id => ({ id, runId: "r", startedAt: now, finishedAt: now, inventorySha256: generated.denominatorSha256,
      sourceRevision: revision, candidateJarSha256: digest, nativeHostSha256: digest, state: "executed", findings: [], artifacts: [{ path: "/fixture/raw", sha256: digest, bytes: 1 }] }));
    const expected = { runId: "r", candidateJarSha256: digest, nativeHostSha256: digest };
    expect(executionFindings(generated, reviewed(generated), executions, expected)).toEqual([]);
    expect(executionFindings(generated, reviewed(generated), executions.slice(1), expected).join("\n")).toContain("no current run evidence");
    const asserted: any = structuredClone(executions); asserted[0].pass = true;
    expect(executionFindings(generated, reviewed(generated), asserted, expected).join("\n")).toContain("manually asserted");
  });
  test("regeneration cannot auto-credit a new helper, changed branch, or changed ownership/scope", () => {
    const prior = inventory(), review = reviewed(prior);
    expect(generateDispositions(prior).units[0]!.disposition).toBe("unsupported");
    const helper = structuredClone(prior); helper.units.push({ ...helper.units[0]!, id: "new/helper", label: "unprobed" });
    expect(generateDispositions(helper, review).units.at(-1)!.disposition).toBe("unsupported");
    const branch = structuredClone(prior); branch.units[0]!.sha256 = "b".repeat(64);
    expect(generateDispositions(branch, review).units[0]!.disposition).toBe("unsupported");
    for (const mutation of ["owner", "scope", "file"] as const) {
      const changed = structuredClone(prior);
      if (mutation === "owner") changed.units[0]!.ownership = "ported";
      if (mutation === "scope") changed.units[0]!.requiredBy = ["codex-rs/core/src/new_behavior.rs"];
      if (mutation === "file") changed.files[0]!.sha256 = "c".repeat(64);
      expect(generateDispositions(changed, review).units[0]!.disposition).toBe("unsupported");
      expect(dispositionFindings(changed, review).join("\n")).toContain("scope changed");
    }
  });
  test("a passing verifier family cannot satisfy an unlinked or misassigned behavioral unit", () => {
    const value = inventory(); value.units[0]!.ownership = "ported";
    const unit = value.units[0]!, review = reviewed(value), item = review.units[0]!;
    item.disposition = "verify"; item.verifiers = ["current.tools-and-description"];
    item.witnesses = [{ ...coreCaseIdentity({ operation: "schema_projection", schema: { type: "string" } }), referenceEntrypoint: unit.id }];
    expect(dispositionFindings(value, review)).toEqual([]);
    expect(executionFindings(value, review, [], { runId: "r", candidateJarSha256: digest, nativeHostSha256: digest }).join("\n")).toContain("named case did not invoke this exact source unit");
    item.witnesses[0]!.referenceEntrypoint = "another/uncalled/function";
    expect(dispositionFindings(value, review).join("\n")).toContain("misassigned");
  });
  test("source instrumentation can only insert exact function-entry counters and retains original hashes", () => {
    const value = inventory(), source = Buffer.from("pub fn helper() { if true { return; } }"), unit = value.units[0]!;
    unit.end = source.length; unit.sha256 = sha256(source); value.files[0]!.sha256 = unit.sha256;
    const instrumented = instrumentRust(source, unit.file, value);
    expect(instrumented.insertions.length).toBe(1); expect(removeWitnessInsertions(instrumented.bytes, instrumented.insertions)).toEqual(source);
    const altered = Buffer.from(instrumented.bytes); altered[altered.length - 1] = 32;
    expect(removeWitnessInsertions(altered, instrumented.insertions)).not.toEqual(source);
    value.units[0]!.sha256 = digest; expect(() => instrumentRust(source, unit.file, value)).toThrow("source unit changed");
  });
  test("a sealed historical 24-family receipt is only a bounded floor", () => {
    expect(currentReceiptFindings({ schema: 1, qualified: true, cases: Array.from({ length: 24 }, () => ({ state: "executed" })) }, {} as any, key).join("\n")).toContain("historical bounded receipt");
  });
  test("a current receipt becomes invalid when installed reference version/hash changes", () => {
    const now = new Date().toISOString(), expected = { sourceRevision: revision, inventorySha256: digest, dispositionsSha256: digest, referenceLockSha256: digest, harnessSha256: digest,
      referenceRelease: "0.164.0", referenceCliSha256: digest, nativeHostSha256: digest, provenanceSha256: digest };
    const receipt = { schema: 2, claim: "current-complete-CodeMode", runId: "a".repeat(32), startedAt: now, finishedAt: now, sourceRevision: revision,
      inventorySha256: digest, dispositionsSha256: digest, referenceLockSha256: digest, harnessSha256: digest, reference: { release: "0.164.0", cli: { sha256: digest }, nativeHost: { sha256: digest } },
      candidateNativeHost: { sha256: digest }, nativeProvenance: { sha256: digest }, installed: {}, candidateConformant: true, qualified: true, seal: "" } as unknown as CurrentReceipt;
    receipt.seal = sealCurrentReceipt(receipt, key); expect(currentReceiptFindings(receipt, expected, key)).toEqual([]);
    const beforeInstall = structuredClone(receipt); delete beforeInstall.installed; beforeInstall.qualified = false;
    beforeInstall.seal = sealCurrentReceipt(beforeInstall, key);
    expect(currentReceiptFindings(beforeInstall, { ...expected, requireInstalled: false }, key)).toEqual([]);
    expect(currentReceiptFindings(beforeInstall, expected, key).join("\n")).toContain("installation proof is missing");
    expect(currentReceiptFindings(beforeInstall, expected, key).join("\n")).toContain("did not qualify this installed");
    expect(currentReceiptFindings(receipt, { ...expected, referenceRelease: "0.165.0" }, key).join("\n")).toContain("version or executable identity drifted");
    expect(currentReceiptFindings(receipt, { ...expected, nativeHostSha256: "b".repeat(64) }, key).join("\n")).toContain("version or executable identity drifted");
  });
});

describe("configured listener identity", () => {
  const selected: ListenerIdentity = { pid: 400, startTicks: "100", bootId: "boot", controlPort: 3096, socketInodes: ["123"], supervisorUnit: "splice.service", supervisorMainPid: 400 };
  test("a still-live historical PID cannot stand in for the selected listener/service", () => {
    expect(listenerFindings(selected, selected)).toEqual([]);
    expect(listenerFindings(selected, { ...selected, pid: 401, startTicks: "200", supervisorMainPid: 401 }).join("\n")).toContain("listener pid changed");
    expect(listenerFindings(selected, { ...selected, supervisorMainPid: 401 }).join("\n")).toContain("MainPID does not own");
    expect(listenerFindings(selected, { ...selected, startTicks: "999" }).join("\n")).toContain("startTicks changed");
    expect(listenerFindings(undefined, selected).join("\n")).toContain("independently resolved");
  });
  test("resolves only listening sockets at the authoritative configured port", () => {
    const table = "sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n" +
      "0: 0100007F:0C18 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 123\n" +
      "1: 0100007F:0C18 00000000:0000 01 00000000:00000000 00:00000000 00000000 1000 0 999\n" +
      "2: 0100007F:0C19 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 777\n";
    expect(listeningInodes([table], 3096)).toEqual(["123"]);
  });
});

test("source inventory discovers new crates, helpers, schema fields, enums and RPCs from immutable Git objects", async () => {
  const root = await mkdtemp(resolve(tmpdir(), "splice-current-inventory-test-"));
  const run = (args: string[]) => { const result = Bun.spawnSync(["git", ...args], { cwd: root }); if (result.exitCode) throw new Error(result.stderr.toString()); return result.stdout.toString().trim(); };
  try {
    await mkdir(resolve(root, "codex-rs/code-mode-future/src"), { recursive: true });
    await mkdir(resolve(root, "codex-rs/utils/fixture/src"), { recursive: true });
    await writeFile(resolve(root, "codex-rs/utils/fixture/Cargo.toml"), '[package]\nname="codex-utils-fixture"\nversion="0.0.0"\n');
    await writeFile(resolve(root, "codex-rs/utils/fixture/src/lib.rs"), 'pub fn sanitize_shape() { private_helper(); }\nfn private_helper() {}\n');
    await writeFile(resolve(root, "codex-rs/code-mode-future/Cargo.toml"), '[package]\nname="codex-code-mode-future"\nversion="0.0.0"\n');
    await writeFile(resolve(root, "codex-rs/code-mode-future/src/lib.rs"), 'use codex_utils_fixture::sanitize_shape;\npub struct Shape { pub value: bool }\npub enum Choice { A, B { field: String } }\npub fn visible() { fn helper() {} sanitize_shape(); }\nconst TYPES: &str = r#"type Result = { content: string; };"#;\n#[cfg(test)] mod tests { fn hidden_test() {} }\n');
    await writeFile(resolve(root, "codex-rs/code-mode-future/src/host.proto"), 'syntax="proto3"; service CodeModeHost { rpc NewApi (Args) returns (Result); } message Args { string query = 1; }');
    run(["init", "-q"]); run(["-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "add", "."]);
    run(["-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "fixture"]);
    const revision = run(["rev-parse", "HEAD"]), first = await generateCurrentInventory(root, revision);
    expect(await resolveCurrentSource({ sourceRevision: revision, tag: "rust-v0.164.0", repository: "https://github.com/openai/codex" }, root)).toBe(root);
    await writeFile(resolve(root, "codex-rs/code-mode-future/src/lib.rs"), "invalid working tree content");
    const second = await generateCurrentInventory(root, revision);
    expect(inventoryFindings(second, first)).toEqual([]);
    expect(first.discoveredCrates).toEqual(["codex-rs/code-mode-future"]);
    expect(first.units.map(unit => unit.label)).toContain("helper"); expect(first.units.map(unit => unit.label)).not.toContain("hidden_test");
    expect(first.units.map(unit => unit.label)).toContain("sanitize_shape"); expect(first.units.map(unit => unit.label)).toContain("private_helper");
    expect(first.units.some(unit => unit.kind === "field_declaration" && unit.label.includes("value"))).toBe(true);
    expect(first.units.some(unit => unit.kind === "enum_variant")).toBe(true);
    expect(first.units.some(unit => unit.kind === "typescript-property_signature" && unit.label.includes("content"))).toBe(true);
    expect(first.grpcMethods).toEqual(["NewApi"]);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test("flipping source linkage to verified cannot replace original executed provenance reports", async () => {
  const root = await mkdtemp(resolve(tmpdir(), "splice-native-provenance-test-"));
  try {
    const raw = Buffer.from("fixture"), nativeHash = sha256(raw), gzip = gzipSync(raw), gzipPath = resolve(root, "host.gz"), propertiesPath = resolve(root, "host.properties"), proofPath = resolve(root, "asserted.json");
    await writeFile(gzipPath, gzip);
    const properties = `sourceRevision=${revision}\nrelease=0.164.0\nsha256=${nativeHash}\n`; await writeFile(propertiesPath, properties); await writeFile(proofPath, "{}");
    const provenance: NativeProvenance = { schema: 2, release: "0.164.0", tag: "rust-v0.164.0", sourceRevision: revision,
      artifacts: ["linux-amd64", "linux-aarch64", "darwin-aarch64", "windows-amd64"].map(platform => ({ platform, sourceRevision: revision,
        archiveSha256: digest, sha256: nativeHash, origin: "https://github.com/openai/codex/releases/download/rust-v0.164.0/fixture",
        packagedGzip: { path: gzipPath, sha256: sha256(gzip), bytes: gzip.length }, packagedManifest: { path: propertiesPath, sha256: sha256(properties), bytes: Buffer.byteLength(properties) },
        sourceLinkage: { state: "verified", method: "sigstore-attestation", sourceRevision: revision, executableSha256: nativeHash,
          evidence: [{ path: proofPath, sha256: sha256("{}"), bytes: 2 }] },
      })) };
    expect((await nativeProvenanceFindings(provenance, { release: "0.164.0", tag: "rust-v0.164.0", sourceRevision: revision, nativeHostSha256: nativeHash })).join("\n")).toContain("actual Sigstore source/signature bundle");
  } finally { await rm(root, { recursive: true, force: true }); }
});

test("fabricated successful report and bundle hashes cannot replace cryptographic proof", async () => {
  const directory = await mkdtemp(resolve(tmpdir(), "splice-fabricated-sigstore-"));
  try {
    const bundle = resolve(directory, "fake.sigstore");
    await writeFile(bundle, JSON.stringify({ exitCode: 0, sourceRevision: revision, executableSha256: digest, stdout: "Verified OK", bundleSha256: digest }));
    await expect(verifyLinuxSigstore(Buffer.from("unsigned bytes"), bundle, { tag: "rust-v0.164.0", sourceRevision: revision })).rejects.toThrow("fabricated report cannot establish provenance");
    const proof = { workflowRunId: 5, artifactId: 6, artifactDigest: "sha256:" + digest, members: ["host"] };
    const workflow = { id: 5, head_sha: revision, repository: { id: 7, full_name: "openai/codex" }, path: ".github/workflows/rust-release.yml", status: "completed", conclusion: "success" };
    const artifact = { id: 6, digest: proof.artifactDigest, workflow_run: { id: 5, head_sha: revision, repository_id: 7 }, archive_download_url: "https://api.github.com/repos/openai/codex/actions/artifacts/6/zip" };
    expect(() => assertOfficialActionsMetadata(workflow, artifact, proof, revision)).not.toThrow();
    expect(() => assertOfficialActionsMetadata(workflow, { ...artifact, digest: "sha256:" + "b".repeat(64) }, proof, revision)).toThrow();
    expect(() => assertOfficialActionsMetadata({ ...workflow, head_sha: "2".repeat(40) }, artifact, proof, revision)).toThrow();
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("fully fabricated provenance transcripts accepted by the old report gate cannot authorize native reuse", async () => {
  const directory = await mkdtemp(resolve(tmpdir(), "splice-native-report-forgery-"));
  try {
    const raw = Buffer.from("unsigned native fixture"), nativeHash = sha256(raw), gzipPath = resolve(directory, "host.gz"), manifestPath = resolve(directory, "host.properties");
    await writeFile(gzipPath, gzipSync(raw)); await writeFile(manifestPath, `release=0.164.0\nsourceRevision=${revision}\nsha256=${nativeHash}\n`);
    const platforms = ["linux-amd64", "linux-aarch64", "darwin-aarch64", "windows-amd64"], releasePath = resolve(directory, "release.json");
    const origins = platforms.map(platform => `https://github.com/openai/codex/releases/download/rust-v0.164.0/${platform}.tar.gz`);
    await writeFile(releasePath, JSON.stringify({ assets: platforms.map((_, id) => ({ id, browser_download_url: origins[id], digest: `sha256:${digest}` })) }));
    const entries: NativeProvenance["artifacts"] = [];
    for (const [id, platform] of platforms.entries()) {
      const platformRoot = resolve(directory, platform); await mkdir(platformRoot);
      const bundle = resolve(platformRoot, "asserted.sigstore"), report = resolve(platformRoot, "source-linkage-verification.json");
      await writeFile(bundle, JSON.stringify({ base64Signature: "claimed", cert: "claimed", rekorBundle: { SignedEntryTimestamp: "claimed" } }));
      await writeFile(report, JSON.stringify({ exitCode: 0, sourceRevision: revision, executableSha256: nativeHash, bundleSha256: (await artifact(bundle)).sha256,
        command: ["cosign", "verify-blob", "--certificate-github-workflow-sha", revision, "--certificate-identity", "https://github.com/openai/codex/.github/workflows/rust-release.yml@refs/tags/rust-v0.164.0", "--certificate-oidc-issuer", "https://token.actions.githubusercontent.com"],
        workflow: { id: 5, head_sha: revision, conclusion: "success", path: ".github/workflows/rust-release.yml" },
        artifact: { digest: `sha256:${digest}`, workflow_run: { id: 5, head_sha: revision } }, verifiedArchiveSha256: digest, extractedSha256: nativeHash, releaseExecutableSha256: nativeHash, identical: true }));
      entries.push({ platform, sourceRevision: revision, archiveSha256: digest, sha256: nativeHash, origin: origins[id]!, assetId: id,
        packagedGzip: await artifact(gzipPath), packagedManifest: await artifact(manifestPath), sourceLinkage: { state: "verified", method: platform.startsWith("linux") ? "sigstore-attestation" : "official-actions-artifact", sourceRevision: revision, executableSha256: nativeHash, evidence: [await artifact(releasePath), await artifact(report), await artifact(bundle)] } });
    }
    const findings = await nativeProvenanceFindings({ schema: 2, release: "0.164.0", tag: "rust-v0.164.0", sourceRevision: revision, artifacts: entries }, { release: "0.164.0", tag: "rust-v0.164.0", sourceRevision: revision, nativeHostSha256: nativeHash });
    expect(findings.filter(message => message.includes("fabricated report") || message.includes("actual official Actions source/archive/extraction proof"))).toHaveLength(4);
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("a receipt cannot attach an unexecuted source unit to an otherwise matching named case", async () => {
  const directory = await mkdtemp(resolve(tmpdir(), "splice-unlinked-source-case-"));
  try {
    const value = inventory(), source = Buffer.from("pub fn helper() { }"), unit = value.units[0]!;
    unit.end = source.length; unit.sha256 = sha256(source); value.files[0]!.sha256 = unit.sha256;
    const transformed = instrumentRust(source, unit.file, value), input = { operation: "describe", tools: [] }, identity = coreCaseIdentity(input), id = identity.caseId, nonce = "nonce";
    const save = async (name: string, bytes: string | Uint8Array) => { const path = resolve(directory, name); await writeFile(path, bytes); return artifact(path); };
    const original = await save("original.rs", source), instrumented = await save("instrumented.rs", transformed.bytes);
    const manifest = await save("manifest.json", JSON.stringify({ schema: 1, sourceRevision: value.sourceRevision, instrumentation: "function-entry-insertions-v1", sources: [{ sourceFile: unit.file, sourceFileSha256: unit.sha256, sourceStart: 0, original, instrumented, insertions: transformed.insertions }] }));
    const inputArtifact = await save("input.jsonl", JSON.stringify({ id, ...input }) + "\n");
    const reference = await save("reference.json", JSON.stringify({ pid: 1, exitCode: 0, stdout: JSON.stringify({ id, result: { description: "same" }, source_witnesses: [] }) + "\n" }));
    const candidate = await save("candidate.json", JSON.stringify({ pid: 2, exitCode: 0, argv: ["java", "--current-core-fixture", nonce], stdout: JSON.stringify({ id, nonce, result: { description: "same" } }) + "\n" }));
    const case_ = { ...identity, referenceFunctions: [{ id: unit.id, sha256: unit.sha256 }], input: inputArtifact, reference, candidate, sourceManifest: manifest };
    expect((await executedCaseWitnessFindings([case_], value, nonce)).join("\n")).toContain("invalid executed source witness");
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("legacy transport context depends on exact current gRPC selection and cannot exclude active availability", async () => {
  const value: CurrentInventory = await Bun.file(resolve(import.meta.dir, "current-inventory.json")).json();
  const review: Dispositions = await Bun.file(resolve(import.meta.dir, "current-dispositions.json")).json();
  const legacy = value.units.find(unit => unit.file === "codex-rs/code-mode/src/remote_session.rs" && unit.kind === "function_item" && unit.label === "new");
  expect(legacy).toBeDefined(); expect(contextReason(legacy!, value)).toContain("current core host adapter constructs GrpcCodeModeSessionProvider");
  const selected = value.files.find(file => file.path === "codex-rs/core/src/code_mode_host.rs")!; selected.sha256 = "f".repeat(64);
  expect(contextReason(legacy!, value)).toBeUndefined();
  expect(dispositionFindings(value, review).join("\n")).toContain(`in-scope behavior cannot be removed as implementation context: ${legacy!.id}`);
  const availability = value.units.find(unit => unit.file === "codex-rs/code-mode/src/remote_session.rs" && unit.kind === "function_item" && unit.label === "availability");
  expect(availability).toBeDefined(); expect(contextReason(availability!, value)).toBeUndefined();
});

test("selected installed Codex package drift is checked independently of archived reference paths", async () => {
  const root = await mkdtemp(resolve(tmpdir(), "splice-current-reference-test-"));
  try {
    await mkdir(resolve(root, "bin"));
    await writeFile(resolve(root, "bin/codex"), '#!/bin/sh\nprintf "codex-cli 0.164.0\\n"\n'); await chmod(resolve(root, "bin/codex"), 0o755);
    await writeFile(resolve(root, "bin/codex-code-mode-host"), "native-fixture");
    const metadata = resolve(root, "codex-package.json"); await writeFile(metadata, JSON.stringify({ version: "0.164.0", target: "fixture" }));
    const lock = { release: "0.164.0", target: "fixture", installedPackageRootDefault: root,
      cliSha256: sha256(await readFile(resolve(root, "bin/codex"))), nativeHostSha256: sha256(await readFile(resolve(root, "bin/codex-code-mode-host"))) } as CurrentReference;
    await expect(observeCurrentReference(lock)).resolves.toMatchObject({ release: "0.164.0" });
    await writeFile(metadata, JSON.stringify({ version: "0.165.0", target: "fixture" })); await expect(observeCurrentReference(lock)).rejects.toThrow("reference drift");
    await writeFile(metadata, JSON.stringify({ version: "0.164.0", target: "fixture" })); await writeFile(resolve(root, "bin/codex-code-mode-host"), "changed");
    await expect(observeCurrentReference(lock)).rejects.toThrow("hash drift");
  } finally { await rm(root, { recursive: true, force: true }); }
});
