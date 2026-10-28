import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { stat } from "node:fs/promises";
import { isAbsolute, posix, relative } from "node:path";
import { StringDecoder } from "node:string_decoder";
import { compilerArray as array, compilerArtifact as artifact, compilerHash as hash, compilerObject as object, compilerString as string, compilerStrings as strings, freezeCompilerFacts, verifyCompilerArtifact } from "./current-compiler-evidence";
import type { CompilerDef } from "./current-compiler-format";
import type { CompilerArtifact, CompilerBindingIndex, CompilerDeclaration, CompilerExpectation, CompilerImmutableFile, CompilerNamespace, CompilerRange, CompilerReference, CompilerSourceIdentity } from "./current-compiler-types";

export type CompilerDriverProof = {
  schema: 1; format: "rustc-driver-v1"; source: CompilerSourceIdentity; sourceRoot: string; sourcePrefix: string;
  compiler: CompilerArtifact & { version: string }; originalCargoLock: CompilerArtifact; buildCargoLock: CompilerArtifact;
  driver: { binary: CompilerArtifact; source: CompilerArtifact }; components: CompilerArtifact[];
  crates: Array<{ crate: string; cwd: string; args: string[]; features: string[]; cfgs: string[]; externs: CompilerArtifact[]; inputs: CompilerArtifact[]; facts: CompilerArtifact }>;
};
type Identity = CompilerDef & { stableCrateId: string; kind: string };
const MAX_FACT_BYTES = 256 * 1024 * 1024;
function integer(value: unknown): number {
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) throw new Error("invalid compiler fact integer"); return value;
}
function identity(value: unknown, scope: string): Identity {
  const row = object(value), crate = string(row.crate), stableCrateId = string(row.stableCrateId), crateHash = string(row.crateHash), path = row.path;
  if (!/^[A-Za-z_][A-Za-z_0-9]*$/.test(crate) || !/^StableCrateId\(\d+\)$/.test(stableCrateId) || !/^[a-f0-9]{32}$/.test(crateHash) || typeof path !== "string") throw new Error("invalid full compiler definition identity");
  const index = integer(row.index), crateNumber = integer(row.crateNumber), kind = string(row.kind);
  // The full compiler identity and DefIndex identify a definition; rustc's display path may use a reexport.
  const key = `${scope}:${crate}:${stableCrateId}:${crateHash}:${index}`;
  return { key, raw: JSON.stringify(row), crate, crateHash, crateNumber, index, path, stableCrateId, kind };
}
function manifest(value: unknown, expected: CompilerExpectation): CompilerDriverProof {
  const row = object(value), source = object(row.source), compiler = object(row.compiler), driver = object(row.driver);
  if (row.schema !== 1 || row.format !== "rustc-driver-v1") throw new Error("unsupported compiler driver proof");
  const sourceIdentity = { revision: string(source.revision), tree: string(source.tree), originalCargoLockSha256: string(source.originalCargoLockSha256), buildCargoLockSha256: string(source.buildCargoLockSha256) };
  for (const key of ["revision", "tree", "originalCargoLockSha256", "buildCargoLockSha256"] as const) if (sourceIdentity[key] !== expected[key]) throw new Error("compiler source identity differs");
  const sourceRoot = string(row.sourceRoot), sourcePrefix = string(row.sourcePrefix);
  if (!isAbsolute(sourceRoot) || sourcePrefix.startsWith("/") || sourcePrefix.includes("..")) throw new Error("invalid compiler driver source location");
  const crates = array(row.crates).map(value => {
    const crate = object(value);
    return { crate: string(crate.crate), cwd: string(crate.cwd), args: strings(crate.args), cfgs: strings(crate.cfgs), features: strings(crate.features),
      externs: array(crate.externs).map(artifact), inputs: array(crate.inputs).map(artifact), facts: artifact(crate.facts) };
  });
  if (!crates.length || new Set(crates.map(crate => crate.crate)).size !== crates.length) throw new Error("compiler driver crate set is not unique");
  return { schema: 1, format: "rustc-driver-v1", source: sourceIdentity, sourceRoot, sourcePrefix, compiler: { ...artifact(compiler), version: string(compiler.version) },
    originalCargoLock: artifact(row.originalCargoLock), buildCargoLock: artifact(row.buildCargoLock), driver: { binary: artifact(driver.binary), source: artifact(driver.source) }, components: array(row.components).map(artifact), crates };
}
function argsMatch(crate: CompilerDriverProof["crates"][number]): void {
  const args = crate.args, at = args.indexOf("--crate-name");
  if (at < 0 || args[at + 1] !== crate.crate || !args.includes("--emit=metadata") || !args.includes("-Zno-steal-thir=yes") || args.some(arg => arg.startsWith("-Zunpretty"))) throw new Error("compiler driver argv differs");
  const cfgs = args.flatMap((arg, index) => arg === "--cfg" ? [args[index + 1]!] : arg.startsWith("--cfg=") ? [arg.slice(6)] : []);
  const features = cfgs.flatMap(cfg => /^feature="([^"]+)"$/.exec(cfg)?.[1] ? [/^feature="([^"]+)"$/.exec(cfg)![1]!] : []);
  const externs = args.flatMap((arg, index) => arg === "--extern" ? [args[index + 1]!.slice(args[index + 1]!.indexOf("=") + 1)] : []);
  if (JSON.stringify([...cfgs].sort()) !== JSON.stringify([...crate.cfgs].sort()) || JSON.stringify(features.sort()) !== JSON.stringify([...crate.features].sort())) throw new Error("compiler driver cfg/feature identity differs");
  if (JSON.stringify(externs.sort()) !== JSON.stringify(crate.externs.map(item => item.path).sort())) throw new Error("compiler driver extern argv differs");
}
async function* rows(file: CompilerArtifact): AsyncIterable<Record<string, unknown>> {
  if (file.bytes > MAX_FACT_BYTES || (await stat(file.path)).size !== file.bytes) throw new Error("compiler facts size differs or exceeds its bound");
  const digest = createHash("sha256"), decoder = new StringDecoder("utf8"); let bytes = 0, pending = "";
  for await (const chunk of createReadStream(file.path)) {
    const data = chunk as Buffer; bytes += data.length; digest.update(data); pending += decoder.write(data);
    if (bytes > file.bytes) throw new Error("compiler facts grew beyond their identity");
    let newline = pending.indexOf("\n");
    while (newline >= 0) {
      const line = pending.slice(0, newline); pending = pending.slice(newline + 1);
      if (!line || line.length > 1024 * 1024) throw new Error("invalid compiler fact line");
      yield object(JSON.parse(line)); newline = pending.indexOf("\n");
    }
    if (pending.length > 1024 * 1024) throw new Error("compiler fact line exceeds its bound");
  }
  pending += decoder.end();
  if (pending || bytes !== file.bytes || digest.digest("hex") !== file.sha256) throw new Error("compiler facts are incomplete or their hash differs");
}
function sourceRange(value: unknown, proof: CompilerDriverProof, files: ReadonlyMap<string, CompilerImmutableFile>, checked: Map<string, string>): CompilerRange | undefined {
  const span = object(value);
  if (span.state !== "source" || span.rootContext !== true) return;
  const inputPath = string(span.file);
  if (!isAbsolute(inputPath)) throw new Error("compiler source span is not absolute");
  const local = relative(proof.sourceRoot, inputPath);
  if (local.startsWith("../") || local.includes("\\") || isAbsolute(local)) throw new Error("compiler source span escapes its declared root");
  const file = posix.normalize(`${proof.sourcePrefix}/${local}`), input = files.get(file);
  if (!input) return;
  const compilerHash = string(span.compilerSourceHash), before = checked.get(file);
  if (before !== undefined && before !== compilerHash) throw new Error("compiler source hashes conflict");
  if (before === undefined) {
    const match = /^SourceFileHash \{ kind: (Md5|Sha1|Sha256), value: (\[[^\]]+\]) \}$/.exec(compilerHash);
    if (!match) throw new Error("unsupported compiler source hash");
    const values: unknown = JSON.parse(match[2]!);
    if (!Array.isArray(values) || values.length !== 32 || values.some(value => !Number.isInteger(value) || value < 0 || value > 255)) throw new Error("invalid compiler source hash bytes");
    const size = ({ Md5: 16, Sha1: 20, Sha256: 32 } as const)[match[1] as "Md5" | "Sha1" | "Sha256"];
    const expected = Buffer.from(values).subarray(0, size).toString("hex"), actual = createHash(match[1]!.toLowerCase()).update(input.source).digest("hex");
    if (actual !== expected || hash(input.source) !== input.sha256) throw new Error("compiler declaration source hash differs from immutable bytes");
    checked.set(file, compilerHash);
  }
  const start = integer(span.start), end = integer(span.end);
  if (start > end || end > Buffer.byteLength(input.source)) throw new Error("compiler source span exceeds immutable bytes");
  return { file, start, end };
}
const namespaces = new Set<CompilerNamespace>(["type", "value", "macro", "unknown"]);
const kinds = new Set<CompilerReference["kind"]>(["path", "import", "typed-value", "call", "callee"]);

