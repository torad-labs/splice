import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { readFile, stat } from "node:fs/promises";
import { isAbsolute, posix, relative } from "node:path";
import { StringDecoder } from "node:string_decoder";
import { createGunzip } from "node:zlib";
import { parseCompilerBindings } from "./current-compiler-bindings";
import type { CompilerSpan } from "./current-compiler-format";
import type { CompilerArtifact, CompilerBindingIndex, CompilerExpectation, CompilerImmutableFile, CompilerIrArtifact, CompilerProof, CompilerRange } from "./current-compiler-types";

const MAX_IR_BYTES = 128 * 1024 * 1024;
const MAX_GZIP_BYTES = 512 * 1024 * 1024;
const MAX_DECODED_BYTES = 2 * 1024 * 1024 * 1024;
const hash = (bytes: string | Uint8Array) => createHash("sha256").update(bytes).digest("hex");
function object(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== "object" || Array.isArray(value)) throw new Error("compiler proof requires an object");
  return value as Record<string, unknown>;
}
function string(value: unknown): string { if (typeof value !== "string" || !value) throw new Error("compiler proof requires a string"); return value; }
function strings(value: unknown): string[] {
  if (!Array.isArray(value) || value.some(item => typeof item !== "string")) throw new Error("compiler proof requires a string array");
  return value as string[];
}
function array(value: unknown): unknown[] { if (!Array.isArray(value)) throw new Error("compiler proof requires an array"); return value; }
function artifact(value: unknown): CompilerArtifact {
  const row = object(value), path = string(row.path), sha256 = string(row.sha256), bytes = row.bytes;
  if (!isAbsolute(path) || !/^[a-f0-9]{64}$/.test(sha256) || typeof bytes !== "number" || !Number.isSafeInteger(bytes) || bytes < 0) throw new Error("invalid compiler artifact identity");
  return { path, sha256, bytes };
}
function irArtifact(value: unknown): CompilerIrArtifact {
  const row = object(value), identity = artifact(value);
  if (row.encoding === undefined && row.decoded === undefined) return identity;
  if (row.encoding !== "gzip") throw new Error("unsupported compiler IR encoding");
  const decoded = object(row.decoded), bytes = decoded.bytes, sha256 = string(decoded.sha256);
  if (typeof bytes !== "number" || !Number.isSafeInteger(bytes) || bytes < 0 || !/^[a-f0-9]{64}$/.test(sha256)) throw new Error("invalid decoded compiler IR identity");
  return { ...identity, encoding: "gzip", decoded: { bytes, sha256 } };
}
function proof(value: unknown): CompilerProof {
  const row = object(value), source = object(row.source), compiler = object(row.compiler);
  if (row.schema !== 1) throw new Error("unsupported compiler proof schema");
  const sourceIdentity = { revision: string(source.revision), tree: string(source.tree), originalCargoLockSha256: string(source.originalCargoLockSha256), buildCargoLockSha256: string(source.buildCargoLockSha256) };
  if (![sourceIdentity.revision, sourceIdentity.tree].every(item => /^[a-f0-9]{40}$/.test(item)) ||
    ![sourceIdentity.originalCargoLockSha256, sourceIdentity.buildCargoLockSha256].every(item => /^[a-f0-9]{64}$/.test(item))) throw new Error("invalid compiler source identity");
  const sourcePrefix = string(row.sourcePrefix);
  if (sourcePrefix.startsWith("/") || sourcePrefix.includes("..")) throw new Error("invalid compiler source prefix");
  const crates = array(row.crates).map(value => {
    const crate = object(value), hir = object(crate.hir), thir = object(crate.thir);
    return { crate: string(crate.crate), cwd: string(crate.cwd), features: strings(crate.features), cfgs: strings(crate.cfgs), externs: array(crate.externs).map(artifact),
      hir: { ...irArtifact(hir), args: strings(hir.args) }, thir: { ...irArtifact(thir), args: strings(thir.args) } };
  });
  if (!crates.length || new Set(crates.map(crate => crate.crate)).size !== crates.length) throw new Error("compiler proof has no unique crate set");
  return { schema: 1, source: sourceIdentity, sourcePrefix, compiler: { ...artifact(compiler), version: string(compiler.version) }, buildCargoLock: artifact(row.buildCargoLock), crates };
}

