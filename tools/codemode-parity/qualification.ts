import { createHash, createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import { readdir, readFile, realpath, stat, writeFile, mkdir } from "node:fs/promises";
import { dirname, resolve, relative } from "node:path";
import { verifiers } from "./verifiers";

export const requiredCaseIds = [
  "runtime.module-semantics", "runtime.item-boundary", "runtime.sequential-callbacks",
  "runtime.partial-parallel-results", "runtime.explicit-yield", "runtime.timed-yield",
  "runtime.wait-suffix", "runtime.terminate", "runtime.notifications", "runtime.timers",
  "runtime.session-store", "runtime.output-content", "runtime.errors-and-cancellation",
  "history.append-only", "history.restart", "history.replay-is-not-steering", "history.real-steering",
  "history.cleared-results", "delivery.readable-reasoning", "delivery.interactive-cadence",
  "delivery.late-failure-recovery", "packaging.native-host", "evidence.failing-baseline", "evidence.installed-identity",
] as const;
export type CaseId = typeof requiredCaseIds[number];
export type Identity = { path: string; sha256: string };
export type Artifact = { path: string; sha256: string; bytes: number };
export type CaseEvidence = {
  id: CaseId; state: "executed" | "failed" | "missing"; verifier: string;
  runId: string; artifacts: Artifact[]; findings: string[];
};
export type Receipt = {
  schema: 1; runId: string; startedAt: string; finishedAt: string;
  contractSha256: string; harnessSha256: string;
  reference: Identity; candidateJar: Identity; candidateNativeHost: Identity;
  referenceSourceLinkage: "unproven" | "verified";
  cases: CaseEvidence[]; installed?: { pid: number; jarFd: string; jarSha256: string; hostPid: number; hostExeSha256: string };
  qualified: boolean; seal: string;
};
export const sha256 = (bytes: string | Uint8Array): string => createHash("sha256").update(bytes).digest("hex");
export async function identity(path: string): Promise<Identity> {
  const canonical = await realpath(path);
  return { path: canonical, sha256: sha256(await readFile(canonical)) };
}
export async function artifact(path: string): Promise<Artifact> {
  const found = await identity(path);
  return { ...found, bytes: (await stat(found.path)).size };
}
export async function rawArtifactFindings(receipt: Pick<Receipt, "cases">): Promise<string[]> {
  const findings: string[] = [];
  for (const item of receipt.cases) for (const entry of item.artifacts ?? []) {
    try {
      const found = await artifact(entry.path);
      if (found.sha256 !== entry.sha256 || found.bytes !== entry.bytes) findings.push(`${item.id}: raw artifact changed`);
    } catch { findings.push(`${item.id}: raw artifact is unavailable`); }
  }
  return findings;
}
export async function harnessHash(directory: string): Promise<string> {
  const files: string[] = [];
  const walk = async (path: string) => {
    for (const entry of await readdir(path, { withFileTypes: true })) {
      const next = resolve(path, entry.name);
      if (entry.isDirectory()) await walk(next);
      else if (/\.(?:ts|json|rs)$/.test(entry.name) && entry.name !== "progress.json" && entry.name !== "contract.json") files.push(next);
    }
  };
  await walk(directory);
  // Imported archive reader is part of the executable harness identity too.
  files.push(resolve(directory, "../release/src/lib/zip.ts"));
  const hash = createHash("sha256");
  for (const file of files.sort()) hash.update(relative(directory, file) + "\0").update(await readFile(file)).update("\0");
  return hash.digest("hex");
}
export function contractFindings(contract: any): string[] {
  const findings: string[] = [];
  const ids = Array.isArray(contract?.cases) ? contract.cases.map((item: any) => item.id) : [];
  if (ids.length !== requiredCaseIds.length || new Set(ids).size !== ids.length ||
    requiredCaseIds.some(id => !ids.includes(id))) findings.push("contract must contain every frozen family exactly once");
  if (contract?.policy?.missingCase !== "fail" || contract?.policy?.skippedCase !== "fail" ||
    contract?.policy?.unverifiedReference !== "fail" || contract?.policy?.candidateIdentityMismatch !== "fail" ||
    contract?.policy?.allowSubsetCompletion !== false || contract?.policy?.sourceInspectionCountsAsExecution !== false ||
    contract?.policy?.benchmarkMeansProveParity !== false) findings.push("contract fail-closed policy changed");
  return findings;
}
const payload = (receipt: Receipt): string => JSON.stringify({ ...receipt, seal: undefined });
export function sealReceipt(receipt: Receipt, key: Uint8Array): string {
  return createHmac("sha256", key).update(payload(receipt)).digest("hex");
}
export function structuralFindings(receipt: any, expected: {
  runId?: string; contractSha256: string; harnessSha256: string;
  referenceSha256: string; candidateJarSha256: string; candidateNativeHostSha256: string;
  now?: number; maxAgeMs?: number;
}, key: Uint8Array): string[] {
  const findings: string[] = [];
  if (!receipt || receipt.schema !== 1 || typeof receipt.runId !== "string" || !/^[a-f0-9]{32}$/.test(receipt.runId)) return ["malformed qualification receipt"];
  if (expected.runId && receipt.runId !== expected.runId) findings.push("run identity mismatch");
  if (receipt.contractSha256 !== expected.contractSha256) findings.push("contract hash mismatch");
  if (receipt.harnessSha256 !== expected.harnessSha256) findings.push("harness hash mismatch");
  if (receipt.reference?.sha256 !== expected.referenceSha256) findings.push("reference executable hash mismatch");
  if (receipt.candidateJar?.sha256 !== expected.candidateJarSha256) findings.push("candidate jar hash mismatch");
  if (receipt.candidateNativeHost?.sha256 !== expected.candidateNativeHostSha256) findings.push("candidate native host hash mismatch");
  if (receipt.referenceSourceLinkage !== "unproven") findings.push("binary-to-source linkage is not established by this harness");
  const start = Date.parse(receipt.startedAt), finish = Date.parse(receipt.finishedAt), now = expected.now ?? Date.now();
  if (!Number.isFinite(start) || !Number.isFinite(finish) || finish < start || finish > now + 1000 || start < now - (expected.maxAgeMs ?? 86_400_000)) findings.push("stale or malformed evidence time");
  const cases: any[] = Array.isArray(receipt.cases) ? receipt.cases : [];
  const ids = cases.map(item => item?.id);
  if (ids.length !== requiredCaseIds.length || new Set(ids).size !== ids.length || requiredCaseIds.some(id => !ids.includes(id))) findings.push("missing, duplicate, or unknown case inventory");
  for (const item of cases) {
    if (!requiredCaseIds.includes(item?.id)) { findings.push(`unknown case ${String(item?.id)}`); continue; }
    if (item.runId !== receipt.runId) findings.push(`${item.id}: stale run evidence`);
    if (item.state !== "executed" || !Array.isArray(item.findings) || item.findings.length) findings.push(`${item.id}: not executed successfully`);
    if (!verifiers[item.id as CaseId] || item.verifier !== verifiers[item.id as CaseId]) findings.push(`${item.id}: no executable verifier`);
    if (!Array.isArray(item.artifacts) || !item.artifacts.length || item.artifacts.some((entry: any) =>
      typeof entry?.path !== "string" || !/^[a-f0-9]{64}$/.test(entry?.sha256) || !Number.isSafeInteger(entry?.bytes) || entry.bytes < 1)) findings.push(`${item.id}: missing or malformed raw artifact`);
    if ("pass" in item || "status" in item || "skipped" in item) findings.push(`${item.id}: manually asserted result fields are forbidden`);
  }
  const installed = receipt.installed;
  if (!installed || !Number.isSafeInteger(installed.pid) || !Number.isSafeInteger(installed.hostPid) ||
    typeof installed.jarFd !== "string" || !new RegExp(`^/proc/${installed.pid}/fd/[0-9]+$`).test(installed.jarFd) ||
    installed.jarSha256 !== expected.candidateJarSha256 || installed.hostExeSha256 !== expected.candidateNativeHostSha256) findings.push("installed open jar/native executable identity is missing or mismatched");
  const expectedSeal = sealReceipt(receipt, key);
  if (typeof receipt.seal !== "string" || !/^[a-f0-9]{64}$/.test(receipt.seal) || !timingSafeEqual(Buffer.from(receipt.seal, "hex"), Buffer.from(expectedSeal, "hex"))) findings.push("unsealed or modified receipt; asserted PASS is not execution evidence");
  if (receipt.qualified !== true) findings.push("runner did not qualify this candidate");
  return findings;
}
export async function localSealKey(path: string, create: boolean): Promise<Uint8Array> {
  try {
    const key = await readFile(path);
    if (key.length !== 32 || ((await stat(path)).mode & 0o077) !== 0) throw new Error("qualification key must be 32 bytes with mode 0600");
    return key;
  } catch (error: any) {
    if (!create || error.code !== "ENOENT") throw error;
    await mkdir(dirname(path), { recursive: true });
    const key = randomBytes(32);
    await writeFile(path, key, { mode: 0o600, flag: "wx" });
    return key;
  }
}
