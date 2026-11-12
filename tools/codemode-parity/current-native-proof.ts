import assert from "node:assert/strict";
import { chmod, mkdir, mkdtemp, readFile, rename, rm, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { homedir } from "node:os";
import { zipEntry } from "../release/src/lib/zip";
import { sha256 } from "./qualification";

// Trust anchors are independent of the claimed verification reports. Updating them is a code review.
export const cosignPin = {
  release: "v3.1.4",
  platforms: {
    "linux-x64": { name: "cosign-linux-amd64", sha256: "424e2c97d0b45fd7a6b608a87f090f35633f4c23ece5f092d48bba11aaf4bbfa" },
    "linux-arm64": { name: "cosign-linux-arm64", sha256: "ade83b7aac62e131cacfe96d958948b2150cc9efec46a570a2649b8e27dedad2" },
  },
  trustedRootSha256: "6494e21ea73fa7ee769f85f57d5a3e6a08725eae1e38c755fc3517c9e6bc0b66",
} as const;
const repo = resolve(import.meta.dir, "../..");
const proofCache = () => resolve(process.env.CODEMODE_PROVENANCE_CACHE ?? resolve(process.env.XDG_CACHE_HOME ?? resolve(homedir(), ".cache"), "splice/codemode-provenance"));

async function command(argv: string[], outputPath?: string): Promise<{ exitCode: number; stdout: string; stderr: string }> {
  const child = Bun.spawn(argv, { stdin: "ignore", stdout: "pipe", stderr: "pipe" });
  const timeout = setTimeout(() => child.kill("SIGKILL"), 180_000);
  try {
    const stdout = outputPath ? Bun.write(outputPath, new Response(child.stdout)).then(() => "") : new Response(child.stdout).text();
    const [exitCode, out, stderr] = await Promise.all([child.exited, stdout, new Response(child.stderr).text()]);
    return { exitCode, stdout: out, stderr };
  } finally { clearTimeout(timeout); }
}
async function downloadPinned(url: string, path: string, digest: string): Promise<string> {
  try { if (sha256(await readFile(path)) === digest) return path; } catch { /* bootstrap */ }
  await mkdir(dirname(path), { recursive: true });
  const temporary = `${path}.${crypto.randomUUID()}.part`;
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(180_000) });
    assert.ok(response.ok, `official proof bootstrap failed (${response.status}): ${url}`);
    await Bun.write(temporary, response);
    assert.equal(sha256(await readFile(temporary)), digest, `downloaded verifier digest mismatch: ${url}`);
    await rename(temporary, path); return path;
  } finally { await rm(temporary, { force: true }); }
}
export async function pinnedCosign(): Promise<string> {
  const platform = `${process.platform}-${process.arch}` as keyof typeof cosignPin.platforms;
  const pin = cosignPin.platforms[platform];
  assert.ok(pin, `native provenance verification currently requires Linux x64/arm64; observed ${platform}`);
  const path = resolve(proofCache(), `cosign-${cosignPin.release}`, pin.name);
  await downloadPinned(`https://github.com/sigstore/cosign/releases/download/${cosignPin.release}/${pin.name}`, path, pin.sha256);
  await chmod(path, 0o755); return path;
}
/** The transcript is deliberately not an input: signature, certificate, SCT and Rekor proof are checked now. */
export async function verifyLinuxSigstore(bytes: Uint8Array, bundlePath: string, expected: { tag: string; sourceRevision: string }): Promise<void> {
  const bundle = JSON.parse(await readFile(bundlePath, "utf8"));
  assert.ok(typeof bundle.messageSignature?.signature === "string" && bundle.verificationMaterial?.certificate?.rawBytes && bundle.verificationMaterial?.tlogEntries?.length,
    "actual Sigstore signature/certificate/transparency bundle is missing; a fabricated report cannot establish provenance");
  const trustedRoot = resolve(import.meta.dir, "current-provenance/sigstore-trusted-root.json");
  assert.equal(sha256(await readFile(trustedRoot)), cosignPin.trustedRootSha256, "pinned Sigstore trust anchor changed");
  const verifier = await pinnedCosign(), temporary = await mkdtemp(resolve(proofCache(), "verify-"));
  try {
    const executable = resolve(temporary, "packaged-native-host"); await writeFile(executable, bytes);
    const checked = await command([verifier, "verify-blob", "--trusted-root", trustedRoot, "--bundle", bundlePath,
      "--certificate-identity", `https://github.com/openai/codex/.github/workflows/rust-release.yml@refs/tags/${expected.tag}`,
      "--certificate-oidc-issuer", "https://token.actions.githubusercontent.com", "--certificate-github-workflow-sha", expected.sourceRevision, executable]);
    assert.equal(checked.exitCode, 0, `actual pinned cosign signature/source verification failed: ${checked.stderr.trim()}`);
  } finally { await rm(temporary, { force: true, recursive: true }); }
}

