import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { closeSync, lstatSync, openSync, readFileSync, readSync, readdirSync, realpathSync } from "node:fs";
import { resolve } from "node:path";
import type { CliRun, CliScenario, ObjectValue } from "./types";
import type { CurrentInventory } from "../current-inventory";

export const freeformPatch = "*** Begin Patch\n*** Add File: codemode-freeform.txt\n+CodeMode freeform λ fixture.\n*** End Patch";
export const freeformFile = "codemode-freeform.txt";
export const freeformContents = "CodeMode freeform λ fixture.\n";
const freeformScript = `text({tag:"freeform-patch",value:await tools.apply_patch(${JSON.stringify(freeformPatch)})});`;
export const freeformScenario: CliScenario = { schema: 1, id: "freeform-patch", applyPatch: true, actions: [
  { kind: "exec", source: freeformScript },
] };

type Artifact = { path: string; sha256: string; bytes: number };
export type FreeformTool = { binary: Artifact; proof: Artifact };
export type FreeformTranscript = {
  patch: string; value: ObjectValue; description: string; stdout: string; stderr: string;
  file: { path: string; sha256: string; bytes: number; base64: string }; tool: FreeformTool;
};
const digest = (bytes: Uint8Array | string) => createHash("sha256").update(bytes).digest("hex");

function object(value: unknown, label: string): ObjectValue {
  assert.ok(value !== null && typeof value === "object" && !Array.isArray(value), `${label} must be an object`);
  return value as ObjectValue;
}
function bounded(path: string, limit: number): Buffer {
  const stat = lstatSync(path);
  assert.ok(stat.isFile() && !stat.isSymbolicLink() && stat.size <= limit, "Freeform evidence must be a bounded regular file");
  const bytes = readFileSync(path); assert.equal(bytes.length, stat.size); return bytes;
}
function verify(proof: Artifact, limit: number): void {
  assert.equal(resolve(proof.path), proof.path, "Freeform tool evidence must use an absolute path");
  assert.match(proof.sha256, /^[a-f0-9]{64}$/);
  const stat = lstatSync(proof.path);
  assert.ok(stat.isFile() && !stat.isSymbolicLink() && stat.size <= limit, "Freeform artifact must be a bounded regular file");
  assert.equal(stat.size, proof.bytes);
  const fd = openSync(proof.path, "r"), hash = createHash("sha256"), buffer = Buffer.alloc(64 * 1024);
  let total = 0;
  try {
    for (;;) { const count = readSync(fd, buffer, 0, buffer.length, null); if (!count) break; total += count; assert.ok(total <= limit); hash.update(buffer.subarray(0, count)); }
  } finally { closeSync(fd); }
  assert.equal(total, proof.bytes); assert.equal(hash.digest("hex"), proof.sha256);
}
function checked(proof: Artifact, limit: number): Buffer {
  verify(proof, limit); return bounded(proof.path, limit);
}

/** The fixture executable must come from the same audited, locked source build as the CLI. */
export async function assertApplyPatchBuild(tool: FreeformTool, inventory: CurrentInventory): Promise<void> {
  const proof = JSON.parse(checked(tool.proof, 1024 * 1024).toString());
  const { assertSourceCliBuild, sourceCliFlags } = await import("../current-cli-source-profile");
  assert.equal(proof.schema, 1); assert.equal(proof.sourceRevision, inventory.sourceRevision); assert.equal(proof.sourceTree, inventory.sourceTree);
  const sourceBuild = JSON.parse(checked(proof.sourceBuild, 1024 * 1024).toString());
  await assertSourceCliBuild(sourceBuild, inventory);
  assert.equal(proof.compiler, sourceBuild.compiler); assert.deepEqual(proof.flags, sourceCliFlags);
  assert.deepEqual(proof.binary, tool.binary); verify(tool.binary, 1024 * 1024 * 1024);
  const command = JSON.parse(checked(proof.command, 1024 * 1024).toString());
  assert.equal(command.exitCode, 0); assert.ok(Number.isSafeInteger(command.pid) && command.pid > 0);
  assert.ok(command.argv.includes("build") && command.argv.includes("--locked") && command.argv.includes("codex-apply-patch") && command.argv.includes("apply_patch"), "Freeform fixture must use the locked official apply_patch application");
}

