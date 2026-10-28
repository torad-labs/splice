import assert from "node:assert/strict";
import { readFile, rm } from "node:fs/promises";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { gunzipSync } from "node:zlib";
import { sha256, type Artifact } from "./qualification";
import type { CurrentInventory } from "./current-inventory";
import { capturedCommand } from "./current-command";
import { coreCaseIdentity, parseLosslessJson, assertObservableJsonKeyOrder, type FunctionWitness } from "./current-witness";
import type { CoreValueWitness } from "./current-core-values";
import { llvmSourceEntries, type ClientProfileProof } from "./current-client-profile";
import { apiMemberValues } from "./current-api-values";

type Path = Array<string | number>;
export type ApiJob = {
  id: string; reference_input: Record<string, any>; candidate_input: Record<string, any>;
  entrypoint?: string;
  reference_path: Path; candidate_path: Path;
  observations?: Array<{ unit_id: string; unit_sha256: string; reference_path: Path; candidate_path: Path }>;
};
export type ApiExtraction = { kind: "complete-file" | "complete-function" | "statement-context"; sourceFile: string; sourceStart: number; sourceEnd: number; sourceSha256: string; sourceFileSha256: string; localFile: string; localStart: number; original: Artifact; local: Artifact };
export type ApiBuildProof = {
  schema: 1; sourceRevision: string; sourceTree: string; sourceDirectory: string; sourceRepository: string;
  compiler: string; flags: string[]; binary: Artifact; driverSources: Artifact[]; cargoToml: Artifact; cargoLock: Artifact;
  command: Artifact; candidateJar: Artifact; cleanBefore: Artifact; cleanAfter: Artifact; sourceIdentity: Artifact;
  tools: { cov: Artifact; profdata: Artifact }; extractions: ApiExtraction[];
};
export type ApiCaseWitness = {
  producer: "core-api-llvm"; caseId: string; inputSha256: string; productionEntrypoint: string;
  referenceFunctions: FunctionWitness[]; referenceValues: CoreValueWitness[];
  input: Artifact; reference: Artifact; candidate: Artifact; build: Artifact; profile: Artifact;
};
export const apiRustFlags = ["-Cinstrument-coverage", "-Cdebuginfo=1", "-Copt-level=0", "-Cdebug-assertions=no", "-Coverflow-checks=no"];
export function apiJobs(): ApiJob[] {
  const text = readFileSync(resolve(import.meta.dir, "current-api-reference/jobs.jsonl"), "utf8");
  const jobs: ApiJob[] = text.trimEnd().split("\n").map(parseLosslessJson), seen = new Set<string>();
  assert.ok(jobs.length > 0, "mandatory actual-crate API jobs are absent");
  for (const job of jobs) {
    assert.ok(typeof job.id === "string" && !seen.has(job.id), "unknown or duplicate actual-crate API job"); seen.add(job.id);
    assert.ok(job.reference_input?.operation && job.candidate_input?.operation && Array.isArray(job.reference_path) && Array.isArray(job.candidate_path));
    if (job.entrypoint) assert.equal(job.entrypoint, coreCaseIdentity(job.candidate_input).productionEntrypoint, "API job names an unused production entrypoint");
    const path = (components: Path) => components.map((component: any) => {
      if (typeof component === "string" || typeof component === "number") return component;
      assert.ok((JSON as typeof JSON & { isRawJSON(value: unknown): boolean }).isRawJSON(component) && /^(0|[1-9]\d*)$/.test(component.rawJSON), "API projection index is not an unsigned array index");
      const value = Number(component.rawJSON); assert.ok(Number.isSafeInteger(value)); return value;
    });
    job.reference_path = path(job.reference_path); job.candidate_path = path(job.candidate_path);
    for (const observation of job.observations ?? []) { observation.reference_path = path(observation.reference_path); observation.candidate_path = path(observation.candidate_path); }
  }
  return jobs;
}
export function apiBatches(jobs = apiJobs()): Array<{ caseId: string; inputSha256: string; productionEntrypoint: string; jobs: ApiJob[] }> {
  const groups = new Map<string, ApiJob[]>();
  for (const job of jobs) {
    const entry = coreCaseIdentity(job.candidate_input).productionEntrypoint;
    groups.set(entry, [...groups.get(entry) ?? [], job]);
  }
  return [...groups].sort(([a], [b]) => a.localeCompare(b)).map(([productionEntrypoint, jobs]) => {
    const name = productionEntrypoint.includes("Arguments") ? "arguments" : productionEntrypoint.includes("Catalog") ? "catalog" : productionEntrypoint.includes("Metadata") ? "metadata" : "core";
    const inputSha256 = sha256(JSON.stringify(jobs));
    return { caseId: `core/api_${name}/${inputSha256.slice(0, 20)}`, inputSha256, productionEntrypoint, jobs };
  });
}
export function apiValue(value: any, path: Path): any {
  for (const component of path) {
    assert.ok(value !== null && typeof value === "object" && Object.hasOwn(value, component), `actual API observation omitted path ${JSON.stringify(path)}`);
    value = value[component];
  }
  return value;
}
export function compareApiRows(jobs: ApiJob[], expected: any[], actual: any[], runId: string): void {
  assert.equal(expected.length, jobs.length, "actual source omitted an API job"); assert.equal(actual.length, jobs.length, "candidate omitted an API job");
  for (const [index, job] of jobs.entries()) {
    assert.equal(expected[index]?.id, job.id); assert.equal(actual[index]?.id, job.id); assert.equal(actual[index]?.nonce, runId);
    const source = apiValue(expected[index].result, job.reference_path), candidate = apiValue(actual[index].result, job.candidate_path);
    assert.deepEqual(candidate, source, `actual-crate API projection differs: ${job.id}`);
    assertObservableJsonKeyOrder(candidate, source);
  }
}
export const observedApiValues = apiMemberValues;
function offset(bytes: Buffer, line: number, column: number): number {
  let at = 0, current = 1; while (current < line && at < bytes.length) if (bytes[at++] === 10) current++;
  assert.equal(current, line); return at + column - 1;
}
export async function apiSourceEntries(exported: any, inventory: CurrentInventory, build: Pick<ApiBuildProof, "sourceDirectory" | "sourceRepository" | "extractions">): ReturnType<typeof llvmSourceEntries> {
  const result = await llvmSourceEntries(exported, inventory, build.sourceDirectory, build.sourceRepository);
  for (const extraction of build.extractions) {
    const local = await readFile(extraction.localFile), original = await readFile(extraction.original.path);
    assert.equal(sha256(local), extraction.local.sha256); assert.equal(sha256(original), extraction.sourceSha256);
    assert.equal(original.length, extraction.sourceEnd - extraction.sourceStart);
    assert.ok(local.subarray(extraction.localStart, extraction.localStart + original.length).equals(original), "private source function was modified in the actual-crate wrapper");
    assert.ok(inventory.files.some(file => file.path === extraction.sourceFile && file.sha256 === extraction.sourceFileSha256), "private source file identity changed");
    const git = await capturedCommand(["git", "-C", build.sourceRepository, "show", `${inventory.sourceRevision}:${extraction.sourceFile}`], import.meta.dir); assert.equal(git.exitCode, 0);
    const pinned = Buffer.from(git.stdout); assert.equal(sha256(pinned), extraction.sourceFileSha256); assert.ok(pinned.subarray(extraction.sourceStart, extraction.sourceEnd).equals(original));
    if (extraction.kind === "statement-context") continue;
    assert.ok(extraction.kind === "complete-file" && extraction.sourceStart === 0 && extraction.sourceSha256 === extraction.sourceFileSha256 || extraction.kind === "complete-function" && inventory.units.some(unit => unit.kind === "function_item" && unit.file === extraction.sourceFile && unit.start === extraction.sourceStart && unit.end === extraction.sourceEnd && unit.sha256 === extraction.sourceSha256), "private extraction cannot grant an enclosing function entry from a statement fragment");
    for (const section of exported.data ?? []) for (const function_ of section.functions ?? []) {
      if (!(function_.count > 0)) continue;
      const entry = function_.regions.find((region: number[]) => region[7] === 0); if (!entry || resolve(function_.filenames[entry[5]] ?? "") !== resolve(extraction.localFile)) continue;
      const from = offset(local, entry[0], entry[1]), to = offset(local, entry[2], entry[3]);
      if (from < extraction.localStart || to > extraction.localStart + original.length) continue;
      const start = extraction.sourceStart + from - extraction.localStart, end = extraction.sourceStart + to - extraction.localStart;
      const unit = inventory.units.filter(unit => unit.file === extraction.sourceFile && unit.kind === "function_item" && unit.start <= start && end <= unit.end).sort((a, b) => a.end - a.start - (b.end - b.start))[0];
      assert.ok(unit, "private LLVM function entry does not name an authored source function");
      result.mappings.push({ id: unit.id, sha256: unit.sha256, function: function_.name, count: function_.count, entryRegion: entry, start, end, localFile: extraction.localFile });
      result.linked.push({ id: unit.id, sha256: unit.sha256 });
    }
  }
  result.linked = [...new Map(result.linked.map(unit => [unit.id, unit])).values()].sort((a, b) => a.id.localeCompare(b.id));
  return result;
}
async function checked(proof: Artifact): Promise<Buffer> { const bytes = await readFile(proof.path); assert.equal(bytes.length, proof.bytes); assert.equal(sha256(bytes), proof.sha256); return bytes; }
export async function apiWitnessFindings(cases: ApiCaseWitness[], inventory: CurrentInventory, runId: string, candidateJar: Pick<Artifact, "path" | "sha256">): Promise<string[]> {
  const findings: string[] = [], seen = new Set<string>(), plans = apiBatches();
  for (const witness of cases) try {
    assert.ok(!seen.has(witness.caseId), "duplicate actual-crate API batch"); seen.add(witness.caseId);
    const plan = plans.find(plan => plan.caseId === witness.caseId); assert.ok(plan, "unknown or weakened actual-crate API batch");
    assert.equal(witness.inputSha256, plan.inputSha256); assert.equal(witness.productionEntrypoint, plan.productionEntrypoint);
    assert.equal((await checked(witness.input)).toString(), JSON.stringify(plan.jobs) + "\n", "actual API job batch was changed or shrunk");
    const build = JSON.parse((await checked(witness.build)).toString()) as ApiBuildProof;
    assert.equal(build.schema, 1); assert.equal(build.sourceRevision, inventory.sourceRevision); assert.equal(build.sourceTree, inventory.sourceTree);
    assert.match(build.compiler, /commit-hash: 58cf8f9c35b355199727627d07ae34a4e2846d67\b/); assert.deepEqual(build.flags, apiRustFlags); assert.equal(build.candidateJar.path, candidateJar.path); assert.equal(build.candidateJar.sha256, candidateJar.sha256);
    for (const proof of [build.binary, build.cargoToml, build.cargoLock, build.command, build.cleanBefore, build.cleanAfter, build.sourceIdentity, build.tools.cov, build.tools.profdata, ...build.driverSources]) await checked(proof);
    assert.equal((await checked(build.cleanBefore)).length, 0); assert.equal((await checked(build.cleanAfter)).length, 0);
    assert.equal((await checked(build.sourceIdentity)).toString().trim(), `${inventory.sourceRevision}\n${inventory.sourceTree}`);
    const reference = JSON.parse((await checked(witness.reference)).toString()), candidate = JSON.parse((await checked(witness.candidate)).toString());
    for (const command of [reference, candidate]) { assert.equal(command.exitCode, 0); assert.ok(Number.isSafeInteger(command.pid) && command.pid > 0); }
    assert.equal(reference.argv[0], build.binary.path); assert.ok(candidate.argv.includes("--current-core-fixture") && candidate.argv.at(-1) === runId && candidate.argv.includes(candidateJar.path));
    const stdin = (side: "reference_input" | "candidate_input") => plan.jobs.map(job => JSON.stringify({ ...job[side], id: job.id })).join("\n") + "\n";
    for (const [command, side] of [[reference, "reference_input"], [candidate, "candidate_input"]] as const) { const input = stdin(side); assert.deepEqual(command.stdin, { sha256: sha256(input), bytes: Buffer.byteLength(input) }); }
    const expected = reference.stdout.trimEnd().split("\n").map(parseLosslessJson), actual = candidate.stdout.trimEnd().split("\n").map(parseLosslessJson);
    compareApiRows(plan.jobs, expected, actual, runId);
    assert.deepEqual(witness.referenceValues, await observedApiValues(plan.jobs, expected, actual, inventory, build));
    const profile = JSON.parse((await checked(witness.profile)).toString()) as ClientProfileProof;
    for (const proof of [profile.profraw, profile.profdata, profile.mappings]) await checked(proof);
    const saved = gunzipSync(await checked(profile.exported)); assert.equal(saved.length, profile.exported.decodedBytes); assert.equal(sha256(saved), profile.exported.decodedSha256);
    const merged = `${profile.profdata.path}.verify-${crypto.randomUUID()}`;
    const merge = await capturedCommand([build.tools.profdata.path, "merge", "-sparse", profile.profraw.path, "-o", merged], import.meta.dir); assert.equal(merge.exitCode, 0, merge.stderr);
    try {
      const exported = await capturedCommand([build.tools.cov.path, "export", build.binary.path, `-instr-profile=${merged}`, "-skip-expansions"], import.meta.dir, undefined, 180_000); assert.equal(exported.exitCode, 0, exported.stderr);
      const derived = await apiSourceEntries(JSON.parse(exported.stdout), inventory, build), stored = await apiSourceEntries(JSON.parse(saved.toString()), inventory, build);
      assert.deepEqual(stored, derived, "actual-crate source counters disagree with executable/profile"); assert.deepEqual(witness.referenceFunctions, derived.linked);
      assert.deepEqual(JSON.parse((await checked(profile.mappings)).toString()),derived,"actual-crate source mapping audit differs from the reproduced LLVM mappings");
    } finally { await rm(merged, { force: true }); }
  } catch (error) { findings.push(`${witness.caseId}: invalid actual-crate API witness: ${error instanceof Error ? error.message : String(error)}`); }
  for (const plan of plans) if (!seen.has(plan.caseId)) findings.push(`mandatory actual-crate API batch was omitted: ${plan.caseId}`);
  return findings;
}
