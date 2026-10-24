import { describe, test, expect } from "bun:test";
import { requiredCaseIds, contractFindings, structuralFindings, sealReceipt, artifact, rawArtifactFindings, type Receipt } from "./qualification";
import { verifiers } from "./verifiers";
import { assertNativeTypedMedia } from "./interactive-output";
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
