import { describe, expect, test } from "bun:test";
import { createHash } from "node:crypto";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { assertApplyPatchBuild, compareFreeformBoundary, freeformContents, freeformFile, freeformGatewayFields, freeformPatch, freeformScenario, sourceFreeformTranscript, type FreeformTool, type FreeformTranscript } from "./freeform-contract";
import type { CliRun, ObjectValue } from "./types";
import type { CurrentInventory } from "../current-inventory";

const sha = (bytes: Uint8Array | string) => createHash("sha256").update(bytes).digest("hex");
const revision = "360319d8dee62d87e2d59fc0c21805d79519cfed";
const description = "### `apply_patch`\nThis is a FREEFORM tool\napply_patch(input: string): Promise<unknown>\n### `next`\n";
function artifact(path: string) { const bytes = readFileSync(path); return { path, sha256: sha(bytes), bytes: bytes.length }; }

/** Synthetic guard input only; it is never a qualification execution or a source build witness. */
function fixture() {
  const directory = mkdtempSync(join(tmpdir(), "codemode-freeform-guard-")), workspace = join(directory, "workspace");
  mkdirSync(workspace); writeFileSync(join(workspace, freeformFile), freeformContents);
  const binary = join(directory, "apply_patch"), proof = join(directory, "build-proof.json");
  writeFileSync(binary, "guard-only executable bytes");
  writeFileSync(proof, JSON.stringify({ schema: 1, sourceRevision: revision, binary: artifact(binary) }));
  const tool: FreeformTool = { binary: artifact(binary), proof: artifact(proof) };
  const events = [{ type: "event_msg", payload: { type: "item_completed", thread_id: "guard-session", item: {
    type: "FileChange", id: "exec-11111111-2222-3333-4444-555555555555", status: "completed",
    changes: { [join(workspace, freeformFile)]: { type: "add", content: freeformContents } },
    stdout: `Success. Updated the following files:\nA ${freeformFile}\n`, stderr: "",
  } } }];
  writeFileSync(join(directory, "native-events.jsonl"), events.map(row => JSON.stringify(row)).join("\n") + "\n");
  const source = { scenario: structuredClone(freeformScenario), directory, reference: { sourceRevision: revision }, completion: { sessionId: "guard-session" }, freeformTool: tool,
    observations: [{ request: 1, input: [{ type: "additional_tools", tools: [{ type: "namespace", name: "functions", tools: [{ name: "exec", description }] }] }] },
      { request: 2, input: [{ type: "custom_tool_call", name: "exec", call_id: "probe-freeform-patch-0", input: (freeformScenario.actions[0] as { source: string }).source },
        { type: "custom_tool_call_output", call_id: "probe-freeform-patch-0", output: [{ type: "input_text", text: "Script completed" }, { type: "input_text", text: JSON.stringify({ tag: "freeform-patch", value: {} }) }] }] }],
  } as unknown as CliRun;
  return { directory, workspace, source, tool, events };
}
function candidate(transcript: FreeformTranscript, directory: string): ObjectValue {
  const profileDirectory = join(directory, "freeform-tool-profile"); mkdirSync(profileDirectory);
  const profile = join(profileDirectory, "1234.profraw"); writeFileSync(profile, "guard-only counters");
  writeFileSync(join(directory, "freeform-tool-stdout.txt"), transcript.stdout); writeFileSync(join(directory, "freeform-tool-stderr.txt"), transcript.stderr);
  return { boundary: "builtin-apply-patch", callbacks: [{ name: "apply_patch", id: "permission-actual", arguments: { input: freeformPatch }, result: {} }],
    freeformEffects: [{ callbackId: "permission-actual", executable: transcript.tool.binary, argv: [transcript.tool.binary.path, freeformPatch], inputSha256: sha(freeformPatch),
      pid: 1234, exit: 0, stdout: transcript.stdout, stderr: transcript.stderr, files: [transcript.file], profiles: [artifact(profile)] }],
    toolResults: [{ id: "permission-actual", origin: "GENERIC", disposition: "TOOL_OUTPUT", isError: false, payload: {}, value: {} }],
  };
}

