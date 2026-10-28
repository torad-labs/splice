import { afterEach, describe, expect, test } from "bun:test";
import { createHash } from "node:crypto";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { findCompilerBinding, loadCompilerBindings, type CompilerDriverProof, type CompilerExpectation } from "./current-compiler-bindings";

const fixtureDirectory = join(import.meta.dir, "fixtures/compiler-driver");
const digest = (bytes: string | Uint8Array) => createHash("sha256").update(bytes).digest("hex");
const temporary: string[] = [];
afterEach(async () => { for (const directory of temporary.splice(0)) await rm(directory, { force: true, recursive: true }); });
async function fixture() {
  const directory = await mkdtemp(join(tmpdir(), "splice-compiler-driver-")); temporary.push(directory);
  const manifest = JSON.parse(await readFile(join(fixtureDirectory, "manifest.json"), "utf8"));
  const artifact = async (path: string) => { const bytes = await readFile(path); return { path, bytes: bytes.length, sha256: digest(bytes) }; };
  const sources = new Map<string, { source: string; sha256: string }>();
  for (const entry of manifest.sources) {
    const name = entry.path.split("/").at(-1), source = await readFile(join(fixtureDirectory, name), "utf8");
    expect(digest(source)).toBe(entry.sha256); sources.set(`fixture/${name}`, { source, sha256: entry.sha256 });
  }
  const facts = await artifact(join(fixtureDirectory, "facts.jsonl")); expect(facts.sha256).toBe(manifest.output.sha256);
  const input = async (name: string) => { const path = join(directory, name); await writeFile(path, `reader unit identity ${name}; not executable compiler evidence\n`); return artifact(path); };
  const compiler = await input("compiler"), originalCargoLock = await input("original.lock"), buildCargoLock = await input("build.lock"), external = await input("external.rmeta");
  const identity = { revision: "c".repeat(40), tree: "d".repeat(40), originalCargoLockSha256: originalCargoLock.sha256, buildCargoLockSha256: buildCargoLock.sha256 };
  const proof: CompilerDriverProof = { schema: 1, format: "rustc-driver-v1", source: identity, sourceRoot: manifest.sourceRoot, sourcePrefix: "fixture", compiler: { ...compiler, version: "unit reader identity" }, originalCargoLock, buildCargoLock,
    driver: { binary: await input("driver"), source: await input("driver.rs") }, components: [await input("component")],
    crates: [{ crate: "compiler_driver_fixture", cwd: manifest.sourceRoot, args: ["--crate-name", "compiler_driver_fixture", "--emit=metadata", "-Zno-steal-thir=yes", "--extern", `external_fixture=${external.path}`], cfgs: [], features: [], externs: [external], inputs: [], facts }] };
  const path = join(directory, "proof.json");
  const seal = async (): Promise<CompilerExpectation> => { const bytes = JSON.stringify(proof); await writeFile(path, bytes); return { ...identity, compilerSha256: compiler.sha256, compilerVersion: proof.compiler.version, proofSha256: digest(bytes) }; };
  const locate = (token: string) => {
    const source = sources.get("fixture/probe.rs")!.source, at = source.indexOf(token);
    if (at < 0) throw new Error("source token absent"); const start = Buffer.byteLength(source.slice(0, at));
    return { file: "fixture/probe.rs", start, end: start + Buffer.byteLength(token) };
  };
  return { directory, artifact, sources, proof, path, seal, locate };
}

