import { describe, test, expect } from "bun:test";
import { requiredCaseIds, contractFindings, structuralFindings, sealReceipt, artifact, rawArtifactFindings, type Receipt } from "./qualification";
import { verifiers } from "./verifiers";
import { assertNativeTypedMedia, assertAcceptedCallbackText, assertInterruptedCallbackText } from "./interactive-output";
import { assertNativeBatch, assertNativeRestartBatch, assertNativeOutOfOrderBatch } from "./interactive-batch";
import { assertCandidateWorkerLoss } from "./candidate-loss";
import { resolveEvidenceDirectory } from "./evidence-location";
import type { TraceEvent } from "./native-reference";
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
  outputs[0]!.output.push({ type: "input_text", text: "accepted" });
  outputs[1]!.output.push({ type: "input_text", text: "native-batch-second-epoch:1200" });
  outputs[1]!.output.push({ type: "input_text", text: "effect-file:1100" });
  return { scriptAttempts: 1, callbackTimes: [1100], marks: [
    ...originals.map(item => ({ event: "upstream_sse", epochMs: 1000, frame: { type: "response.output_item.done", item } })),
    { event: "batch_items_done", epochMs: 1000 },
    { event: "client_sse", epochMs: 1050, frame: { type: "content_block_start", content_block: { type: "tool_use", name: "Bash", id: "callback-once" } } },
    { event: "client_callback_result_received", epochMs: 1150, results: [{ tool_use_id: "callback-once", content: "accepted" }] },
    { event: "client_sse", epochMs: 1175, frame: { type: "content_block_start", content_block: { type: "tool_use", name: "Read", id: "callback-read" } } },
    { event: "client_callback_result_received", epochMs: 1190, results: [{ tool_use_id: "callback-read", content: "effect-file:1100" }] },
    { event: "response_completed_sent", epochMs: 3000 },
  ] as any[], upstreamRequests: [{ nativeInput: [...originals, ...outputs] as any[] }] };
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
  test("rejects second execution held until terminal and a late first callback result", () => {
    const held = batchObservation(); held.upstreamRequests[0]!.nativeInput[3].output[2].text = "native-batch-second-epoch:3001";
    expect(() => assertNativeBatch(held)).toThrow();
    const late = batchObservation(); late.marks[late.marks.length - 2] = { event: "client_callback_result_received", epochMs: 3001 };
    expect(() => assertNativeBatch(late)).toThrow();
  });
  test("rejects retried sources, a short hold, or an effect after terminal", () => {
    const retried = batchObservation(); retried.scriptAttempts = 2;
    expect(() => assertNativeBatch(retried)).toThrow();
    const early = batchObservation(); early.marks[early.marks.length - 1] = { event: "response_completed_sent", epochMs: 1500 };
    expect(() => assertNativeBatch(early)).toThrow();
    const late = batchObservation(); late.callbackTimes = [3001];
    expect(() => assertNativeBatch(late)).toThrow();
  });
  test("rejects a second script without its own actual Read callback and accepted file result", () => {
    const missing = batchObservation(); missing.marks = missing.marks.filter(mark => mark.frame?.content_block?.name !== "Read");
    expect(() => assertNativeBatch(missing)).toThrow();
    const reused = batchObservation(); reused.marks.find(mark => mark.frame?.content_block?.name === "Read")!.frame!.content_block!.id = "callback-once";
    expect(() => assertNativeBatch(reused)).toThrow();
    const forged = batchObservation(); forged.marks.find(mark => mark.event === "client_callback_result_received" && mark.results?.[0]?.tool_use_id === "callback-read")!.results![0]!.content = "source contained tools.Read";
    expect(() => assertNativeBatch(forged)).toThrow();
    const dropped = batchObservation(); dropped.upstreamRequests[0]!.nativeInput[3].output.pop();
    expect(() => assertNativeBatch(dropped)).toThrow();
  });
});

function workerLossTrace(): TraceEvent[] {
  return [
    { at: 1, direction: "harness", frame: { type: "harness/native-host-kill", pid: 123, signal: "SIGKILL" } },
    { at: 2, direction: "sent", frame: { type: "operation/request", id: 7, request: { method: "session/wait" } } },
    { at: 3, direction: "received", frame: { type: "operation/response", id: 7, result: { status: "error",
      errorType: "splice.upstream.failure.CodeModeWorkerLostException", message: "Code-mode worker was stopped; source was not rerun" } } },
  ];
}
describe("qualification worker-loss guard (not product parity)", () => {
  test("requires the actual typed operation error after killing the candidate child", () => {
    expect(() => assertCandidateWorkerLoss(workerLossTrace())).not.toThrow();
  });
  test("rejects a hung adapter or a generic worker-looking error", () => {
    const missing = workerLossTrace(); missing.pop();
    expect(() => assertCandidateWorkerLoss(missing)).toThrow();
    const untyped = workerLossTrace(); delete untyped[2]!.frame.result.errorType;
    expect(() => assertCandidateWorkerLoss(untyped)).toThrow();
  });
  test("rejects success and unmatched operation replies", () => {
    const success = workerLossTrace(); success[2]!.frame.result.status = "ok";
    expect(() => assertCandidateWorkerLoss(success)).toThrow();
    const unmatched = workerLossTrace(); unmatched[2]!.frame.id = 8;
    expect(() => assertCandidateWorkerLoss(unmatched)).toThrow();
  });
});

