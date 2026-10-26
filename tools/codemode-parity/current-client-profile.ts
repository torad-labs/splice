import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { gunzipSync } from "node:zlib";
import type { CurrentInventory } from "./current-inventory";
import { capturedCommand } from "./current-command";
import { sha256, type Artifact } from "./qualification";
import { parseLosslessJson, type FunctionWitness } from "./current-witness";
import { compareClientObservations, readPeerTrace } from "./current-client-observable";
export { normalizeClientObservation } from "./current-client-observable";
import { currentClientCases, expandedClientInput } from "./current-client/cases";

export const candidateClientClasses = ["splice.codemode.nativehost.NativeConnection", "splice.codemode.nativehost.NativeObservations", "splice.codemode.nativehost.NativeFrames", "splice.codemode.nativehost.NativeRequests",
  ...["NativeGrpcChannel", "NativeGrpcRouter", "NativeGrpcSessions", "NativeGrpcExecutions", "NativeGrpcDelegates", "NativeGrpcWaits", "NativeGrpcWire"].map(name => `splice.codemode.nativehost.grpc.${name}`)].sort();
export const clientAdapterEntrypoints = { connection: "splice.codemode.nativehost.NativeConnection.operation", logical: "splice.codemode.NativeCodeModeRuntime.startNativeSession", wait: "splice.codemode.NativeCodeModeRuntime.controlNativeSession", opening: "splice.codemode.NativeCodeModeRuntime.openNativeSession" } as const;
export const clientAdapterMain = { connection: "ClientCandidate", logical: "ClientCandidateLogical", wait: "ClientCandidateWait", opening: "ClientCandidateOpening" } as const;
const candidateOwnerClasses = ["splice.codemode.NativeCodeModeRuntime", "splice.codemode.nativehost.sessions.NativeLogicalSession", "splice.codemode.nativehost.NativeConnection", "splice.codemode.nativehost.NativeCell", "splice.codemode.nativehost.NativeControlCell", "splice.codemode.nativehost.grpc.NativeGrpcWaits"].sort();
const candidateWaitClasses = ["splice.codemode.NativeCodeModeRuntime", "splice.codemode.nativehost.sessions.NativeLogicalControl", "splice.codemode.nativehost.NativeControlCell", "splice.codemode.nativehost.grpc.NativeGrpcWaits", "splice.codemode.nativehost.grpc.state.NativeGrpcWaitSlots"].sort();
const candidateOpeningClasses = ["splice.codemode.NativeCodeModeRuntime", "splice.codemode.nativehost.sessions.NativeLogicalSession", "splice.codemode.nativehost.NativeConnection", "splice.codemode.nativehost.grpc.NativeGrpcRouter"].sort();
export type ClientCaseWitness = {
  producer: "rust-client-llvm"; caseId: string; inputSha256: string; productionEntrypoint: typeof clientAdapterEntrypoints[keyof typeof clientAdapterEntrypoints];
  referenceFunctions: FunctionWitness[]; input: Artifact; reference: Artifact; candidate: Artifact; build: Artifact; profile: Artifact;
  peerTrace: { reference: Artifact; candidate: Artifact; referenceLaunchPolicy: Artifact; candidateLaunchPolicy: Artifact };
};
export type ClientBuildProof = {
  schema: 1; sourceRevision: string; sourceTree: string; sourceDirectory: string; sourceRepository: string;
  compiler: string; flags: string[]; binary: Artifact; driver: Artifact; cargoToml: Artifact; cargoLock: Artifact;
  tools: { cov: Artifact; profdata: Artifact }; wrapper: Artifact; wrapperSources: Artifact[]; wrapperBuild: Artifact;
  wrapperEntries: string[]; candidateJar: Artifact; cleanBefore: Artifact; cleanAfter: Artifact; sourceIdentity: Artifact;
};
export type ClientProfileProof = { schema: 1; profraw: Artifact; profdata: Artifact; exported: Artifact & { decodedSha256: string; decodedBytes: number }; mappings: Artifact };
export function clientCaseIdentity(input: Record<string, any>): { caseId: string; inputSha256: string; productionEntrypoint: ClientCaseWitness["productionEntrypoint"] } {
  const { id, nonce: _nonce, ...body } = input;
  assert.ok(typeof id === "string" && /^rust-client(?:-fault)?\/(?:[a-z0-9-]+\/)?[a-z0-9-]+$/.test(id));
  const adapter = input.adapter ?? "connection"; assert.ok(Object.hasOwn(clientAdapterEntrypoints, adapter));
  return { caseId: id, inputSha256: sha256(JSON.stringify(body)), productionEntrypoint: clientAdapterEntrypoints[adapter as keyof typeof clientAdapterEntrypoints] };
}
export function assertCandidateClientOrigin(row: any, expectedJar: string, classpath: string, adapter = "connection"): void {
  assert.deepEqual(Object.keys(row.class_origins ?? {}).sort(), adapter === "logical" ? candidateOwnerClasses : adapter === "wait" ? candidateWaitClasses : adapter === "opening" ? candidateOpeningClasses : candidateClientClasses, "candidate wrapper omitted required production class origins");
  for (const path of Object.values(row.class_origins)) assert.equal(path, expectedJar, "candidate wrapper shadowed a production class");
  assert.equal(row.classpath, classpath, "candidate classpath contains an overlay or unbound dependency");
}
function byteOffset(bytes: Uint8Array, line: number, column: number): number {
  let current = 1, offset = 0; while (current < line && offset < bytes.length) if (bytes[offset++] === 10) current++;
  assert.equal(current, line, "LLVM entry is beyond the pinned source file"); return offset + column - 1;
}
export async function llvmSourceEntries(exported: any, inventory: CurrentInventory, sourceDirectory: string, sourceRepository: string): Promise<{ linked: FunctionWitness[]; mappings: any[] }> {
  const units = inventory.units.filter(unit => unit.kind === "function_item"), source = new Map<string, Buffer>(), mappings: any[] = [];
  for (const section of exported.data ?? []) for (const function_ of section.functions ?? []) {
    if (!(function_.count > 0)) continue;
    const entry = function_.regions.find((region: number[]) => region[7] === 0); if (!entry) continue;
    const rawFilename = function_.filenames[entry[5]]; if (typeof rawFilename !== "string") continue;
    const filename = resolve(rawFilename), prefix = resolve(sourceDirectory) + "/"; if (!filename.startsWith(prefix)) continue;
    const file = filename.slice(prefix.length); if (!units.some(unit => unit.file === file)) continue;
    if (!source.has(file)) { const read = await capturedCommand(["git", "-C", sourceRepository, "show", `${inventory.sourceRevision}:${file}`], import.meta.dir); assert.equal(read.exitCode, 0); source.set(file, Buffer.from(read.stdout)); }
    const bytes = source.get(file)!, start = byteOffset(bytes, entry[0], entry[1]), end = byteOffset(bytes, entry[2], entry[3]);
    const unit = units.filter(unit => unit.file === file && unit.start <= start && end <= unit.end).sort((a, b) => (a.end - a.start) - (b.end - b.start))[0];
    assert.ok(unit, `positive project-local LLVM function has no exact AST body mapping: ${file}:${start}-${end}`);
    assert.equal(sha256(bytes.subarray(unit.start, unit.end)), unit.sha256); assert.equal(sha256(bytes), inventory.files.find(item => item.path === file)?.sha256);
    mappings.push({ id: unit.id, sha256: unit.sha256, function: function_.name, count: function_.count, entryRegion: entry, start, end, fileSha256: sha256(bytes) });
  }
  return { linked: [...new Map(mappings.map(item => [item.id, { id: item.id, sha256: item.sha256 }])).values()].sort((a, b) => a.id.localeCompare(b.id)), mappings };
}
async function checkedBytes(proof: Artifact): Promise<Buffer> { const bytes = await readFile(proof.path); assert.equal(bytes.length, proof.bytes); assert.equal(sha256(bytes), proof.sha256); return bytes; }
/** Re-run the profile decoder/exporter against the bound executable; stored positive JSON counts cannot authorize credit. */
export async function clientWitnessFindings(cases: ClientCaseWitness[], inventory: CurrentInventory, runId: string, candidateJar: { path: string; sha256: string }, disclosures: { execution: string[]; receipt: string[] } = { execution: [], receipt: [] }): Promise<string[]> {
  const findings: string[] = [], checkedBuilds = new Map<string, ClientBuildProof>(), seen = new Set<string>();
  for (const witness of cases) try {
    assert.ok(!seen.has(witness.caseId), "duplicated actual-client case"); seen.add(witness.caseId);
    let build = checkedBuilds.get(witness.build.sha256);
    if (!build) {
      build = JSON.parse((await checkedBytes(witness.build)).toString()) as ClientBuildProof;
      assert.equal(build.schema, 1); assert.equal(build.sourceRevision, inventory.sourceRevision); assert.equal(build.sourceTree, inventory.sourceTree);
      assert.match(build.compiler, /commit-hash: 58cf8f9c35b355199727627d07ae34a4e2846d67\b/);
      assert.deepEqual(build.flags, ["-Cinstrument-coverage", "-Cdebuginfo=1", "-Copt-level=0", "-Cdebug-assertions=no", "-Coverflow-checks=no"]);
      assert.equal(build.candidateJar.sha256, candidateJar.sha256); assert.equal(build.candidateJar.path, candidateJar.path);
      for (const proof of [build.binary, build.driver, build.cargoToml, build.cargoLock, build.tools.cov, build.tools.profdata, build.wrapper, ...build.wrapperSources, build.wrapperBuild, build.cleanBefore, build.cleanAfter, build.sourceIdentity]) await checkedBytes(proof);
      assert.equal((await checkedBytes(build.cleanBefore)).length, 0); assert.equal((await checkedBytes(build.cleanAfter)).length, 0);
      assert.equal((await checkedBytes(build.sourceIdentity)).toString().trim(), `${inventory.sourceRevision}\n${inventory.sourceTree}`);
      assert.ok(build.wrapperEntries.length && build.wrapperEntries.every(name => name.startsWith("ClientCandidate") || name.startsWith("META-INF/")));
      checkedBuilds.set(witness.build.sha256, build);
    }
    const input = parseLosslessJson((await checkedBytes(witness.input)).toString()); assert.equal(input.nonce, runId);
    assert.deepEqual(clientCaseIdentity(input), { caseId: witness.caseId, inputSha256: witness.inputSha256, productionEntrypoint: witness.productionEntrypoint });
    const planned = currentClientCases.find(case_ => case_.id === witness.caseId); assert.ok(planned, "unknown actual-client case");
    assert.deepEqual(clientCaseIdentity(expandedClientInput(planned, runId)), clientCaseIdentity(input), "actual-client scenario was weakened or changed");
    const referenceCommand = JSON.parse((await checkedBytes(witness.reference)).toString()), candidateCommand = JSON.parse((await checkedBytes(witness.candidate)).toString());
    for (const command of [referenceCommand, candidateCommand]) { assert.equal(command.exitCode, 0); assert.ok(Number.isSafeInteger(command.pid) && command.pid > 0); }
    const stdin = JSON.stringify(input); for (const command of [referenceCommand, candidateCommand]) assert.deepEqual(command.stdin, { sha256: sha256(stdin), bytes: Buffer.byteLength(stdin) }, "actual-client process did not consume the bound scenario");
    assert.equal(referenceCommand.argv[0], build.binary.path);
    const classpath = `${build.wrapper.path}:${candidateJar.path}`, adapter = input.adapter ?? "connection";
    assert.deepEqual(candidateCommand.argv.slice(1, 4), ["-cp", classpath, clientAdapterMain[adapter as keyof typeof clientAdapterMain]]);
    const reference = parseLosslessJson(referenceCommand.stdout), candidate = parseLosslessJson(candidateCommand.stdout);
    for (const row of [reference, candidate]) { assert.equal(row.id, witness.caseId); assert.equal(row.nonce, runId); }
    assertCandidateClientOrigin(candidate, candidateJar.path, classpath, adapter);
    const sourceTrace = readPeerTrace((await checkedBytes(witness.peerTrace.reference)).toString()), targetTrace = readPeerTrace((await checkedBytes(witness.peerTrace.candidate)).toString());
    if (input.fault_mode) assert.ok(sourceTrace.length && targetTrace.length, "scripted peer transcript is missing");
    const soft = compareClientObservations(reference.result, candidate.result, input, sourceTrace, targetTrace);
    for (const difference of soft) assert.ok(disclosures.execution.includes(difference) && disclosures.receipt.includes(difference), "observed transport diagnostic difference was omitted from known_soft_spots");
    for (const proof of [witness.peerTrace.referenceLaunchPolicy, witness.peerTrace.candidateLaunchPolicy]) {
      const rows = (await checkedBytes(proof)).toString().trimEnd().split("\n").filter(Boolean).map(line => JSON.parse(line));
      if (input.provider === "process") { assert.ok(rows.length, "process-owner launch policy was not observed"); for (const row of rows) assert.deepEqual(row.restrictedNamesPresent, [], "native worker inherited caller credentials"); }
    }
    const profile: ClientProfileProof = JSON.parse((await checkedBytes(witness.profile)).toString());
    for (const proof of [profile.profraw, profile.profdata, profile.mappings]) await checkedBytes(proof);
    const decoded = gunzipSync(await checkedBytes(profile.exported)); assert.equal(decoded.length, profile.exported.decodedBytes); assert.equal(sha256(decoded), profile.exported.decodedSha256);
    // llvm-profdata validates profile structure and binary/function hashes; llvm-cov derives the counters anew.
    const merged = `${profile.profdata.path}.verify-${crypto.randomUUID()}`;
    const merge = await capturedCommand([build.tools.profdata.path, "merge", "-sparse", profile.profraw.path, "-o", merged], import.meta.dir); assert.equal(merge.exitCode, 0, merge.stderr);
    try {
      const exported = await capturedCommand([build.tools.cov.path, "export", build.binary.path, `-instr-profile=${merged}`, "-skip-expansions"], import.meta.dir, undefined, 180_000);
      assert.equal(exported.exitCode, 0, exported.stderr);
      const derived = await llvmSourceEntries(JSON.parse(exported.stdout), inventory, build.sourceDirectory, build.sourceRepository);
      const saved = await llvmSourceEntries(JSON.parse(decoded.toString()), inventory, build.sourceDirectory, build.sourceRepository);
      assert.deepEqual(saved, derived, "saved LLVM function-entry mappings disagree with actual binary/profile export");
      assert.deepEqual(witness.referenceFunctions, derived.linked, "claimed source function was not entered by this named case");
    } finally { await (await import("node:fs/promises")).rm(merged, { force: true }); }
  } catch (error) { findings.push(`${witness.caseId}: invalid actual-client witness: ${error instanceof Error ? error.message : String(error)}`); }
  for (const expected of currentClientCases) if (!seen.has(expected.id)) findings.push(`mandatory actual-client scenario was omitted: ${expected.id}`);
  return findings;
}
