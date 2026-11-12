import assert from "node:assert/strict";
import { copyFile, mkdir, readFile } from "node:fs/promises";
import { basename, dirname, resolve } from "node:path";
import { capturedCommand } from "./current-command";
import { assertSourceCliBuild, type CliSourceBuildProof } from "./current-cli-source-profile";
import type { CurrentInventory } from "./current-inventory";
import type { ClientProfileProof } from "./current-client-profile";
import { sha256, type Artifact, type Identity } from "./qualification";
import { assertPreDispatchFacts, preDispatchControl, preDispatchInputSha256, preDispatchScenario, type PreDispatchEvent, type PreDispatchFacts } from "./current-pre-dispatch-contract";
import { executePreDispatchSource, assertPreDispatchSourceControls, type PreDispatchSource } from "./current-pre-dispatch-source";
import { executePreDispatchCandidate, type PreDispatchCandidate, type PreDispatchKotlinCompiler } from "./current-pre-dispatch-candidate";
import { executePreDispatchProfile } from "./current-pre-dispatch-profile";
import { preDispatchArtifact, preDispatchChecked, preDispatchDriver, preDispatchVerify, preDispatchWrite } from "./current-pre-dispatch-io";

export type PreDispatchOptions = {
  sourceBuild: Artifact; inventory: CurrentInventory; candidateJar: Identity; nativeHost: Identity;
  java: Identity; kotlinCompiler: PreDispatchKotlinCompiler; gdb: Identity; bun: Identity; evidence: string; runId: string;
};
export type PreDispatchWitness = {
  schema: 1; producer: "linux-pre-dispatch"; caseId: typeof preDispatchControl.id; runId: string; inputSha256: string;
  input: Artifact; inventory: Artifact; source: PreDispatchSource; candidate: PreDispatchCandidate;
  profile: Awaited<ReturnType<typeof executePreDispatchProfile>>; helpers: Artifact[];
  registered: false; behavioralCoverage: false;
};
const json = async (pin: Artifact) => JSON.parse((await preDispatchChecked(pin)).toString());
const rows = (bytes: Buffer): PreDispatchEvent[] => bytes.toString().trimEnd().split("\n").filter(Boolean).map(line => JSON.parse(line));
function candidateFile(candidate: PreDispatchCandidate, name: string): Artifact {
  const found = candidate.files.filter(pin => pin.path === resolve(candidate.directory, name)); assert.equal(found.length, 1, `missing/duplicate candidate raw file ${name}`); return found[0]!;
}
async function helperArtifacts(): Promise<Artifact[]> {
  const names = ["current-pre-dispatch-contract.ts", "current-pre-dispatch-io.ts", "current-pre-dispatch-source.ts", "current-pre-dispatch-profile.ts", "current-pre-dispatch-candidate.ts", "current-pre-dispatch-proof.ts",
    "current-cli-source-profile.ts", "current-client-profile.ts", "current-command.ts", "current-cli/native-events.ts", "current-cli/scripted-upstream.ts",
    "pre-dispatch/source-driver.ts", "pre-dispatch/profile-driver.ts", "pre-dispatch/candidate-driver.ts", "pre-dispatch/PendingDeliveryPrototype.kt.fixture", "pre-dispatch/NativeStreamDecoder.kt.fixture"];
  return Promise.all(names.map(name => preDispatchArtifact(resolve(import.meta.dir, name))));
}
export function preDispatchWitnessArtifacts(witness: PreDispatchWitness): Artifact[] {
  const source = witness.source, candidate = witness.candidate, profile = witness.profile;
  return [witness.input, witness.inventory, ...witness.helpers, source.build, source.config, source.command, source.driver,
    source.recipe, source.receipt, source.controls, source.mi, source.terminal, source.nativeEvents, source.requests, source.frames, source.profileRaw, ...source.files,
    candidate.config, candidate.driver, candidate.command, candidate.result, candidate.compile, candidate.receipt, candidate.recipe, candidate.events, candidate.posted,
    candidate.nativeDecoded, ...candidate.files, profile.proof, profile.driver, profile.command, ...profile.files];
}
/** No route registration or source-disposition credit is performed here. */
export async function executePreDispatchWitness(options: PreDispatchOptions): Promise<{ witness: PreDispatchWitness; artifact: Artifact }> {
  assert.equal(process.platform, "linux"); assert.match(options.runId, /^[a-f0-9]{32}$/);
  const directory = resolve(options.evidence, `${options.runId}.pre-dispatch`); await mkdir(directory, { recursive: false });
  const helpers = await helperArtifacts(), input = await preDispatchWrite(resolve(directory, "control-input.json"), preDispatchControl);
  const inventory = await preDispatchWrite(resolve(directory, "inventory.json"), options.inventory);
  const source = await executePreDispatchSource({ build: options.sourceBuild, inventory: options.inventory, host: options.nativeHost,
    gdb: options.gdb, bun: options.bun, directory: resolve(directory, "source"), runId: options.runId });
  const sourceReceipt = await json(source.receipt);
  const profile = await executePreDispatchProfile({ build: options.sourceBuild, inventory, raw: source.profileRaw, pid: sourceReceipt.pid,
    directory: resolve(directory, "profile"), bun: options.bun, runId: options.runId });
  const candidate = await executePreDispatchCandidate({ jar: options.candidateJar, java: options.java, compiler: options.kotlinCompiler,
    directory: resolve(directory, "candidate"), runId: options.runId, bun: options.bun });
  for (const helper of helpers) await preDispatchVerify(helper);
  const witness: PreDispatchWitness = { schema: 1, producer: "linux-pre-dispatch", caseId: preDispatchControl.id, runId: options.runId,
    inputSha256: preDispatchInputSha256, input, inventory, source, candidate, profile, helpers, registered: false, behavioralCoverage: false };
  assertPreDispatchFacts(await preDispatchFacts(witness, options));
  return { witness, artifact: await preDispatchWrite(resolve(directory, "witness.json"), witness) };
}
async function nativeCandidateFacts(candidate: PreDispatchCandidate, runId: string): Promise<{ durableNoticeCount: number }> {
  const events = rows(await preDispatchChecked(candidate.events)), pending = events.find(event => event.kind === "actual_notification_pending")!.value;
  const decoded = await json(candidate.nativeDecoded), protobuf = decoded.filter((frame: any) => frame.kind === "protobuf");
  const notices = protobuf.filter((frame: any) => frame.direction === "host-to-client" && frame.value.includes("notification {"));
  assert.equal(notices.length, 1); assert.ok(notices[0].value.includes(`cell_id: "${pending.cellId}"`));
  assert.ok(notices[0].value.includes(`call_id: "${pending.callId}"`)); assert.ok(notices[0].value.includes(preDispatchScenario(runId).notice));
  const termination = protobuf.filter((frame: any) => frame.method === "Terminate" && frame.direction === "host-to-client");
  assert.equal(termination.length, 1); assert.ok(termination[0].value.includes(`cell_id: "${pending.cellId}"`)); assert.ok(termination[0].value.includes("terminated {"));
  assert.equal(protobuf.filter((frame: any) => frame.direction === "client-to-host" && frame.method === "Execute").length, 3);
  let durableNoticeCount = 0;
  for (const pin of candidate.files.filter(pin => pin.path.startsWith(resolve(candidate.directory, "records") + "/") && pin.path.endsWith(".jsonl"))) {
    for (const row of rows(await preDispatchChecked(pin)) as any[]) for (const record of row.records ?? []) {
      durableNoticeCount += record.nativeNotifications?.length ?? 0;
      assert.ok(!String(record.output).includes(preDispatchScenario(runId).forbidden));
    }
  }
  const process = events.find(event => event.kind === "native_process")!.value;
  assert.equal(process.exeSha256, preDispatchControl.nativeHostSha256);
  const startTicks = process.stat.slice(process.stat.lastIndexOf(")") + 2).split(" ")[19];
  let sameLifetime = false;
  try { const current = await readFile(`/proc/${process.pid}/stat`, "utf8"); sameLifetime = current.slice(current.lastIndexOf(")") + 2).split(" ")[19] === startTicks; } catch {}
  assert.equal(sameLifetime, false, "owned candidate native process leaked");
  return { durableNoticeCount };
}
export async function preDispatchFacts(witness: PreDispatchWitness, expected: PreDispatchOptions): Promise<PreDispatchFacts> {
  const source = await json(witness.source.receipt), candidate = await json(witness.candidate.result);
  const sourceEvents = rows(await preDispatchChecked(witness.source.controls)), candidateEvents = rows(await preDispatchChecked(witness.candidate.events));
  assertPreDispatchSourceControls(source, sourceEvents, (await preDispatchChecked(witness.source.mi)).toString());
  const profile: ClientProfileProof = await json(witness.profile.proof), mappings = await json(profile.mappings);
  const exact = mappings.mappings.filter((row: any) => row.id === preDispatchControl.sourceUnit.id); assert.equal(exact.length, 1);
  const requests = (await json(witness.source.requests)).filter((request: any) => !request.title), posted = await json(witness.candidate.posted);
  const output = (input: any[], id: string): string => {
    const outputs = input.filter(item => item.type === "custom_tool_call_output" && item.call_id === id); assert.equal(outputs.length, 1);
    assert.ok(Array.isArray(outputs[0].output)); assert.equal(outputs[0].output.length, 2); return outputs[0].output[1].text;
  };
  const nativeRows = rows(await preDispatchChecked(witness.source.nativeEvents)) as any[];
  const sourceNotice = nativeRows.some(row => row.type === "response_item" && row.payload?.type === "custom_tool_call_output" && row.payload.call_id === "probe-pre-ready-1" && row.payload.output === preDispatchScenario(witness.runId).notice);
  const facts = await nativeCandidateFacts(witness.candidate, witness.runId);
  return { runId: witness.runId, inputSha256: witness.inputSha256, sourcePid: source.pid, profilePid: Number(basename(profile.profraw.path, ".profraw")), sourceStartTicks: source.startTicks,
    sourceCliSha256: source.before.cli.sha256, candidateJarSha256: candidate.candidateJar.sha256, expectedCandidateJarSha256: expected.candidateJar.sha256,
    sourceUnit: { id: exact[0].id, sha256: exact[0].sha256 }, sourceCount: exact[0].count, sourceEvents, candidateEvents,
    sourceTaskRequests: requests.length, candidateTaskRequests: posted.length,
    sourceResumedValue: output(requests.at(-1).body.input, "probe-pre-ready-2"),
    candidateResumedValue: output(posted.at(-1), `pre-ready-exec-2-${witness.runId}`), sourceNoticeDelivered: sourceNotice,
    candidateNoticeDelivered: candidate.deliveredText.some((text: string) => text.includes(preDispatchScenario(witness.runId).notice)), durableNoticeCount: facts.durableNoticeCount,
    sourceCompleted: nativeRows.some(row => row.type === "event_msg" && row.payload?.type === "task_complete" && row.payload.last_agent_message === `CODEMODE_DISPATCH_GATE_${witness.runId}`),
    candidateCompleted: candidate.outcomes?.[1] === "Success", loaderBreakpointDisclosed: source.note.includes("internal shlib-event loader breakpoint") };
}
/** Replay standard LLVM and the packaged protobuf decoder from retained raw bytes, without rerunning a model or CLI. */
export async function preDispatchWitnessFindings(witness: PreDispatchWitness, expected: PreDispatchOptions): Promise<string[]> {
  try {
    assert.equal(witness.schema, 1); assert.equal(witness.producer, "linux-pre-dispatch"); assert.equal(witness.caseId, preDispatchControl.id);
    assert.equal(witness.runId, expected.runId); assert.equal(witness.inputSha256, preDispatchInputSha256);
    assert.equal(witness.registered, false); assert.equal(witness.behavioralCoverage, false);
    assert.deepEqual(await json(witness.input), preDispatchControl);
    assert.deepEqual(await json(witness.inventory), expected.inventory);
    assert.deepEqual(witness.source.build, expected.sourceBuild);
    for (const artifact of preDispatchWitnessArtifacts(witness)) await preDispatchVerify(artifact);
    assert.deepEqual(witness.helpers, await helperArtifacts());
    const build = await json(expected.sourceBuild) as CliSourceBuildProof; await assertSourceCliBuild(build, expected.inventory);
    for (const tool of [expected.candidateJar, expected.gdb, expected.bun, expected.java, expected.kotlinCompiler.compiler, ...expected.kotlinCompiler.dependencies]) await preDispatchVerify(tool);
    const sourceRecipe = await json(witness.source.recipe); assert.equal(sourceRecipe.nonce, expected.runId); assert.equal(sourceRecipe.inputSha256, preDispatchInputSha256);
    assert.deepEqual(sourceRecipe.scripts, preDispatchScenario(expected.runId).scripts); assert.deepEqual(sourceRecipe.control, preDispatchControl);
    const sourceConfig = await json(witness.source.config); assert.deepEqual(sourceConfig.gdb, expected.gdb); assert.deepEqual(sourceConfig.cli, build.binary); assert.deepEqual(sourceConfig.host, expected.nativeHost);
    const debuggerEvent = rows(await preDispatchChecked(witness.source.controls)).find(event => event.kind === "owned_gdb")!;
    assert.equal(debuggerEvent.value.identity.sha256, expected.gdb.sha256);
    const candidateRecipe = await json(witness.candidate.recipe); assert.deepEqual(candidateRecipe.scripts, preDispatchScenario(expected.runId).scripts);
    assert.equal(candidateRecipe.nonce, expected.runId); assert.equal(candidateRecipe.inputSha256, preDispatchInputSha256);
    const candidate = await json(witness.candidate.result), process = await json(candidateFile(witness.candidate, "process.json"));
    assert.equal(process.exitCode, 0); assert.equal(candidate.pid, process.pid);
    assert.deepEqual(Object.keys(candidate.classOrigins).sort(), ["splice.app.diagnostics.CodeModeHttpInterruptProbe", "splice.codemode.NativeCodeModeRuntime", "splice.provider.codex.CodexCodeModeBridge"]);
    for (const origin of Object.values(candidate.classOrigins)) assert.equal(origin, expected.candidateJar.path);
    const compile = await json(witness.candidate.compile); assert.equal(compile.command.exitCode, 0); assert.deepEqual(compile.compiler, expected.kotlinCompiler);
    assert.deepEqual(compile.java, expected.java); assert.match(compile.version.stdout + compile.version.stderr, /kotlinc-jvm 2\.3\.21\b/);
    assert.equal(compile.candidate.sha256, expected.candidateJar.sha256); assert.equal(compile.gradle, false);
    for (const pair of compile.sourceFiles) { await preDispatchVerify(pair.fixture); await preDispatchVerify(pair.source); assert.equal(pair.fixture.sha256, pair.source.sha256); }
    const fixtureNames = ["PendingDeliveryPrototype", "NativeStreamDecoder"];
    assert.deepEqual(compile.sourceFiles.map((pair: any) => pair.fixture.path), fixtureNames.map(name => preDispatchDriver(`${name}.kt.fixture`)));
    assert.deepEqual(compile.sourceFiles.map((pair: any) => pair.source.path), fixtureNames.map(name => resolve(witness.candidate.directory, `${name}.kt`)));
    await preDispatchVerify(compile.output);
    assert.equal(compile.output.path, candidateFile(witness.candidate, "pending-delivery.jar").path);
    const classpath = [expected.kotlinCompiler.compiler.path, expected.candidateJar.path, ...expected.kotlinCompiler.dependencies.map(pin => pin.path)].join(":");
    assert.deepEqual(compile.command.argv, [expected.java.path, "-Xmx1g", "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
      "-classpath", expected.candidateJar.path, `-Xfriend-paths=${expected.candidateJar.path}`, "-jvm-target", "21", "-d", compile.output.path, ...compile.sourceFiles.map((pair: any) => pair.source.path)]);
    assert.deepEqual(process.argv, [expected.java.path, "-Xmx1g", `-Djava.io.tmpdir=${resolve(witness.candidate.directory, "native-tmp")}`,
      `-Duser.home=${resolve(witness.candidate.directory, "private-home")}`, "-cp", `${compile.output.path}:${expected.candidateJar.path}`,
      "splice.dispatchprototype.PendingDeliveryPrototype", witness.candidate.directory]);
    const limits = await json(candidateFile(witness.candidate, "limits.json"));
    assert.equal(limits["memory.max"].trim(), "4294967296"); assert.equal(limits["memory.swap.max"].trim(), "0"); assert.match(limits["memory.events"], /oom_kill 0\n/);
    const verification = resolve(dirname(witness.profile.proof.path), `verify-${Date.now()}`); await mkdir(verification);
    const pid = (await json(witness.source.receipt)).pid;
    const replay = await executePreDispatchProfile({ build: expected.sourceBuild, inventory: witness.inventory, raw: witness.source.profileRaw,
      pid, directory: resolve(verification, "profile"), bun: expected.bun, runId: sha256(verification).slice(0, 32) });
    const oldProfile: ClientProfileProof = await json(witness.profile.proof), newProfile: ClientProfileProof = await json(replay.proof);
    assert.equal(oldProfile.mappings.sha256, newProfile.mappings.sha256); assert.equal(oldProfile.exported.decodedSha256, newProfile.exported.decodedSha256);
    for (const pin of [oldProfile.profraw, oldProfile.profdata, oldProfile.mappings, oldProfile.exported]) await preDispatchVerify(pin);
    const decodeDirectory = resolve(verification, "native"); await mkdir(decodeDirectory);
    for (const name of ["native-client-to-host.raw", "native-host-to-client.raw"]) await copyFile(candidateFile(witness.candidate, name).path, resolve(decodeDirectory, name));
    const decoded = await capturedCommand([expected.java.path, "-Xmx256m", "-cp", `${compile.output.path}:${expected.candidateJar.path}`,
      "splice.dispatchprototype.NativeStreamDecoder", decodeDirectory], verification, undefined, 30_000);
    await preDispatchWrite(resolve(verification, "native-decode-command.json"), decoded); assert.equal(decoded.exitCode, 0, decoded.stderr);
    assert.equal((await preDispatchArtifact(resolve(decodeDirectory, "native-decoded.json"))).sha256, witness.candidate.nativeDecoded.sha256);
    assertPreDispatchFacts(await preDispatchFacts(witness, expected));
    return [];
  } catch (error) { return [`pre-dispatch proof: ${error instanceof Error ? error.message : String(error)}`]; }
}