describe("actual rustc driver fact reader", () => {
  test("bridges cached external DefId directly to its hash-verified declaration bytes", async () => {
    const f = await fixture(), index = await loadCompilerBindings(f.path, await f.seal(), f.sources);
    const bound = findCompilerBinding(index, { ...f.locate("value.read()"), namespace: "value", kind: "call" });
    expect(bound.status).toBe("bound");
    if (bound.status !== "bound") throw new Error("actual external method did not bind");
    expect(bound.target.crate).toBe("external_fixture"); expect(bound.target.crateNumber).not.toBe(0);
    expect(bound.target.crateHash).toMatch(/^[a-f0-9]{32}$/); expect(bound.target.stableCrateId).toMatch(/^StableCrateId\(\d+\)$/);
    expect(bound.declaration?.range?.file).toBe("fixture/remote.rs");
    const range = bound.declaration!.range!;
    expect(Buffer.from(f.sources.get(range.file)!.source).subarray(range.start, range.end).toString()).toContain("fn read");
    expect(index.behavioralCoverage).toBe(false);
  });

  test("keeps same-name implementations, import namespaces and nested owners separate", async () => {
    const f = await fixture(), index = await loadCompilerBindings(f.path, await f.seal(), f.sources);
    const first = findCompilerBinding(index, { ...f.locate("first::Worker::run"), namespace: "value" });
    const second = findCompilerBinding(index, { ...f.locate("second::Worker::run"), namespace: "value" });
    expect(first.status).toBe("bound"); expect(second.status).toBe("bound");
    if (first.status !== "bound" || second.status !== "bound") throw new Error("qualified methods did not bind");
    expect(first.target.key).not.toBe(second.target.key);
    expect(first.declaration?.range?.start).not.toBe(second.declaration?.range?.start);
    expect(findCompilerBinding(index, { ...f.locate("first::Worker::run"), namespace: "type" }).status).toBe("unresolved");
    expect(findCompilerBinding(index, { ...f.locate("external_fixture::Remote"), namespace: "type", kind: "import" }).status).toBe("bound");
    expect(findCompilerBinding(index, { ...f.locate("external_fixture::Remote"), namespace: "macro" }).status).toBe("unresolved");
    const outer = index.definitions.find(item => item.path === "outer")!, nested = index.definitions.find(item => item.path === "outer::nested")!;
    expect(findCompilerBinding(index, { ...f.locate("first::Worker::run"), namespace: "value", owner: nested.key }).status).toBe("bound");
    expect(findCompilerBinding(index, { ...f.locate("first::Worker::run"), namespace: "value", owner: outer.key }).status).toBe("unresolved");
  });

  test("dynamic and generic dispatch retain their compiler targets as unresolved debt", async () => {
    const f = await fixture(), index = await loadCompilerBindings(f.path, await f.seal(), f.sources);
    expect(index.references.some(item => item.resolution === "dynamic-call" && item.target !== undefined)).toBe(true);
    expect(index.references.some(item => item.resolution === "generic-call" && item.target !== undefined)).toBe(true);
    expect(findCompilerBinding(index, { ...f.locate("value.pick()"), namespace: "value", kind: "call" }).status).toBe("unresolved");
    expect(Object.isFrozen(index.declarations)).toBe(true);
  });

  test("Unicode source bytes and external hashes bind without a path or name lookup", async () => {
    const f = await fixture(), index = await loadCompilerBindings(f.path, await f.seal(), f.sources);
    const query = { ...f.locate("external_fixture::value"), namespace: "value" as const };
    expect(findCompilerBinding(index, query).status).toBe("bound");
    const old = f.sources.get("fixture/remote.rs")!, changed = old.source.replace("7", "9");
    f.sources.set("fixture/remote.rs", { source: changed, sha256: digest(changed) });
    await expect(loadCompilerBindings(f.path, await f.seal(), f.sources)).rejects.toThrow("compiler declaration source hash differs");
  });

  test("changed compiler components and original extern metadata are rejected", async () => {
    const f = await fixture(), expected = await f.seal();
    await writeFile(f.proof.components[0]!.path, "altered component");
    await expect(loadCompilerBindings(f.path, expected, f.sources)).rejects.toThrow("compiler input");
    const g = await fixture(), sealed = await g.seal();
    await writeFile(g.proof.crates[0]!.externs[0]!.path, "different metadata");
    await expect(loadCompilerBindings(g.path, sealed, g.sources)).rejects.toThrow("compiler input");
  });

  test("an incomplete stream or incorrect completion count never publishes partial facts", async () => {
    const f = await fixture(), crate = f.proof.crates[0]!, lines = (await readFile(crate.facts.path, "utf8")).trimEnd().split("\n");
    const path = join(f.directory, "facts.jsonl"); await writeFile(path, lines.slice(0, -1).join("\n") + "\n"); crate.facts = await f.artifact(path);
    await expect(loadCompilerBindings(f.path, await f.seal(), f.sources)).rejects.toThrow("did not complete");
    const last = JSON.parse(lines.at(-1)!); last.references++;
    await writeFile(path, [...lines.slice(0, -1), JSON.stringify(last)].join("\n") + "\n"); crate.facts = await f.artifact(path);
    await expect(loadCompilerBindings(f.path, await f.seal(), f.sources)).rejects.toThrow("completion counts differ");
  });

  test("missing immutable files retain unresolved declarations rather than dropping reference rows", async () => {
    const f = await fixture(), expected = await f.seal(), complete = await loadCompilerBindings(f.path, expected, f.sources);
    f.sources.delete("fixture/remote.rs");
    const partial = await loadCompilerBindings(f.path, expected, f.sources);
    expect(partial.references.length).toBe(complete.references.length); expect(partial.declarations.length).toBe(complete.declarations.length);
    const result = findCompilerBinding(partial, { ...f.locate("value.read()"), namespace: "value", kind: "call" });
    expect(result.status).toBe("bound");
    if (result.status !== "bound") throw new Error("exact compiler identity should remain available");
    expect(result.declaration?.range).toBeUndefined();
  });
});
