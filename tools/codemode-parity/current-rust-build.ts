import assert from "node:assert/strict";
import { mkdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { homedir } from "node:os";
import { zipEntry } from "../release/src/lib/zip";
import { artifact, sha256, type Artifact } from "./qualification";
import { capturedCommand } from "./current-command";

export const rustCompiler = { toolchain: "1.99.1", commit: "58cf8f9c35b355199727627d07ae34a4e2846d67", llvm: "23.1.1" } as const;
export const kotlinCompiler = { version: "2.3.21", archiveSha256: "a8cfc1d62cd4d0de4d04f42575e40135bd620588c17d568a20eb9c7c259af14f" } as const;
export function rustLockPackages(text: string): Array<{ name: string; version: string; source?: string; checksum?: string }> {
  return text.split("[[package]]").slice(1).map(section => {
    const field = (name: string) => section.match(new RegExp(`(?:^|\\n)${name} = "([^"]+)"`))?.[1];
    assert.ok(field("name") && field("version"), "invalid locked Rust package");
    return { name: field("name")!, version: field("version")!, source: field("source"), checksum: field("checksum") };
  });
}
export function assertRustDependencyClosure(official: string, project: string): void {
  const source = rustLockPackages(official);
  const origin = (value?: string) => {
    if (!value?.startsWith("git+")) return value;
    const url = new URL(value.slice(4));
    return url.hostname === "github.com" ? `git+github.com${url.pathname.replace(/\.git$/, "")}#${url.hash.slice(1)}` : value;
  };
  for (const dependency of rustLockPackages(project).filter(item => item.source)) {
    assert.ok(source.some(item => item.name === dependency.name && item.version === dependency.version && origin(item.source) === origin(dependency.source) && item.checksum === dependency.checksum),
      `standalone oracle external dependency differs from the exact official lock: ${dependency.name}@${dependency.version}`);
  }
}
export async function matchingRustToolchain(directory: string, llvm: boolean, save: (name: string, data: string) => Promise<unknown>): Promise<{ compiler: string; sysroot: string; host: string; llvmDirectory: string }> {
  let compiler = await capturedCommand(["rustup", "run", rustCompiler.toolchain, "rustc", "--version", "--verbose"], directory);
  if (compiler.exitCode !== 0) {
    const install = await capturedCommand(["rustup", "toolchain", "install", rustCompiler.toolchain, "--profile", "minimal", ...(llvm ? ["--component", "llvm-tools"] : [])], directory, undefined, 180_000);
    await save("toolchain-install.json", JSON.stringify(install)); assert.equal(install.exitCode, 0, install.stderr);
    compiler = await capturedCommand(["rustup", "run", rustCompiler.toolchain, "rustc", "--version", "--verbose"], directory);
  }
  assert.equal(compiler.exitCode, 0); assert.match(compiler.stdout, new RegExp(`commit-hash: ${rustCompiler.commit}\\b`));
  assert.match(compiler.stdout, /release: 1\.99\.1\b/); assert.match(compiler.stdout, /LLVM version: 23\.1\.1\b/);
  await save("compiler.json", JSON.stringify(compiler));
  const root = await capturedCommand(["rustup", "run", rustCompiler.toolchain, "rustc", "--print", "sysroot"], directory); assert.equal(root.exitCode, 0);
  const host = compiler.stdout.match(/^host: (.+)$/m)![1]!, sysroot = root.stdout.trim(), llvmDirectory = resolve(sysroot, "lib/rustlib", host, "bin");
  if (llvm) {
    let probe = await capturedCommand([resolve(llvmDirectory, "llvm-cov"), "--version"], directory).catch(() => ({ exitCode: -1, stdout: "", stderr: "missing llvm-tools" }));
    if (probe.exitCode !== 0) {
      const install = await capturedCommand(["rustup", "component", "add", "llvm-tools", "--toolchain", rustCompiler.toolchain], directory, undefined, 180_000);
      await save("llvm-install.json", JSON.stringify(install)); assert.equal(install.exitCode, 0, install.stderr);
      probe = await capturedCommand([resolve(llvmDirectory, "llvm-cov"), "--version"], directory);
    }
    assert.equal(probe.exitCode, 0); assert.match(probe.stdout, /23\.1\.1-rust-1\.99\.1/);
  }
  return { compiler: compiler.stdout, sysroot, host, llvmDirectory };
}
/** Pinned compiler ZIP, never whichever compiler happens to be first on PATH. */
export async function compileCandidateWrapper(java: string, jar: string, source: string | string[], directory: string): Promise<{ jar: string; compilerArchive: Artifact; source: Artifact; sources: Artifact[]; build: Artifact; entries: string[] }> {
  const root = resolve(process.env.XDG_CACHE_HOME ?? resolve(homedir(), ".cache"), "splice/codemode-tools", `kotlin-${kotlinCompiler.version}`);
  await mkdir(root, { recursive: true }); const archive = resolve(root, "compiler.zip");
  let cached = false; try { cached = sha256(await readFile(archive)) === kotlinCompiler.archiveSha256; } catch { /* bootstrap */ }
  if (!cached) {
    const partial = `${archive}.${crypto.randomUUID()}.part`;
    try {
      const response = await fetch(`https://github.com/JetBrains/kotlin/releases/download/v${kotlinCompiler.version}/kotlin-compiler-${kotlinCompiler.version}.zip`, { signal: AbortSignal.timeout(180_000) });
      assert.ok(response.ok, `pinned Kotlin wrapper compiler bootstrap failed: ${response.status}`); await Bun.write(partial, response);
      assert.equal(sha256(await readFile(partial)), kotlinCompiler.archiveSha256); await rename(partial, archive);
    } finally { await rm(partial, { force: true }); }
  }
  const jarTool = resolve(dirname(java), "jar"), listing = await capturedCommand([jarTool, "tf", archive], directory); assert.equal(listing.exitCode, 0, listing.stderr);
  const jars = listing.stdout.trimEnd().split("\n").filter(name => /^kotlinc\/lib\/[^/]+\.jar$/.test(name)); assert.ok(jars.length > 5);
  const libs: string[] = [];
  for (const name of jars) { const bytes = zipEntry(archive, name), path = resolve(root, name); await mkdir(dirname(path), { recursive: true }); await writeFile(path, bytes); libs.push(path); }
  const compilerVersion = await capturedCommand([java, "-cp", libs.join(":"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-version"], directory);
  assert.equal(compilerVersion.exitCode, 0); assert.match(compilerVersion.stdout + compilerVersion.stderr, /kotlinc-jvm 2\.3\.21\b/);
  const sources = typeof source === "string" ? [source] : source;
  const wrapper = resolve(directory, "candidate-wrapper.jar"), compiler = await capturedCommand([java, "-cp", libs.join(":"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "21", `-Xfriend-paths=${jar}`, "-cp", jar, ...sources, "-d", wrapper], directory, undefined, 180_000);
  const record = resolve(directory, "wrapper-build.json"); await writeFile(record, JSON.stringify({ compilerVersion, compiler }, null, 2) + "\n"); assert.equal(compiler.exitCode, 0, compiler.stderr);
  const entries = await capturedCommand([jarTool, "tf", wrapper], directory); assert.equal(entries.exitCode, 0);
  const names = entries.stdout.trimEnd().split("\n"); assert.ok(names.length && names.every(name => name.startsWith("ClientCandidate") || name.startsWith("META-INF/")), "candidate wrapper contains a copied production class");
  return { jar: wrapper, compilerArchive: await artifact(archive), source: await artifact(sources[0]!), sources: await Promise.all(sources.map(artifact)), build: await artifact(record), entries: names };
}