export type ActionsProof = { workflowRunId: number; artifactId: number; artifactDigest: string; members: string[] };
/** Metadata is obtained directly from the official API over TLS, never from a saved verification report. */
async function officialJson(endpoint: string): Promise<any> {
  assert.match(endpoint, /^repos\/openai\/codex\/actions\/(?:runs|artifacts)\/\d+$/);
  const result = await command(["gh", "api", "--hostname", "github.com", endpoint]);
  assert.equal(result.exitCode, 0, `official Actions metadata unavailable; configure gh/GH_TOKEN for public artifact reads: ${result.stderr.trim()}`);
  return JSON.parse(result.stdout);
}
export function assertOfficialActionsMetadata(workflow: any, artifact: any, proof: ActionsProof, sourceRevision: string): void {
  assert.equal(workflow.id, proof.workflowRunId); assert.equal(workflow.head_sha, sourceRevision);
  assert.equal(workflow.repository?.full_name, "openai/codex"); assert.equal(workflow.path, ".github/workflows/rust-release.yml");
  assert.equal(workflow.status, "completed"); assert.equal(workflow.conclusion, "success");
  assert.equal(artifact.id, proof.artifactId); assert.equal(artifact.digest, proof.artifactDigest);
  assert.equal(artifact.workflow_run?.id, proof.workflowRunId); assert.equal(artifact.workflow_run?.head_sha, sourceRevision);
  assert.equal(artifact.workflow_run?.repository_id, workflow.repository.id);
  assert.equal(artifact.archive_download_url, `https://api.github.com/repos/openai/codex/actions/artifacts/${proof.artifactId}/zip`);
}
export async function verifyActionsArtifact(bytes: Uint8Array, proof: ActionsProof, sourceRevision: string): Promise<void> {
  assert.match(proof.artifactDigest, /^sha256:[a-f0-9]{64}$/);
  assert.ok(Number.isSafeInteger(proof.workflowRunId) && Number.isSafeInteger(proof.artifactId));
  assert.ok(Array.isArray(proof.members) && proof.members.length >= 1 && proof.members.length <= 2 && proof.members.every(member => typeof member === "string" && member.length > 0 && !member.split("/").includes("..")));
  const [workflow, metadata] = await Promise.all([officialJson(`repos/openai/codex/actions/runs/${proof.workflowRunId}`), officialJson(`repos/openai/codex/actions/artifacts/${proof.artifactId}`)]);
  assertOfficialActionsMetadata(workflow, metadata, proof, sourceRevision);
  const digest = proof.artifactDigest.slice(7), archive = resolve(proofCache(), "actions", `${proof.artifactId}-${digest}.zip`);
  await mkdir(dirname(archive), { recursive: true });
  let cached = false;
  try { cached = sha256(await readFile(archive)) === digest; } catch { /* bootstrap */ }
  if (!cached) {
    const partial = `${archive}.${crypto.randomUUID()}.part`;
    try {
      const result = await command(["gh", "api", "--hostname", "github.com", `repos/openai/codex/actions/artifacts/${proof.artifactId}/zip`], partial);
      assert.equal(result.exitCode, 0, `official Actions archive unavailable; restore authenticated gh access or the digest-addressed proof cache: ${result.stderr.trim()}`);
      assert.equal(sha256(await readFile(partial)), digest, "official Actions archive digest mismatch"); await rename(partial, archive);
    } finally { await rm(partial, { force: true }); }
  }
  // Re-read and extract actual archive bytes each time. A cached PASS/transcript is never consumed.
  assert.equal(sha256(await readFile(archive)), digest, "cached Actions archive changed");
  let extracted = zipEntry(archive, proof.members[0]!);
  if (proof.members.length === 2) {
    const temporary = await mkdtemp(resolve(proofCache(), "wheel-"));
    try { const wheel = resolve(temporary, "artifact.whl"); await writeFile(wheel, extracted); extracted = zipEntry(wheel, proof.members[1]!); }
    finally { await rm(temporary, { recursive: true, force: true }); }
  }
  assert.ok(Buffer.from(bytes).equals(extracted), "official exact-source Actions executable differs from the decompressed packaged payload");
}

export function provenancePath(path: string): string { return resolve(repo, path); }