function restartBatchObservation() {
  const value = batchObservation();
  value.upstreamRequests[0]!.nativeInput[2].output = "Script terminated\nWall time 0.0 seconds\nOutput:\n" + JSON.stringify({
    status: "interrupted", sourceRerun: false, results: [{ id: "callback-once", output: "accepted", isError: false }], unresolved: [],
  });
  return value;
}
describe("qualification queued-source restart guard (not product parity)", () => {
  test("requires honest first-source loss plus completed queued-source output", () => {
    expect(() => assertNativeRestartBatch(restartBatchObservation())).not.toThrow();
    expect(() => assertNativeRestartBatch(batchObservation())).toThrow();
  });
  test("rejects a rerun declaration or missing accepted callback evidence", () => {
    const rerun = restartBatchObservation();
    rerun.upstreamRequests[0]!.nativeInput[2].output = rerun.upstreamRequests[0]!.nativeInput[2].output.replace('"sourceRerun":false', '"sourceRerun":true');
    expect(() => assertNativeRestartBatch(rerun)).toThrow();
    const missing = restartBatchObservation();
    missing.upstreamRequests[0]!.nativeInput[2].output = missing.upstreamRequests[0]!.nativeInput[2].output.replace('"callback-once"', '"different-callback"');
    expect(() => assertNativeRestartBatch(missing)).toThrow();
  });
  test("rejects a queued sibling that did not produce its real completed output", () => {
    const value = restartBatchObservation(); value.upstreamRequests[0]!.nativeInput[3].output[1].text = "native-batch-first";
    expect(() => assertNativeRestartBatch(value)).toThrow();
  });
});

function outOfOrderBatchObservation() {
  const value = batchObservation(); const input = value.upstreamRequests[0]!.nativeInput;
  value.marks = [
    { event: "upstream_sse", epochMs: 900, frame: { type: "response.output_item.added", output_index: 0, item: input[1] } },
    { event: "upstream_sse", epochMs: 901, frame: { type: "response.output_item.added", output_index: 1, item: input[0] } },
    { event: "upstream_sse", epochMs: 1000, frame: { type: "response.output_item.done", output_index: 1, item: input[0] } },
    { event: "client_sse", epochMs: 1050, frame: { type: "content_block_start", content_block: { type: "tool_use", name: "Bash", id: "callback-once" } } },
    { event: "client_callback_result_received", epochMs: 1150, results: [{ tool_use_id: "callback-once", content: "accepted" }] },
    { event: "upstream_sse", epochMs: 3000, frame: { type: "response.output_item.done", output_index: 0, item: input[1] } },
    { event: "batch_items_done", epochMs: 3000 },
    { event: "client_sse", epochMs: 3100, frame: { type: "content_block_start", content_block: { type: "tool_use", name: "Read", id: "callback-read" } } },
    { event: "client_callback_result_received", epochMs: 3150, results: [{ tool_use_id: "callback-read", content: "effect-file:1100" }] },
    { event: "response_completed_sent", epochMs: 5000 },
    { event: "upstream_sse", epochMs: 5000, frame: { type: "response.completed", response: { output: [input[1], input[0]] } } },
  ] as any;
  input[3].output[2].text = "native-batch-second-epoch:3200";
  return value;
}
describe("qualification completed-item arrival guard (not product parity)", () => {
  test("accepts completed item one while item zero remains incomplete", () => {
    expect(() => assertNativeOutOfOrderBatch(outOfOrderBatchObservation())).not.toThrow();
  });
  test("rejects blocking the completed item's real effect behind its incomplete sibling", () => {
    const value = outOfOrderBatchObservation(); value.callbackTimes = [3100];
    expect(() => assertNativeOutOfOrderBatch(value)).toThrow();
  });
  test("rejects reconstructing model history in item-index order", () => {
    const value = outOfOrderBatchObservation(); const input = value.upstreamRequests[0]!.nativeInput;
    [input[0], input[1]] = [input[1], input[0]];
    expect(() => assertNativeOutOfOrderBatch(value)).toThrow();
  });
});

