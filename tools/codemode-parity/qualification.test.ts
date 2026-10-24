import { describe, test, expect } from "bun:test";
import { requiredCaseIds, contractFindings, structuralFindings, sealReceipt, artifact, rawArtifactFindings, type Receipt } from "./qualification";
import { verifiers } from "./verifiers";
import { assertNativeTypedMedia } from "./interactive-output";
import { assertNativeBatch } from "./interactive-batch";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";

const digest = "a".repeat(64), candidate = "b".repeat(64), native = "c".repeat(64);
const key = Buffer.alloc(32, 4);
const now = Date.now();
const expected = { contractSha256: digest, harnessSha256: digest, referenceSha256: digest,
  candidateJarSha256: candidate, candidateNativeHostSha256: native, now };
function receipt(): Receipt {
  const runId = "f".repeat(32);
  const value: Receipt = { schema: 1, runId, startedAt: new Date(now - 1000).toISOString(), finishedAt: new Date(now).toISOString(),
    contractSha256: digest, harnessSha256: digest, reference: { path: "/test/reference", sha256: digest },
    candidateJar: { path: "/test/candidate.jar", sha256: candidate }, candidateNativeHost: { path: "/test/native", sha256: native },
    referenceSourceLinkage: "unproven", qualified: true, seal: "",
    cases: requiredCaseIds.map(id => ({ id, runId, state: "executed", verifier: verifiers[id] ?? "unavailable",
      findings: [], artifacts: [{ path: `/test/raw/${id}`, bytes: 5, sha256: digest }] })),
    installed: { pid: 123, hostPid: 456, jarFd: "/proc/123/fd/9", jarSha256: candidate, hostExeSha256: native },
  };
  value.seal = sealReceipt(value, key); return value;
}
function findings(value: Receipt): string[] {
  // Reseal negative probes so each structural rule must stand on its own.
  value.seal = sealReceipt(value, key);
  return structuralFindings(value, expected, key);
}
describe("qualification guard negative probes (not product parity)", () => {
  test("freezes all 24 required families independently of editable receipt inventory", async () => {
    const contract = await Bun.file(new URL("./contract.json", import.meta.url)).json();
    expect(requiredCaseIds).toHaveLength(24);
    expect(contractFindings(contract)).toEqual([]);
    contract.cases.pop(); expect(contractFindings(contract)).toContain("contract must contain every frozen family exactly once");
  });
  test("remove a required case", () => {
    const value = receipt(); value.cases.pop();
    expect(findings(value)).toContain("missing, duplicate, or unknown case inventory");
  });
  test("duplicate a case while retaining the count", () => {
    const value = receipt(); value.cases[23] = structuredClone(value.cases[0]!);
    expect(findings(value)).toContain("missing, duplicate, or unknown case inventory");
  });
  test("skip a required case", () => {
    const value = receipt(); value.cases[0]!.state = "missing";
    expect(findings(value)).toContain("runtime.module-semantics: not executed successfully");
  });
  test("two-smoke subset can never imply 24-family qualification", () => {
    const value = receipt(); value.cases = value.cases.filter(item => ["runtime.explicit-yield", "runtime.wait-suffix"].includes(item.id));
    expect(findings(value)).toContain("missing, duplicate, or unknown case inventory");
  });
  test("swap candidate jar/nativehost and reference identities", () => {
    const value = receipt(); value.candidateJar.sha256 = digest; value.candidateNativeHost.sha256 = digest; value.reference.sha256 = candidate;
    expect(findings(value)).toEqual(expect.arrayContaining(["candidate jar hash mismatch", "candidate native host hash mismatch", "reference executable hash mismatch"]));
  });
  test("stale contract, harness, run and time are rejected", () => {
    const value = receipt(); value.contractSha256 = candidate; value.harnessSha256 = candidate;
    value.cases[0]!.runId = "e".repeat(32); value.startedAt = new Date(now - 90_000_000).toISOString();
    expect(findings(value)).toEqual(expect.arrayContaining(["contract hash mismatch", "harness hash mismatch", "runtime.module-semantics: stale run evidence", "stale or malformed evidence time"]));
  });
  test("manual PASS and unimplemented verifier cannot qualify", () => {
    const value = receipt(); (value.cases[0] as any).pass = true;
    expect(findings(value)).toContain("runtime.module-semantics: manually asserted result fields are forbidden");
    value.cases[0]!.verifier = "codemode-parity/asserted-success";
    expect(findings(value)).toContain("runtime.module-semantics: no executable verifier");
  });
  test("modified or unsealed receipt fails even with all PASS assertions", () => {
    const value = receipt(); value.seal = "0".repeat(64);
    expect(structuralFindings(value, expected, key)).toContain("unsealed or modified receipt; asserted PASS is not execution evidence");
  });
  test("missing and malformed raw artifacts fail", () => {
    const value = receipt(); value.cases[0]!.artifacts = [];
    value.cases[1]!.artifacts[0]!.sha256 = "PASS";
    expect(findings(value)).toEqual(expect.arrayContaining(["runtime.module-semantics: missing or malformed raw artifact", "runtime.item-boundary: missing or malformed raw artifact"]));
  });
  test("edited and deleted raw artifact bytes fail independently of receipt sealing", async () => {
    const directory = await mkdtemp(join(tmpdir(), "splice-qualification-negative-"));
    const path = join(directory, "trace.json");
    try {
      await writeFile(path, '{"effect":"once"}\n');
      const value = receipt(); value.cases = [value.cases[0]!]; value.cases[0]!.artifacts = [await artifact(path)];
      expect(await rawArtifactFindings(value)).toEqual([]);
      await writeFile(path, '{"effect":"twice"}\n');
      expect(await rawArtifactFindings(value)).toEqual(["runtime.module-semantics: raw artifact changed"]);
      await rm(path);
      expect(await rawArtifactFindings(value)).toEqual(["runtime.module-semantics: raw artifact is unavailable"]);
    } finally { await rm(directory, { recursive: true, force: true }); }
  });
  test("path identity without an installed open descriptor fails", () => {
    const value = receipt(); value.installed!.jarFd = "/opt/splice/splice.jar";
    expect(findings(value)).toContain("installed open jar/native executable identity is missing or mismatched");
  });
  test("malformed receipts fail closed", () => {
    expect(structuralFindings({ pass: true }, expected, key)).toEqual(["malformed qualification receipt"]);
  });
});