/** Bind the builtin's result to its actual session-owned file-change event and bytes.
 * A case label or a synthetic callback result cannot supply this evidence. */
export function sourceFreeformTranscript(source: CliRun): FreeformTranscript {
  assert.equal(source.scenario.applyPatch, true, "A generic scenario has no builtin freeform evidence");
  assert.equal(source.scenario.mcp, undefined, "Freeform and MCP boundaries must be distinct");
  assert.equal(source.scenario.rankedSearch, undefined, "Freeform does not qualify ranked search");
  assert.deepEqual(source.scenario.actions, freeformScenario.actions, "Only the reviewed disposable patch is supported");
  const tool = (source as CliRun & { freeformTool?: FreeformTool }).freeformTool;
  assert.ok(tool, "Freeform requires a hash-bound official standalone tool build");
  verify(tool.binary, 1024 * 1024 * 1024); const proof = object(JSON.parse(checked(tool.proof, 1024 * 1024).toString()), "Apply-patch build proof");
  assert.equal(proof.sourceRevision, source.reference.sourceRevision, "Freeform executable belongs to another source revision");
  assert.deepEqual(proof.binary, tool.binary, "Freeform executable differs from its build receipt");
  const first = source.observations[0]?.input ?? [], last = source.observations.at(-1)?.input ?? [];
  const exec = last.filter(item => item.type === "custom_tool_call" && item.name === "exec");
  assert.equal(exec.length, 1, "Freeform needs exactly one observed exec call"); assert.equal(exec[0]!.input, freeformScript);
  const outputs = last.filter(item => item.type === "custom_tool_call_output" && item.call_id === exec[0]!.call_id);
  assert.equal(outputs.length, 1); const output = outputs[0]!.output;
  assert.ok(Array.isArray(output) && output.length === 2, "Freeform source output is incomplete");
  const value = object(JSON.parse(String(object(output[1], "Freeform source output").text)), "Freeform source value");
  assert.deepEqual(value, { tag: "freeform-patch", value: {} }, "Builtin guest value changed");
  const manuals = first.filter(item => item.type === "additional_tools").flatMap(item => (item.tools as ObjectValue[])
    .flatMap(tool => tool.type === "namespace" ? tool.tools as ObjectValue[] : [tool]));
  const manual = manuals.find(tool => tool.name === "exec"); assert.ok(manual && typeof manual.description === "string", "Source omitted the actual tool catalog");
  const start = manual.description.indexOf("### `apply_patch`\n"), end = manual.description.indexOf("### `", start + 5);
  assert.ok(start >= 0 && end > start, "Source did not advertise the freeform builtin");
  const description = manual.description.slice(start, end).trim();
  assert.ok(description.includes("This is a FREEFORM tool") && description.includes("apply_patch(input: string): Promise<unknown>"), "Source changed the freeform input contract");
  const workspace = resolve(source.directory, "workspace"), path = resolve(workspace, freeformFile);
  assert.equal(realpathSync(path), path, "Freeform source file escaped the disposable workspace");
  assert.deepEqual(readdirSync(workspace).sort(), [freeformFile], "Freeform source has unbound file effects");
  const bytes = bounded(path, 1024); assert.equal(bytes.toString(), freeformContents, "Actual patch file bytes changed");
  const events = bounded(resolve(source.directory, "native-events.jsonl"), 16 * 1024 * 1024).toString();
  assert.ok(events.endsWith("\n"), "Freeform native event capture is incomplete");
  const changes = events.trimEnd().split("\n").map(line => object(JSON.parse(line), "Native event"))
    .filter(row => row.type === "event_msg").map(row => object(row.payload, "Native event payload"))
    .filter(row => row.type === "item_completed" && object(row.item, "Completed item").type === "FileChange");
  assert.equal(changes.length, 1, "Freeform needs one actual completed native file change");
  const change = changes[0]!; assert.equal(change.thread_id, source.completion.sessionId, "Freeform effect belongs to another session");
  const item = object(change.item, "Native file change"); assert.match(String(item.id), /^exec-[a-f0-9-]{36}$/);
  assert.equal(item.status, "completed"); assert.deepEqual(item.changes, { [path]: { type: "add", content: freeformContents } });
  assert.equal(item.stdout, `Success. Updated the following files:\nA ${freeformFile}\n`); assert.equal(item.stderr, "");
  return { patch: freeformPatch, value: object(value.value, "Observed builtin value"), description, stdout: String(item.stdout), stderr: String(item.stderr),
    file: { path: freeformFile, sha256: digest(bytes), bytes: bytes.length, base64: bytes.toString("base64") }, tool };
}