describe("qualification evidence-location guard (not product parity)", () => {
  test("uses the last recorded receipt directory and explicit evidence wins", async () => {
    const directory = await mkdtemp(join(tmpdir(), "splice-evidence-pointer-"));
    const progress = join(directory, "progress.json");
    try {
      await writeFile(progress, JSON.stringify({ schema: 1, receiptPath: join(directory, "latest", "receipt.json") }));
      expect(await resolveEvidenceDirectory(undefined, progress, join(directory, "older"))).toBe(join(directory, "latest"));
      await writeFile(progress, "malformed");
      expect(await resolveEvidenceDirectory(join(directory, "explicit"), progress, join(directory, "older"))).toBe(join(directory, "explicit"));
    } finally { await rm(directory, { recursive: true, force: true }); }
  });
  test("falls back only when progress or a recorded receipt is absent", async () => {
    const directory = await mkdtemp(join(tmpdir(), "splice-evidence-pointer-"));
    const progress = join(directory, "progress.json"), fallback = join(directory, "canonical");
    try {
      expect(await resolveEvidenceDirectory(undefined, progress, fallback)).toBe(fallback);
      await writeFile(progress, JSON.stringify({ schema: 1, receiptPath: null }));
      expect(await resolveEvidenceDirectory(undefined, progress, fallback)).toBe(fallback);
      const missingReceipt = join(directory, "missing", "receipt.json");
      await writeFile(progress, JSON.stringify({ schema: 1, receiptPath: missingReceipt }));
      expect(await resolveEvidenceDirectory(undefined, progress, fallback)).toBe(join(directory, "missing"));
    } finally { await rm(directory, { recursive: true, force: true }); }
  });
  test("malformed progress never falls back to an older receipt or erases the pointer", async () => {
    const directory = await mkdtemp(join(tmpdir(), "splice-evidence-pointer-"));
    const progress = join(directory, "progress.json");
    try {
      for (const raw of ["broken json", "null", "[]", '{"schema":1}',
        JSON.stringify({ schema: 1, receiptPath: "relative/receipt.json" }),
        JSON.stringify({ schema: 1, receiptPath: join(directory, "other.json") }),
        JSON.stringify({ schema: 1, receiptPath: 123 }),
        JSON.stringify({ schema: 1, receiptPath: directory + "/../older/receipt.json" })]) {
        await writeFile(progress, raw);
        await expect(resolveEvidenceDirectory(undefined, progress, join(directory, "older"))).rejects.toThrow("malformed qualification progress");
        expect(await Bun.file(progress).text()).toBe(raw);
      }
    } finally { await rm(directory, { recursive: true, force: true }); }
  });
  test("a valid location pointer cannot qualify an asserted PASS receipt", async () => {
    const directory = await mkdtemp(join(tmpdir(), "splice-evidence-pointer-"));
    const progress = join(directory, "progress.json");
    try {
      await writeFile(progress, JSON.stringify({ schema: 1, receiptPath: join(directory, "receipt.json") }));
      await writeFile(join(directory, "receipt.json"), '{"pass":true}');
      const selected = await resolveEvidenceDirectory(undefined, progress, join(directory, "older"));
      expect(structuralFindings(await Bun.file(join(selected, "receipt.json")).json(), expected, key)).toEqual(["malformed qualification receipt"]);
    } finally { await rm(directory, { recursive: true, force: true }); }
  });
});

describe("qualification plain callback text guard (not product parity)", () => {
  const completed = (text: string) => [{ nativeInput: [{ type: "custom_tool_call_output", call_id: "call_probe", output: [
    { type: "input_text", text: "Script completed\n" }, { type: "input_text", text },
  ] }] }];
  test("accepts literal non-JSON callback stdout in completed model output", () => {
    expect(() => assertAcceptedCallbackText(completed("accepted"))).not.toThrow();
  });
  test("rejects quoted or serialized lookalikes and absent completion after host EOF", () => {
    expect(() => assertAcceptedCallbackText(completed('"accepted"'))).toThrow();
    expect(() => assertAcceptedCallbackText(completed('{"text":"accepted"}'))).toThrow();
    expect(() => assertAcceptedCallbackText([])).toThrow();
  });
  test("cannot borrow accepted text from source or the unrelated second batch module", () => {
    const requests = completed("lost");
    requests[0]!.nativeInput.push({ type: "custom_tool_call", call_id: "call_probe", output: [{ type: "input_text", text: "accepted" }] });
    requests[0]!.nativeInput.push({ type: "custom_tool_call_output", call_id: "call_probe_second", output: [{ type: "input_text", text: "accepted" }] });
    expect(() => assertAcceptedCallbackText(requests)).toThrow();
  });
  test("requires exact accepted text in the honest interruption receipt", () => {
    const value = restartBatchObservation();
    expect(() => assertInterruptedCallbackText(value.upstreamRequests)).not.toThrow();
    value.upstreamRequests[0]!.nativeInput[2].output = value.upstreamRequests[0]!.nativeInput[2].output.replace('"output":"accepted"', '"output":"changed"');
    expect(() => assertInterruptedCallbackText(value.upstreamRequests)).toThrow();
  });
});