/** Hashes the exact bytes parsed; a changed/truncated file cannot publish a partial index. */
export async function* verifiedCompilerLines(file: CompilerIrArtifact): AsyncIterable<string> {
  const compressed = file.encoding === "gzip", decoded = compressed ? file.decoded! : file;
  if (file.bytes > (compressed ? MAX_GZIP_BYTES : MAX_IR_BYTES) || decoded.bytes > MAX_DECODED_BYTES || (await stat(file.path)).size !== file.bytes) throw new Error("compiler IR size differs or exceeds its bound");
  const digest = createHash("sha256"), encodedDigest = createHash("sha256"), decoder = new StringDecoder("utf8");
  let pending = "", bytes = 0, encodedBytes = 0;
  const input = createReadStream(file.path), stream = compressed ? input.pipe(createGunzip()) : input;
  input.on("data", (data: Buffer) => {
    encodedBytes += data.length; encodedDigest.update(data);
    if (encodedBytes > file.bytes) stream.destroy(new Error("compiler IR grew beyond its identity"));
  });
  input.on("error", error => { if (stream !== input) stream.destroy(error); });
  try {
    for await (const chunk of stream) {
      const data = chunk as Buffer;
      bytes += data.length;
      if (bytes > decoded.bytes) throw new Error("decoded compiler IR grew beyond its identity");
      digest.update(data); pending += decoder.write(data);
      let newline = pending.indexOf("\n");
      while (newline >= 0) { yield pending.slice(0, newline).replace(/\r$/, ""); pending = pending.slice(newline + 1); newline = pending.indexOf("\n"); }
      if (pending.length > 1024 * 1024) throw new Error("compiler IR line exceeds its bound");
    }
  } finally {
    input.destroy(); if (stream !== input) stream.destroy();
  }
  pending += decoder.end();
  if (pending) yield pending;
  if (bytes !== decoded.bytes || digest.digest("hex") !== decoded.sha256 || encodedBytes !== file.bytes || encodedDigest.digest("hex") !== file.sha256) throw new Error("compiler IR hash differs");
}
async function verifyArtifact(file: CompilerArtifact): Promise<void> {
  if ((await stat(file.path)).size !== file.bytes) throw new Error("compiler input size differs");
  const digest = createHash("sha256"); let bytes = 0;
  for await (const chunk of createReadStream(file.path)) { bytes += (chunk as Buffer).length; digest.update(chunk as Buffer); }
  if (bytes !== file.bytes || digest.digest("hex") !== file.sha256) throw new Error("compiler input hash differs");
}
function validateArgs(args: string[], crate: CompilerProof["crates"][number], mode: string): void {
  const index = args.indexOf("--crate-name");
  if (index < 0 || args[index + 1] !== crate.crate || !args.includes(`-Zunpretty=${mode}`)) throw new Error("compiler analysis argv differs");
  const cfgs = args.flatMap((arg, at) => arg === "--cfg" ? [args[at + 1]!] : arg.startsWith("--cfg=") ? [arg.slice(6)] : []);
  if (JSON.stringify([...cfgs].sort()) !== JSON.stringify([...crate.cfgs].sort())) throw new Error("compiler cfg identity differs");
  const features = cfgs.flatMap(cfg => /^feature="([^"]+)"$/.exec(cfg)?.[1] ? [/^feature="([^"]+)"$/.exec(cfg)![1]!] : []);
  if (JSON.stringify(features.sort()) !== JSON.stringify([...crate.features].sort())) throw new Error("compiler feature identity differs");
  const externs = args.flatMap((arg, at) => arg === "--extern" ? [args[at + 1]!.slice(args[at + 1]!.indexOf("=") + 1)] : []);
  if (JSON.stringify(externs.sort()) !== JSON.stringify(crate.externs.map(item => item.path).sort())) throw new Error("compiler extern argv differs");
}

