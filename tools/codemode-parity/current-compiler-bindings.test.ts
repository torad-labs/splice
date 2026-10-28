import { afterEach, describe, expect, test } from "bun:test";
import { createHash } from "node:crypto";
import { mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { findCompilerBinding, loadCompilerBindings, parseCompilerBindings, type CompilerExpectation, type CompilerProof } from "./current-compiler-bindings";
import { compilerTextLines } from "./current-compiler-format";

const directory = join(import.meta.dir, "fixtures/compiler-bindings");
const digest = (value: string | Uint8Array) => createHash("sha256").update(value).digest("hex");
const temporary: string[] = [];
afterEach(async () => { for (const path of temporary.splice(0)) await rm(path, { recursive: true, force: true }); });

async function fixture() {
  const root = await mkdtemp(join(tmpdir(), "splice-compiler-binding-")); temporary.push(root);
  const manifest = JSON.parse(await readFile(join(directory, "manifest.json"), "utf8"));
  const source = await readFile(join(directory, "probe.rs"), "utf8");
  expect(digest(source)).toBe(manifest.source.sha256);
  const artifact = async (path: string) => ({ path, bytes: (await stat(path)).size, sha256: digest(await readFile(path)) });
  const compiler = join(root, "compiler-identity"), lock = join(root, "Cargo.lock");
  await writeFile(compiler, "unit test compiler identity, never executable evidence\n");
  await writeFile(lock, "version = 4\n");
  const compilerIdentity = await artifact(compiler), lockIdentity = await artifact(lock);
  const sourceIdentity = { revision: "a".repeat(40), tree: "b".repeat(40), originalCargoLockSha256: lockIdentity.sha256, buildCargoLockSha256: lockIdentity.sha256 };
  const proof: CompilerProof = { schema: 1, source: sourceIdentity, sourcePrefix: "fixture", compiler: { ...compilerIdentity, version: manifest.compiler.version }, buildCargoLock: lockIdentity,
    crates: [{ crate: "compiler_binding_fixture", cwd: directory, features: [], cfgs: [], externs: [],
      hir: { ...await artifact(join(directory, "hir-tree.txt")), args: [...manifest.args, "-Zunpretty=hir-tree"] },
      thir: { ...await artifact(join(directory, "thir-flat.txt")), args: [...manifest.args, "-Zunpretty=thir-flat"] } }] };
  expect(proof.crates[0]!.hir.sha256).toBe(manifest.hir.sha256);
  expect(proof.crates[0]!.thir.sha256).toBe(manifest.thir.sha256);
  const path = join(root, "proof.json");
  const files = new Map([["fixture/probe.rs", { source, sha256: manifest.source.sha256 as string }]]);
  const seal = async (): Promise<CompilerExpectation> => {
    const bytes = JSON.stringify(proof); await writeFile(path, bytes);
    return { ...sourceIdentity, compilerSha256: compilerIdentity.sha256, compilerVersion: manifest.compiler.version, proofSha256: digest(bytes) };
  };
  const locate = (text: string, occurrence = 0) => {
    let index = -1;
    for (let at = 0; at <= occurrence; at++) index = source.indexOf(text, index + 1);
    if (index < 0) throw new Error("fixture source token absent");
    const start = Buffer.byteLength(source.slice(0, index)); return { file: "fixture/probe.rs", start, end: start + Buffer.byteLength(text) };
  };
  return { root, source, proof, path, files, seal, locate, artifact };
}

describe("pinned compiler bindings", () => {
  test("binds qualified inherent methods without borrowing a same-name implementation or namespace", async () => {
    const f = await fixture(), index = await loadCompilerBindings(f.path, await f.seal(), f.files);
    const alpha = findCompilerBinding(index, { ...f.locate("alpha::Worker::run"), namespace: "value" });
    const beta = findCompilerBinding(index, { ...f.locate("beta::Worker::run"), namespace: "value" });
    expect(alpha.status).toBe("bound"); expect(beta.status).toBe("bound");
    if (alpha.status !== "bound" || beta.status !== "bound") throw new Error("fixture methods did not bind");
    expect(alpha.target.path).toBe("alpha::{impl#0}::run");
    expect(beta.target.path).toBe("beta::{impl#0}::run");
    expect(alpha.target.key).not.toBe(beta.target.key);
    expect(findCompilerBinding(index, { ...f.locate("alpha::Worker::run"), namespace: "type" }).status).toBe("unresolved");
    expect(index.behavioralCoverage).toBe(false);
  });

  test("retains import namespaces and resolves a call through a glob reexport", async () => {
    const f = await fixture(), index = await loadCompilerBindings(f.path, await f.seal(), f.files);
    const imported = findCompilerBinding(index, { ...f.locate("alpha::Worker", 0), namespace: "type", kind: "import" });
    expect(imported.status).toBe("bound");
    if (imported.status !== "bound") throw new Error("fixture import did not bind");
    expect(imported.target.path).toBe("alpha::Worker");
    expect(findCompilerBinding(index, { ...f.locate("alpha::Worker", 0), namespace: "macro" }).status).toBe("unresolved");
    const glob = findCompilerBinding(index, { ...f.locate("facade::helper"), namespace: "value" });
    expect(glob.status).toBe("bound");
    if (glob.status !== "bound") throw new Error("fixture glob target did not bind");
    expect(glob.target.path).toBe("alpha::helper");
  });

  test("nested functions retain their own owner and qualified target", async () => {
    const f = await fixture(), index = await loadCompilerBindings(f.path, await f.seal(), f.files);
    const outer = index.definitions.find(item => item.path === "outer")!, nested = index.definitions.find(item => item.path === "outer::nested")!;
    expect(nested).toBeDefined();
    const correct = findCompilerBinding(index, { ...f.locate("alpha::Worker::run"), namespace: "value", owner: nested.key });
    expect(correct.status).toBe("bound");
    expect(findCompilerBinding(index, { ...f.locate("alpha::Worker::run"), namespace: "value", owner: outer.key }).status).toBe("unresolved");
    const other = index.definitions.find(item => item.path === "other::nested")!;
    expect(findCompilerBinding(index, { ...f.locate("alpha::Worker::run"), namespace: "value", owner: other.key }).status).toBe("unresolved");
  });

  test("dynamic calls retain the trait fact but cannot certify a concrete target", async () => {
    const f = await fixture(), index = await loadCompilerBindings(f.path, await f.seal(), f.files);
    expect(index.references.some(item => item.kind === "call" && item.resolution === "dynamic-call" && item.target !== undefined)).toBe(true);
    expect(findCompilerBinding(index, { ...f.locate("value.pick()"), namespace: "value", kind: "call" }).status).toBe("unresolved");
    expect(index.references.some(item => item.kind === "call" && item.resolution === "generic-call")).toBe(true);
  });

  test("fields and variants retain exact compiler definitions and parent ownership", async () => {
    const f = await fixture(), index = await loadCompilerBindings(f.path, await f.seal(), f.files);
    const count = index.definitions.find(item => item.path === "Options::count")!;
    const declaration = index.declarations.find(item => item.owner === count.key)!;
    expect(declaration.kind).toBe("FieldDef");
    expect(index.definitions.find(item => item.key === declaration.parentOwner)?.path).toBe("Options");
    expect(Buffer.from(f.source).subarray(declaration.range!.start, declaration.range!.end).toString()).toContain("count: u32");
    const variant = index.definitions.find(item => item.path === "Mode::Named")!;
    expect(index.declarations.find(item => item.owner === variant.key)?.kind).toBe("Variant");
  });

  test("maps compiler character columns to exact UTF-8 source bytes", async () => {
    const f = await fixture(), index = await loadCompilerBindings(f.path, await f.seal(), f.files);
    const target = findCompilerBinding(index, { ...f.locate("alpha::helper"), namespace: "value" });
    expect(target.status).toBe("bound");
    if (target.status !== "bound") throw new Error("unicode source position did not bind");
    expect(target.target.path).toBe("alpha::helper");
  });

  test("rejects a different pinned proof, source tree, compiler or immutable source bytes", async () => {
    const f = await fixture(), expected = await f.seal();
    await expect(loadCompilerBindings(f.path, { ...expected, proofSha256: "0".repeat(64) }, f.files)).rejects.toThrow("proof hash differs");
    await expect(loadCompilerBindings(f.path, { ...expected, tree: "0".repeat(40) }, f.files)).rejects.toThrow("source identity differs");
    await expect(loadCompilerBindings(f.path, { ...expected, compilerSha256: "0".repeat(64) }, f.files)).rejects.toThrow("build identity differs");
    f.files.set("fixture/probe.rs", { source: f.source + " ", sha256: f.files.get("fixture/probe.rs")!.sha256 });
    await expect(loadCompilerBindings(f.path, expected, f.files)).rejects.toThrow("source bytes differ");
  });

  test("checks actual IR hashes and analysis argv before publishing an index", async () => {
    const f = await fixture(); f.proof.crates[0]!.hir.sha256 = "0".repeat(64);
    await expect(loadCompilerBindings(f.path, await f.seal(), f.files)).rejects.toThrow("IR hash differs");
    const g = await fixture(); g.proof.crates[0]!.hir.args[1] = "different_namespace";
    await expect(loadCompilerBindings(g.path, await g.seal(), g.files)).rejects.toThrow("argv differs");
  });

  test("refuses changed extern bytes and incomplete compiler trees", async () => {
    const f = await fixture(), external = join(f.root, "external.rmeta");
    await writeFile(external, "actual metadata fixture");
    f.proof.crates[0]!.externs.push(await f.artifact(external));
    for (const mode of [f.proof.crates[0]!.hir, f.proof.crates[0]!.thir]) mode.args.push("--extern", `external=${external}`);
    const expected = await f.seal(); await writeFile(external, "changed metadata data!!");
    await expect(loadCompilerBindings(f.path, expected, f.files)).rejects.toThrow("input hash differs");
    await expect(parseCompilerBindings(compilerTextLines("DefId(0:0 ~ sample[abc]) => OwnerNodes {"), compilerTextLines(""))).rejects.toThrow("incomplete compiler output");
  });

  test("conflicting compiler targets and absent source evidence remain unresolved without deleting rows", async () => {
    const f = await fixture(), index = await loadCompilerBindings(f.path, await f.seal(), f.files);
    const span = f.locate("alpha::Worker::run"), original = index.references.find(item => item.range?.start === span.start && item.range.end === span.end && item.namespace === "value")!;
    const wrong = index.definitions.find(item => item.path === "beta::{impl#0}::run")!;
    const conflicting = { ...index, references: [...index.references, { ...original, target: wrong.key }] }, count = conflicting.references.length;
    expect(findCompilerBinding(conflicting, { ...span, namespace: "value" }).status).toBe("ambiguous");
    expect(findCompilerBinding(conflicting, { ...span, file: "fixture/not-compiled.rs", namespace: "value" }).status).toBe("unresolved");
    expect(conflicting.references.length).toBe(count);
    expect(Object.isFrozen(index.references)).toBe(true);
  });
});