/** Exact external source spans come from tcx.def_span, never from matching a name against another crate's text. */
export async function loadCompilerDriverBindings(value: unknown, expected: CompilerExpectation, files: ReadonlyMap<string, CompilerImmutableFile>, proofSha256: string): Promise<CompilerBindingIndex> {
  if (proofSha256 !== expected.proofSha256) throw new Error("compiler proof hash differs");
  const proof = manifest(value, expected);
  if (proof.compiler.sha256 !== expected.compilerSha256 || proof.compiler.version !== expected.compilerVersion || proof.originalCargoLock.sha256 !== expected.originalCargoLockSha256 || proof.buildCargoLock.sha256 !== expected.buildCargoLockSha256) throw new Error("compiler driver build identity differs");
  for (const input of [proof.compiler, proof.originalCargoLock, proof.buildCargoLock, proof.driver.binary, proof.driver.source, ...proof.components]) await verifyCompilerArtifact(input);
  if (!proof.components.length) throw new Error("compiler driver component identities are absent");
  for (const input of files.values()) if (hash(input.source) !== input.sha256) throw new Error("immutable source bytes differ");
  const index: CompilerBindingIndex = { schema: 1, source: proof.source, proofSha256, behavioralCoverage: false, definitions: [], declarations: [], references: [] };
  const checkedSources = new Map<string, string>();
  for (const crate of proof.crates) {
    argsMatch(crate);
    for (const input of [...crate.externs, ...crate.inputs]) await verifyCompilerArtifact(input);
    const scope = `${proofSha256}:${crate.crate}:driver`, definitions = new Map<string, Identity>(), declarations: CompilerDeclaration[] = [], references: CompilerReference[] = [];
    let began = false, complete = false, countedReferences = 0, session: Identity | undefined;
    for await (const row of rows(crate.facts)) {
      if (complete) throw new Error("compiler facts continue after their completion");
      if (!began) {
        const root = identity(row.root, scope);
        if (row.tag !== "session" || row.schema !== 1 || root.crate !== crate.crate || root.crateNumber !== 0 || root.index !== 0 || row.behavioralCoverage !== false) throw new Error("compiler fact session differs");
        session = root; began = true; continue;
      }
      if (row.tag === "declaration") {
        const def = identity(row.definition, scope), held = definitions.get(def.key);
        if (held) throw new Error("compiler declaration identity is duplicated");
        if (def.crateNumber === 0 && (def.crate !== session!.crate || def.crateHash !== session!.crateHash || def.stableCrateId !== session!.stableCrateId)) throw new Error("compiler local declaration namespace differs");
        definitions.set(def.key, def);
        declarations.push({ owner: def.key, kind: def.kind, range: sourceRange(row.span, proof, files, checkedSources) });
      } else if (row.tag === "reference") {
        const owner = identity(row.owner, scope), target = row.target === null ? undefined : identity(row.target, scope);
        if (definitions.get(owner.key)?.raw !== owner.raw || (target && definitions.get(target.key)?.raw !== target.raw)) throw new Error("compiler reference has no exact declared owner/target");
        const namespace = string(row.namespace) as CompilerNamespace, kind = string(row.kind) as CompilerReference["kind"];
        if (!namespaces.has(namespace) || !kinds.has(kind)) throw new Error("unsupported compiler reference kind/namespace");
        references.push({ owner: owner.key, target: target?.key, namespace, kind, resolution: string(row.resolution), range: sourceRange(row.span, proof, files, checkedSources) }); countedReferences++;
      } else if (row.tag === "debt") {
        const owner = identity(row.owner, scope);
        references.push({ owner: owner.key, namespace: "unknown", kind: "call", resolution: string(row.reason) });
      } else if (row.tag === "complete") {
        if (integer(row.declarations) !== declarations.length || integer(row.references) !== countedReferences || row.behavioralCoverage !== false) throw new Error("compiler fact completion counts differ");
        complete = true;
      } else throw new Error("unknown compiler fact record");
    }
    if (!began || !complete) throw new Error("compiler fact stream did not complete");
    index.definitions.push(...definitions.values()); index.declarations.push(...declarations); index.references.push(...references);
  }
  return freezeCompilerFacts(index);
}