type SourceLines = { lines: string[]; starts: number[]; columns: Map<number, number[]> };
function range(span: CompilerSpan | undefined, cwd: string, prefix: string, files: ReadonlyMap<string, CompilerImmutableFile>, cache: Map<string, SourceLines>): CompilerRange | undefined {
  if (!span || span.context !== 0) return;
  const path = isAbsolute(span.file) ? relative(cwd, span.file) : span.file;
  if (path.startsWith("../") || path.includes("\\")) return;
  const file = posix.normalize(`${prefix}/${path}`), input = files.get(file);
  if (!input) return;
  let saved = cache.get(file);
  if (!saved) {
    const lines = input.source.split("\n"), starts: number[] = [];
    let start = 0;
    for (const line of lines) { starts.push(start); start += Buffer.byteLength(line) + 1; }
    saved = { lines, starts, columns: new Map() }; cache.set(file, saved);
  }
  const offset = (line: number, column: number): number => {
    const text = saved!.lines[line - 1];
    if (text === undefined) throw new Error("compiler span exceeds immutable source");
    let columns = saved!.columns.get(line);
    if (!columns) {
      columns = [0];
      for (const point of text) columns.push(columns.at(-1)! + Buffer.byteLength(point));
      saved!.columns.set(line, columns);
    }
    const at = columns[column - 1];
    if (at === undefined) throw new Error("compiler span exceeds immutable source");
    return saved!.starts[line - 1]! + at;
  };
  const start = offset(span.line, span.column), end = offset(span.endLine, span.endColumn);
  if (start > end) throw new Error("compiler source range is reversed");
  return { file, start, end };
}

function freeze<T>(value: T): T {
  if (value && typeof value === "object" && !Object.isFrozen(value)) {
    for (const child of Object.values(value)) freeze(child);
    Object.freeze(value);
  }
  return value;
}

export async function loadCompilerBindings(proofPath: string, expected: CompilerExpectation, files: ReadonlyMap<string, CompilerImmutableFile>): Promise<CompilerBindingIndex> {
  const bytes = await readFile(proofPath);
  if (bytes.length > 1024 * 1024) throw new Error("compiler proof exceeds its bound");
  const proofSha256 = hash(bytes);
  if (expected.proofSha256 !== proofSha256) throw new Error("compiler proof hash differs");
  const value = proof(JSON.parse(bytes.toString("utf8")));
  for (const key of ["revision", "tree", "originalCargoLockSha256", "buildCargoLockSha256"] as const) {
    if (value.source[key] !== expected[key]) throw new Error("compiler source identity differs");
  }
  if (value.compiler.sha256 !== expected.compilerSha256 || value.compiler.version !== expected.compilerVersion || value.buildCargoLock.sha256 !== expected.buildCargoLockSha256) throw new Error("compiler build identity differs");
  await verifyArtifact(value.compiler); await verifyArtifact(value.buildCargoLock);
  for (const input of files.values()) if (hash(input.source) !== input.sha256) throw new Error("immutable source bytes differ");
  const index: CompilerBindingIndex = { schema: 1, source: value.source, proofSha256, behavioralCoverage: false, definitions: [], declarations: [], references: [] };
  const sourceLines = new Map<string, SourceLines>();
  for (const crate of value.crates) {
    validateArgs(crate.hir.args, crate, "hir-tree"); validateArgs(crate.thir.args, crate, "thir-flat");
    for (const input of crate.externs) await verifyArtifact(input);
    const parsed = await parseCompilerBindings(verifiedCompilerLines(crate.hir), verifiedCompilerLines(crate.thir));
    if (parsed.definitions.some(definition => definition.raw.startsWith("DefId(0:") && definition.crate !== crate.crate)) throw new Error("compiler local crate namespace differs");
    const key = (local: string) => `${proofSha256}:${crate.crate}:${local}`;
    index.definitions.push(...parsed.definitions.map(item => ({ ...item, key: key(item.key) })));
    index.declarations.push(...parsed.declarations.map(item => ({ ...item, owner: key(item.owner), parentOwner: item.parentOwner && key(item.parentOwner), range: range(item.span, crate.cwd, value.sourcePrefix, files, sourceLines) })));
    index.references.push(...parsed.references.map(item => ({ ...item, owner: key(item.owner), target: item.target && key(item.target), range: range(item.span, crate.cwd, value.sourcePrefix, files, sourceLines) })));
  }
  return freeze(index);
}