export function freeformGatewayFields(transcript: FreeformTranscript): ObjectValue {
  return { boundary: "builtin-apply-patch", tools: [{ name: "apply_patch", description: transcript.description, input_schema: { type: "object", properties: { input: { type: "string" } }, required: ["input"], additionalProperties: false } }],
    declarations: [{ type: "custom", name: "apply_patch", description: transcript.description, format: { type: "text" } }],
    callbacks: [{ name: "apply_patch", arguments: { input: transcript.patch }, result: transcript.value }], freeform: { executable: transcript.tool.binary } };
}

/** Check the actual permission callback, standalone process effect and retained guest value together. */
export function compareFreeformBoundary(transcript: FreeformTranscript, candidate: ObjectValue, candidateDirectory: string): void {
  assert.equal(typeof candidateDirectory, "string", "Freeform comparison requires its actual candidate directory");
  assert.equal(resolve(candidateDirectory), candidateDirectory);
  assert.equal(candidate.boundary, "builtin-apply-patch");
  assert.ok(Array.isArray(candidate.callbacks) && candidate.callbacks.length === 1, "Freeform permission callback is missing");
  const callback = object(candidate.callbacks[0], "Freeform callback");
  assert.equal(callback.name, "apply_patch"); assert.deepEqual(callback.arguments, { input: transcript.patch });
  assert.deepEqual(callback.result, transcript.value);
  assert.ok(typeof callback.id === "string" && callback.id.length > 0, "Freeform callback has no fresh permission identity");
  assert.ok(Array.isArray(candidate.freeformEffects) && candidate.freeformEffects.length === 1, "Freeform did not execute its effect adapter");
  const effect = object(candidate.freeformEffects[0], "Freeform process effect");
  assert.equal(effect.callbackId, callback.id); assert.deepEqual(effect.executable, transcript.tool.binary);
  assert.deepEqual(effect.argv, [transcript.tool.binary.path, transcript.patch]); assert.equal(effect.inputSha256, digest(transcript.patch));
  assert.ok(Number.isSafeInteger(effect.pid) && Number(effect.pid) > 0, "Freeform tool has no actual process identity");
  assert.equal(effect.exit, 0); assert.equal(effect.stdout, transcript.stdout); assert.equal(effect.stderr, transcript.stderr);
  assert.deepEqual(effect.files, [transcript.file], "Freeform candidate file effects differ from the actual source");
  const workspace = resolve(candidateDirectory, "workspace"), path = resolve(workspace, freeformFile);
  assert.equal(realpathSync(path), path, "Freeform candidate file escaped its own disposable workspace");
  assert.deepEqual(readdirSync(workspace).sort(), [freeformFile], "Freeform candidate has unbound file effects");
  const bytes = bounded(path, 1024);
  assert.deepEqual({ path: freeformFile, bytes: bytes.length, sha256: digest(bytes), base64: bytes.toString("base64") }, transcript.file, "Reported file effect differs from the actual candidate bytes");
  assert.equal(bounded(resolve(candidateDirectory, "freeform-tool-stdout.txt"), 64 * 1024).toString(), transcript.stdout);
  assert.equal(bounded(resolve(candidateDirectory, "freeform-tool-stderr.txt"), 64 * 1024).toString(), transcript.stderr);
  assert.ok(Array.isArray(effect.profiles) && effect.profiles.length === 1, "Freeform tool profile must be retained separately from CLI counters");
  const profile = object(effect.profiles[0], "Freeform tool profile");
  assert.equal(profile.path, resolve(candidateDirectory, `freeform-tool-profile/${effect.pid}.profraw`), "Freeform tool counters escaped their own candidate directory");
  verify(profile as unknown as Artifact, 128 * 1024 * 1024);
  assert.ok(Array.isArray(candidate.toolResults) && candidate.toolResults.length === 1, "Freeform guest result was not retained");
  assert.deepEqual(candidate.toolResults[0], { id: callback.id, origin: "GENERIC", disposition: "TOOL_OUTPUT", isError: false, payload: transcript.value, value: transcript.value });
}
