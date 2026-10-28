import { readFile, writeFile } from "node:fs/promises";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { gunzipSync } from "node:zlib";
import type { CurrentInventory, SourceUnit } from "./current-inventory";
import { sha256, type Artifact } from "./qualification";
import { verifyLinuxSigstore, verifyActionsArtifact } from "./current-native-proof";
import { unitScopeSha256, caseWitnessArtifacts, type NamedWitness, type ExecutedCaseWitness } from "./current-witness";
import { plannedWitnessLookup } from "./current-plans";
import type { CliControlCase } from "./current-cli-proof";
import { declarationContextReason } from "./current-declaration-context";

export const currentVerifierIds = [
  "current.native-protocol", "current.tools-and-description", "current.output-and-media", "current.gateway-history", "current.interactive-delivery", "current.packaged-identities", "current.rust-client", "current.core-gateway",
] as const;
export type CurrentVerifierId = typeof currentVerifierIds[number];
export type Disposition = { id: string; sha256: string; scopeSha256: string; disposition: "native-reuse" | "verify" | "context" | "unsupported";
  verifiers: CurrentVerifierId[]; witnesses: NamedWitness[]; reason: string };
export type Dispositions = { schema: 3; sourceRevision: string; denominatorSha256: string; units: Disposition[] };
export type ExecutedVerifier = {
  id: CurrentVerifierId; runId: string; startedAt: string; finishedAt: string;
  inventorySha256: string; sourceRevision: string; candidateJarSha256: string; nativeHostSha256: string;
  state: "executed" | "failed"; findings: string[]; artifacts: Artifact[];
  cases?: ExecutedCaseWitness[];
  cliCases?: CliControlCase[];
  known_soft_spots?: string[];
};
export type NativeProvenance = {
  schema: 1 | 2; release: string; tag: string; sourceRevision: string;
  known_soft_spots?: string[];
  artifacts: Array<{
    platform: string; release?: string; sourceRevision: string; archive?: string; archiveSha256: string;
    executable?: string; sha256: string; origin: string; assetId?: number;
    packagedGzip?: Artifact; packagedManifest?: Artifact;
    sourceLinkage: { state: "verified"; method: "sigstore-attestation" | "official-actions-artifact";
      sourceRevision: string; executableSha256: string; evidence: Artifact[];
      workflowRunId?: number; artifactId?: number; artifactDigest?: string; archiveMembers?: string[] };
  }>;
};
/** These are implementation context, not supported Splice configuration or claimed Kotlin ports. */
const contextReview: { schema: 1; sourceRevision: string; units: Array<{ id: string; sha256: string; reason: string; evidence: Array<{ path: string; sha256: string; line: number; predicate: string }> }> } = JSON.parse(readFileSync(resolve(import.meta.dir, "current-context.json"), "utf8"));
export function contextReason(unit: SourceUnit, inventory?: CurrentInventory): string | undefined {
  if (["source-file", "impl_item", "mod_item"].includes(unit.kind)) return "AST accounting container: its exact source hash guards scope and anonymous content. It receives no coverage PASS; behavioral declarations inside the container require their own execution witness or native ownership proof.";
  const declaration = inventory && declarationContextReason(unit, inventory);
  if (declaration) return declaration;
  const representation = unit.representation;
  if (representation?.algorithm === "private-nonwire-layout-v1" && inventory && representation.methods.length &&
    inventory.units.some(owner => owner.id === representation.owner.id && owner.sha256 === representation.owner.sha256) &&
    representation.methods.every(method => inventory.units.some(unit => unit.kind === "function_item" && unit.id === method.id && unit.sha256 === method.sha256))) return `${representation.predicate} Owner: ${representation.owner.id}; exact selected methods: ${representation.methods.map(method => method.id).join(", ")}.`;
  if (unit.foreignScope?.state === "foreign-context" && unit.foreignScope.algorithm === "ast-import-member-closure-v1" && /^[a-f0-9]{64}$/.test(unit.foreignScope.selectorsSha256) && !unit.foreignScope.incoming.length) return `Foreign declaration outside the selected CodeMode AST import/member/helper closure. Importing a sibling declaration does not make this independent declaration a CodeMode API. Exact selector and complete file hashes remain drift guards: ${unit.foreignScope.selectorsSha256}. This context receives no coverage PASS.`;
  const reviewed = contextReview.units.find(item => item.id === unit.id && item.sha256 === unit.sha256);
  if (reviewed && inventory && contextReview.sourceRevision === inventory.sourceRevision && reviewed.evidence.length && reviewed.evidence.every(proof => inventory.files.some(file => file.path === proof.path && file.sha256 === proof.sha256) && proof.predicate.length > 20)) return reviewed.reason;
  return undefined;
}
export function nativeOwnedClosure(unit: SourceUnit): boolean {
  if (unit.ownership !== "native-host") return false;
  return /^codex-rs\/(?:code-mode-host|code-mode-runtime)\//.test(unit.file) ||
    /^codex-rs\/code-mode-protocol\/build\.rs$/.test(unit.file) ||
    /^codex-rs\/code-mode-protocol\/src\/grpc\/.*\.proto$/.test(unit.file) ||
    unit.nativeScope?.state === "required" && (/^codex-rs\/code-mode-protocol\//.test(unit.file) || !!unit.requiredBy?.length && unit.requiredBy.every(path => /^codex-rs\/(?:code-mode-host|code-mode-runtime)\//.test(path)));
}
/** Regeneration carries only already reviewed, byte-identical obligations. It never assigns a feature group. */
export function generateDispositions(inventory: CurrentInventory, reviewed?: Dispositions): Dispositions {
  const prior = new Map(reviewed?.schema === 3 && reviewed.sourceRevision === inventory.sourceRevision ? reviewed.units.map(item => [item.id, item]) : []);
  return { schema: 3, sourceRevision: inventory.sourceRevision, denominatorSha256: inventory.denominatorSha256, units: inventory.units.map(unit => {
    const item = prior.get(unit.id), scopeSha256 = unitScopeSha256(unit, inventory);
    if (item?.sha256 === unit.sha256 && item.scopeSha256 === scopeSha256) return structuredClone(item);
    return { id: unit.id, sha256: unit.sha256, scopeSha256, disposition: "unsupported", verifiers: [], witnesses: [],
      reason: "Unreviewed new/changed source obligation. Add an explicit source-hash-bound named execution witness or justified exact source scope predicate; a path-family verifier cannot grant coverage." };
  }) };
}
export function dispositionFindings(inventory: CurrentInventory, dispositions: Dispositions): string[] {
  const findings: string[] = [];
  const planned = plannedWitnessLookup();
  if (dispositions?.schema !== 3 || dispositions.sourceRevision !== inventory.sourceRevision || dispositions.denominatorSha256 !== inventory.denominatorSha256) findings.push("dispositions are not bound to the current generated reference denominator and explicit source witness schema");
  if (!Array.isArray(dispositions?.units)) return [...findings, "missing current reference dispositions"];
  const expected = new Map(inventory.units.map(unit => [unit.id, unit])), seen = new Set<string>();
  for (const item of dispositions.units) {
    const unit = expected.get(item?.id);
    if (!unit) { findings.push(`unknown disposition unit: ${String(item?.id)}`); continue; }
    if (seen.has(item.id)) findings.push(`duplicate disposition unit: ${item.id}`); seen.add(item.id);
    if (item.sha256 !== unit.sha256 || item.scopeSha256 !== unitScopeSha256(unit, inventory)) findings.push(`source unit bytes/ownership/scope changed without a reviewed witness: ${item.id}`);
    if (!["native-reuse", "verify", "context"].includes(item.disposition)) findings.push(`unsupported disposition: ${item.id}`);
    if (typeof item.reason !== "string" || item.reason.trim().length < 20) findings.push(`missing disposition rationale: ${item.id}`);
    if (!Array.isArray(item.verifiers) || item.verifiers.some(id => !currentVerifierIds.includes(id))) findings.push(`unknown executable verifier: ${item.id}`);
    if (item.disposition === "native-reuse" && !nativeOwnedClosure(unit)) findings.push(`ported source cannot claim native binary reuse outside the actual host-owned closure: ${item.id}`);
    if (item.disposition === "context" && (!contextReason(unit, inventory) || item.reason !== contextReason(unit, inventory))) findings.push(`in-scope behavior cannot be removed as implementation context: ${item.id}`);
    if (!Array.isArray(item.witnesses)) findings.push(`missing explicit named source witnesses: ${item.id}`);
    if (item.disposition === "verify") {
      const observableMember = ["const_item", "struct_item", "enum_item", "enum_variant", "field_declaration", "ordered_field_declaration_list"].includes(unit.kind) || unit.kind.startsWith("typescript-");
      if (item.verifiers.length !== 1 || !["current.tools-and-description", "current.rust-client", "current.core-gateway"].includes(item.verifiers[0]!) || !item.witnesses?.length ||
        unit.kind !== "function_item" && (!observableMember || item.verifiers[0] !== "current.tools-and-description")) findings.push(`uncovered ported source unit; no supported per-unit invocation/value witness: ${item.id}`);
      for (const witness of item.witnesses ?? []) {
        const source = /^core\/[a-z_]+\/[a-f0-9]{20}$/.test(witness.caseId) && /^(?:splice\.upstream\.codemode\.diagnostics\.CodeModeCoreProbe|splice\.provider\.codex\.diagnostics\.CodeMode(?:Arguments|Catalog)Probe|splice\.models\.discovery\.ModelMetadataProbe)\.evaluate$/.test(witness.productionEntrypoint) && item.verifiers[0] === "current.tools-and-description";
        const client = /^rust-client(?:-fault)?\/(?:[a-z0-9-]+\/)?[a-z0-9-]+$/.test(witness.caseId) && /^(?:splice\.codemode\.nativehost\.NativeConnection\.operation|splice\.codemode\.NativeCodeModeRuntime\.(?:startNativeSession|controlNativeSession|openNativeSession))$/.test(witness.productionEntrypoint) && item.verifiers[0] === "current.rust-client";
        const gateway = /^cli-source\/[a-z0-9-]+$/.test(witness.caseId) && witness.productionEntrypoint === "splice.provider.codex.diagnostics.current.CodeModeGatewayScenarioProbe.run" && item.verifiers[0] === "current.core-gateway";
        if (witness.referenceEntrypoint !== item.id || !/^[a-f0-9]{64}$/.test(witness.inputSha256) || (!source && !client && !gateway)) findings.push(`misassigned or unknown named source/case witness: ${item.id}`);
        if (!planned(witness)) findings.push(`named source witness has no matching mandatory executable case/input/entrypoint: ${item.id}: ${witness.caseId}`);
      }
    } else if (item.witnesses?.length) findings.push(`non-executable source context/native ownership cannot claim a case coverage PASS: ${item.id}`);
    if (item.disposition === "context" && item.verifiers?.length) findings.push(`implementation context cannot receive coverage PASS: ${item.id}`);
    if (item.disposition === "native-reuse" && JSON.stringify(item.verifiers) !== JSON.stringify(["current.packaged-identities", "current.native-protocol"])) findings.push(`native reuse lacks mandatory actual identity/protocol executions: ${item.id}`);
  }
  for (const id of expected.keys()) if (!seen.has(id)) findings.push(`missing disposition unit: ${id}`);
  const reviewedUnits = new Map(dispositions.units.map(unit => [unit.id, unit]));
  for (const issue of inventory.foreignScopeDiagnostics ?? []) {
    const from = expected.get(issue.from), reviewed = reviewedUnits.get(issue.from);
    // A proven excluded implementation and a reused host binary have their own
    // complete source predicates. An entered ported function cannot hide an
    // unresolved imported helper behind its own positive entry counter.
    const excluded = from && reviewed?.disposition === "context" && reviewed.reason === contextReason(from, inventory);
    const reused = from && reviewed?.disposition === "native-reuse" && nativeOwnedClosure(from);
    if (!excluded && !reused) findings.push(`unresolved ported CodeMode project reference: ${issue.from}: ${issue.expression} -> ${issue.paths.join(", ")}`);
  }
  return findings;
}

export function executionFindings(inventory: CurrentInventory, dispositions: Dispositions, executed: ExecutedVerifier[], expected: {
  runId: string; candidateJarSha256: string; nativeHostSha256: string; now?: number;
}): string[] {
  const findings: string[] = [], seen = new Set<string>();
  for (const entry of executed ?? []) {
    if (!currentVerifierIds.includes(entry?.id) || seen.has(entry.id)) { findings.push(`unknown or duplicate current execution: ${String(entry?.id)}`); continue; }
    seen.add(entry.id);
    if (entry.runId !== expected.runId || entry.inventorySha256 !== inventory.denominatorSha256 || entry.sourceRevision !== inventory.sourceRevision ||
      entry.candidateJarSha256 !== expected.candidateJarSha256 || entry.nativeHostSha256 !== expected.nativeHostSha256) findings.push(`${entry.id}: execution identity is stale or mismatched`);
    if (entry.state !== "executed" || !Array.isArray(entry.findings) || entry.findings.length) findings.push(`${entry.id}: production verifier did not execute successfully`);
    const start = Date.parse(entry.startedAt), finish = Date.parse(entry.finishedAt), now = expected.now ?? Date.now();
    if (!Number.isFinite(start) || !Number.isFinite(finish) || finish < start || finish > now + 1000 || start < now - 86_400_000) findings.push(`${entry.id}: execution time is stale or malformed`);
    if (!Array.isArray(entry.artifacts) || !entry.artifacts.length || entry.artifacts.some(item => typeof item.path !== "string" || !/^[a-f0-9]{64}$/.test(item.sha256) || !Number.isSafeInteger(item.bytes) || item.bytes < 0)) findings.push(`${entry.id}: raw executed evidence is absent`);
    if (["pass", "status", "skipped", "sourceInspected"].some(key => key in entry)) findings.push(`${entry.id}: manually asserted or source-only evidence is forbidden`);
    for (const case_ of entry.cases ?? []) {
      const values = case_.producer === "rust-client-llvm" || case_.producer === "core-cli-llvm" ? [] : case_.referenceValues ?? [];
      if ((case_.producer === "core-cli-llvm" ? entry.id !== "current.core-gateway" : case_.producer === "rust-client-llvm" ? entry.id !== "current.rust-client" : entry.id !== "current.tools-and-description") || !Array.isArray(case_.referenceFunctions) ||
        !case_.referenceFunctions.length && !values.length) findings.push(`${entry.id}: unknown/unlinked named case producer: ${case_.caseId}`);
      for (const proof of caseWitnessArtifacts(case_)) if (!proof || !entry.artifacts.some(artifact => artifact.path === proof.path && artifact.sha256 === proof.sha256 && artifact.bytes === proof.bytes)) findings.push(`${entry.id}: named case evidence is not bound to the actual verifier artifacts: ${case_.caseId}`);
    }
  }
  for (const id of currentVerifierIds) if (!seen.has(id)) findings.push(`current executable verifier has no current run evidence: ${id}`);
  for (const item of dispositions.units) for (const id of item.verifiers ?? []) if (!seen.has(id)) findings.push(`${item.id}: mapped verifier was not executed: ${id}`);
  const cases = executed.flatMap(entry => entry.cases ?? []);
  for (const item of dispositions.units.filter(item => item.disposition === "verify")) for (const required of item.witnesses ?? []) {
    const found = cases.find(witness => witness.caseId === required.caseId && witness.inputSha256 === required.inputSha256 && witness.productionEntrypoint === required.productionEntrypoint);
    const function_ = inventory.units.find(unit => unit.id === item.id)?.kind === "function_item";
    const observed = found && (function_ ? found.referenceFunctions : found.producer === "rust-client-llvm" || found.producer === "core-cli-llvm" ? [] : found.referenceValues ?? []).some(unit => unit.id === item.id && unit.sha256 === item.sha256);
    if (!observed) findings.push(`${item.id}: named case did not ${function_ ? "invoke" : "observe"} this exact source unit and production entrypoint: ${required.caseId}`);
  }
  return [...new Set(findings)];
}

export async function nativeProvenanceFindings(provenance: NativeProvenance | undefined, expected: { release: string; tag: string; sourceRevision: string; nativeHostSha256: string }): Promise<string[]> {
  const findings: string[] = [];
  if (!provenance || ![1, 2].includes(provenance.schema) || provenance.release !== expected.release || provenance.tag !== expected.tag || provenance.sourceRevision !== expected.sourceRevision || !Array.isArray(provenance.artifacts)) return ["current native source-to-binary provenance is unverified or mismatched"];
  const platforms = ["linux-amd64", "linux-aarch64", "darwin-aarch64", "windows-amd64"];
  const names = provenance.artifacts.map(item => item.platform);
  if (names.length !== platforms.length || new Set(names).size !== names.length || platforms.some(platform => !names.includes(platform))) findings.push("native provenance must bind every packaged platform exactly once");
  for (const item of provenance.artifacts) {
    const prefix = `native provenance ${item.platform}`;
    let packagedBytes: Buffer | undefined;
    if (item.sourceRevision !== expected.sourceRevision || !item.origin?.startsWith("https://github.com/openai/codex/releases/")) findings.push(`${prefix}: official source/release origin is missing`);
    if (provenance.schema === 2) {
      for (const [entry, label] of [[item.packagedGzip, "packaged gzip"], [item.packagedManifest, "packaged manifest"]] as const) {
        try {
          if (!entry) throw new Error("missing packaged payload proof");
          const bytes = await readFile(resolve(import.meta.dir, "../..", entry.path));
          if (bytes.length !== entry.bytes || sha256(bytes) !== entry.sha256) findings.push(`${prefix}: ${label} bytes changed`);
          if (label === "packaged gzip") {
            packagedBytes = gunzipSync(bytes);
            if (sha256(packagedBytes) !== item.sha256) findings.push(`${prefix}: packaged native executable identity changed`);
          }
          if (label === "packaged manifest") {
            const properties = new Map(bytes.toString().split(/\r?\n/).filter(line => line.includes("=")).map(line => [line.slice(0, line.indexOf("=")), line.slice(line.indexOf("=") + 1)]));
            if (properties.get("sourceRevision") !== expected.sourceRevision || properties.get("sha256") !== item.sha256 || properties.get("release") !== expected.release) findings.push(`${prefix}: packaged source manifest does not match verified reference`);
          }
        } catch { findings.push(`${prefix}: ${label} unavailable; restore the repository native payloads`); }
      }
    } else for (const [path, digest, label] of [[item.archive, item.archiveSha256, "archive"], [item.executable, item.sha256, "executable"]] as const) {
      try { if (!path || !/^[a-f0-9]{64}$/.test(digest) || sha256(await readFile(path)) !== digest) findings.push(`${prefix}: ${label} bytes changed`); }
      catch { findings.push(`${prefix}: ${label} is unavailable`); }
    }
    const linkage = item.sourceLinkage;
    if (!linkage || linkage.state !== "verified" || !["sigstore-attestation", "official-actions-artifact"].includes(linkage.method) || linkage.sourceRevision !== expected.sourceRevision || linkage.executableSha256 !== item.sha256 || !Array.isArray(linkage.evidence) || !linkage.evidence.length) { findings.push(`${prefix}: actual verified source linkage evidence is absent`); continue; }
    let release: any, bundlePath: string | undefined;
    for (const proof of linkage.evidence) {
      try {
        const bytes = await readFile(resolve(import.meta.dir, "../..", proof.path));
        if (bytes.length !== proof.bytes || sha256(bytes) !== proof.sha256) findings.push(`${prefix}: source linkage raw evidence changed`);
        if (proof.path.endsWith("/release.json")) release = JSON.parse(bytes.toString());
        if (proof.path.endsWith(".sigstore")) bundlePath = resolve(import.meta.dir, "../..", proof.path);
      } catch { findings.push(`${prefix}: source linkage raw evidence is unavailable`); }
    }
    if (provenance.schema === 2) {
      const asset = release?.assets?.find((asset: any) => asset.id === item.assetId);
      if (!asset || asset.browser_download_url !== item.origin || asset.digest !== `sha256:${item.archiveSha256}`) findings.push(`${prefix}: original official release metadata does not bind the archive identity`);
      try {
        if (!packagedBytes || sha256(packagedBytes) !== item.sha256) throw new Error("packaged executable bytes are unavailable or mismatched");
        if (item.platform.startsWith("linux-")) {
          if (linkage.method !== "sigstore-attestation" || !bundlePath) throw new Error("actual Sigstore source/signature bundle is missing");
          await verifyLinuxSigstore(packagedBytes, bundlePath, expected);
        } else {
          if (linkage.method !== "official-actions-artifact" || !linkage.workflowRunId || !linkage.artifactId || !linkage.artifactDigest || !linkage.archiveMembers) throw new Error("actual official Actions source/archive/extraction proof is missing");
          await verifyActionsArtifact(packagedBytes, { workflowRunId: linkage.workflowRunId, artifactId: linkage.artifactId, artifactDigest: linkage.artifactDigest, members: linkage.archiveMembers }, expected.sourceRevision);
        }
      } catch (error) { findings.push(`${prefix}: ${error instanceof Error ? error.message : String(error)}`); }
    } else findings.push(`${prefix}: current provenance requires tracked packaged bytes and fresh independent verification (schema 2)`);
  }
  if (provenance.artifacts.find(item => item.platform === "linux-amd64")?.sha256 !== expected.nativeHostSha256) findings.push("current Linux host does not match source-verified native artifact");
  return findings;
}

if (import.meta.main) {
  const inventory: CurrentInventory = JSON.parse(await readFile(resolve(import.meta.dir, "current-inventory.json"), "utf8"));
  const path = resolve(import.meta.dir, "current-dispositions.json");
  let reviewed: Dispositions | undefined;
  try { reviewed = JSON.parse(await readFile(path, "utf8")); } catch { /* all new obligations remain unsupported */ }
  const dispositions = generateDispositions(inventory, reviewed);
  await writeFile(resolve(import.meta.dir, "current-dispositions.json"), JSON.stringify(dispositions, null, 2) + "\n");
  console.log(`Generated ${dispositions.units.length} source dispositions; ${dispositions.units.filter(item => item.disposition === "unsupported").length} unsupported`);
}