const nativeMedia = [
  { type: "input_image", image_url: "data:image/png;base64,aGVsbG8=", detail: "original" },
  { type: "input_audio", audio_url: "data:audio/wav;base64,aGVsbG8=" },
  { type: "input_image", image_url: "data:image/png;base64,d29ybGQ=", detail: "high" },
  { type: "input_text", text: "native-generated" },
];
const mediaRequests = (output: unknown) => [{ nativeInput: [{ type: "function_call_output", call_id: "wait_call_1", output }] }];
describe("qualification typed-output guard (not product parity)", () => {
  test("rejects media descriptors serialized in plain output text", () => {
    expect(() => assertNativeTypedMedia(mediaRequests([{ type: "input_text", text: JSON.stringify(nativeMedia) }]))).toThrow();
    expect(() => assertNativeTypedMedia(mediaRequests(JSON.stringify(nativeMedia)))).toThrow();
  });
  test("rejects nested metadata and content on non-output protocol items", () => {
    expect(() => assertNativeTypedMedia(mediaRequests([{ type: "input_text", text: "lookalike", metadata: nativeMedia }]))).toThrow();
    expect(() => assertNativeTypedMedia([{ nativeInput: [{ type: "custom_tool_call", output: nativeMedia }] }])).toThrow();
    expect(() => assertNativeTypedMedia(mediaRequests([nativeMedia]))).toThrow();
  });
  test("accepts genuine direct typed protocol output content", () => {
    expect(() => assertNativeTypedMedia(mediaRequests(nativeMedia))).not.toThrow();
  });
  test("does not recover media from stale output superseded under the same call ID", () => {
    expect(() => assertNativeTypedMedia([
      ...mediaRequests(nativeMedia), ...mediaRequests([{ type: "input_text", text: "Script terminated" }]),
    ])).toThrow();
  });
});

function batchObservation() {
  const originals = [
    { type: "custom_tool_call", id: "exec_probe", call_id: "call_probe", name: "exec", input: "text('native-batch-first');", status: "completed" },
    { type: "custom_tool_call", id: "exec_probe_second", call_id: "call_probe_second", name: "exec", input: "text('native-batch-second');", status: "completed" },
  ];
  const outputs = originals.map((item, index) => ({ type: "custom_tool_call_output", call_id: item.call_id, output: [
    { type: "input_text", text: "Script completed\nWall time 0.1 seconds\nOutput:\n" },
    { type: "input_text", text: index === 0 ? "native-batch-first" : "native-batch-second" },
  ] }));
  return { scriptAttempts: 1, callbackTimes: [1100], marks: [
    ...originals.map(item => ({ event: "upstream_sse", frame: { type: "response.output_item.done", item } })),
    { event: "batch_items_done", epochMs: 1000 }, { event: "response_completed_sent", epochMs: 3000 },
  ], upstreamRequests: [{ nativeInput: [...originals, ...outputs] as any[] }] };
}
describe("qualification multi-item admission guard (not product parity)", () => {
  test("accepts both original items followed by their actual distinct completed outputs", () => {
    expect(() => assertNativeBatch(batchObservation())).not.toThrow();
  });
  test("rejects absent second output even when its marker appears in source", () => {
    const value = batchObservation(); value.upstreamRequests[0]!.nativeInput.pop();
    expect(() => assertNativeBatch(value)).toThrow();
  });
  test("rejects serialized marker lookalikes instead of actual executed output", () => {
    const value = batchObservation(); value.upstreamRequests[0]!.nativeInput[3].output[1].text = JSON.stringify({ text: "native-batch-second" });
    expect(() => assertNativeBatch(value)).toThrow();
  });
  test("rejects interleaved originals and outputs or duplicated batch items", () => {
    const value = batchObservation(); const input = value.upstreamRequests[0]!.nativeInput;
    [input[1], input[2]] = [input[2], input[1]];
    expect(() => assertNativeBatch(value)).toThrow();
    const duplicate = batchObservation(); duplicate.upstreamRequests[0]!.nativeInput.push(duplicate.upstreamRequests[0]!.nativeInput[1]);
    expect(() => assertNativeBatch(duplicate)).toThrow();
  });
  test("rejects retried sources, a short hold, or an effect after terminal", () => {
    const retried = batchObservation(); retried.scriptAttempts = 2;
    expect(() => assertNativeBatch(retried)).toThrow();
    const early = batchObservation(); early.marks[early.marks.length - 1] = { event: "response_completed_sent", epochMs: 1500 };
    expect(() => assertNativeBatch(early)).toThrow();
    const late = batchObservation(); late.callbackTimes = [3001];
    expect(() => assertNativeBatch(late)).toThrow();
  });
});