describe("builtin freeform effect evidence", () => {
  test("source value, session-owned FileChange and exact UTF-8 file bytes produce one freeform callback", () => {
    const f = fixture();
    try {
      const observed = sourceFreeformTranscript(f.source), fields = freeformGatewayFields(observed);
      expect(observed.file).toEqual({ path: freeformFile, bytes: 30, sha256: "81faa44f888c95d5e62d8ffc2b93c08d0f2498e30533c3515a96ed452093ff57", base64: Buffer.from(freeformContents).toString("base64") });
      expect(fields.callbacks).toEqual([{ name: "apply_patch", arguments: { input: freeformPatch }, result: {} }]);
      expect((fields.declarations as ObjectValue[])[0]?.type).toBe("custom");
      expect(() => compareFreeformBoundary(observed, candidate(observed, f.directory), f.directory)).not.toThrow();
    } finally { rmSync(f.directory, { recursive: true, force: true }); }
  });

  test("a label, missing native effect, foreign session or changed file cannot invent the builtin value", () => {
    for (const mutate of [
      (f: ReturnType<typeof fixture>) => { delete (f.source as CliRun & { freeformTool?: FreeformTool }).freeformTool; },
      (f: ReturnType<typeof fixture>) => { f.source.scenario.mcp = true; },
      (f: ReturnType<typeof fixture>) => { (f.source.scenario.actions[0] as { source: string }).source = "text({})"; },
      (f: ReturnType<typeof fixture>) => { (f.source.observations[0]!.input[0]!.tools as ObjectValue[])[0]!.tools = []; },
      (f: ReturnType<typeof fixture>) => { f.events[0]!.payload.thread_id = "foreign-session"; },
      (f: ReturnType<typeof fixture>) => { f.events[0]!.payload.item.status = "failed"; },
      (f: ReturnType<typeof fixture>) => { f.events[0]!.payload.item.changes = {}; },
      (f: ReturnType<typeof fixture>) => { writeFileSync(join(f.workspace, freeformFile), "changed\n"); },
      (f: ReturnType<typeof fixture>) => { writeFileSync(join(f.workspace, "unbound.txt"), "extra\n"); },
      (f: ReturnType<typeof fixture>) => { writeFileSync(f.tool.binary.path, "replaced executable"); },
      (f: ReturnType<typeof fixture>) => { ((f.source.observations[1]!.input[1]!.output as ObjectValue[])[1]!).text = '{"tag":"freeform-patch","value":"stdout"}'; },
    ]) {
      const f = fixture();
      try {
        mutate(f); writeFileSync(join(f.directory, "native-events.jsonl"), f.events.map(row => JSON.stringify(row)).join("\n") + "\n");
        expect(() => sourceFreeformTranscript(f.source)).toThrow();
      } finally { rmSync(f.directory, { recursive: true, force: true }); }
    }
  });

  test("candidate callback, real process evidence, file bytes and retained result must agree", () => {
    const f = fixture();
    try {
      const observed = sourceFreeformTranscript(f.source), actual = candidate(observed, f.directory);
      for (const mutate of [
        (x: ObjectValue) => { x.boundary = "typed-mcp-delegate"; },
        (x: ObjectValue) => { x.freeformEffects = []; },
        (x: ObjectValue) => { (x.callbacks as ObjectValue[])[0]!.arguments = { input: "different patch" }; },
        (x: ObjectValue) => { (x.freeformEffects as ObjectValue[])[0]!.callbackId = "foreign"; },
        (x: ObjectValue) => { (x.freeformEffects as ObjectValue[])[0]!.pid = 0; },
        (x: ObjectValue) => { (x.freeformEffects as ObjectValue[])[0]!.exit = 1; },
        (x: ObjectValue) => { (x.freeformEffects as ObjectValue[])[0]!.stdout = "fake success"; },
        (x: ObjectValue) => { (x.freeformEffects as ObjectValue[])[0]!.files = []; },
        (x: ObjectValue) => { (x.freeformEffects as ObjectValue[])[0]!.profiles = []; },
        (x: ObjectValue) => { (x.toolResults as ObjectValue[])[0]!.origin = "MCP"; },
        (x: ObjectValue) => { (x.toolResults as ObjectValue[])[0]!.disposition = "REJECT"; },
        (x: ObjectValue) => { (x.toolResults as ObjectValue[])[0]!.value = "Success"; },
      ]) { const forged = structuredClone(actual); mutate(forged); expect(() => compareFreeformBoundary(observed, forged, f.directory)).toThrow(); }
      writeFileSync(join(f.workspace, freeformFile), "reported effect is absent\n");
      expect(() => compareFreeformBoundary(observed, actual, f.directory)).toThrow();
      writeFileSync(join(f.workspace, freeformFile), freeformContents);
      writeFileSync(join(f.directory, "freeform-tool-profile/1234.profraw"), "changed profile");
      expect(() => compareFreeformBoundary(observed, actual, f.directory)).toThrow();
    } finally { rmSync(f.directory, { recursive: true, force: true }); }
  });

  test("a matching executable hash is insufficient without the current immutable source-build chain", async () => {
    const f = fixture();
    try {
      const inventory = { sourceRevision: "0000000000000000000000000000000000000000", sourceTree: "tree" } as CurrentInventory;
      await expect(assertApplyPatchBuild(f.tool, inventory)).rejects.toThrow();
    } finally { rmSync(f.directory, { recursive: true, force: true }); }
  });
});
