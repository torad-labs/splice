import { afterEach, expect, test } from "bun:test";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, resolve } from "node:path";
import { foreignMemberScope, type AstMatch, type ScopeFile } from "./current-foreign-scope";
import { astRules } from "./current-inventory";

const temporary: string[] = [];
afterEach(async () => { await Promise.all(temporary.splice(0).map(path => rm(path, { recursive: true, force: true }))); });

async function audit(sources: Record<string, string>) {
  const root = await mkdtemp(resolve(tmpdir(), "splice-resolver-adversary-test-")); temporary.push(root);
  const files: ScopeFile[] = Object.entries(sources).map(([path, source]) => ({ path, source }));
  for (const file of files) { const path = resolve(root, file.path); await mkdir(dirname(path), { recursive: true }); await writeFile(path, file.source); }
  const child = Bun.spawn([resolve(import.meta.dir, "../../node_modules/.bin/ast-grep"), "scan", "--inline-rules", astRules(), "--json=compact", "--no-ignore", "hidden", root], { stdout: "pipe", stderr: "pipe" });
  const [stdout, stderr, exit] = await Promise.all([new Response(child.stdout).text(), new Response(child.stderr).text(), child.exited]);
  expect(exit, stderr).toBe(0);
  const raw: AstMatch[] = JSON.parse(stdout);
  const result = foreignMemberScope(files, raw, root, ["codex-rs/code-mode"], [
    { path: "codex-rs/code-mode/Cargo.toml", source: '[package]\nname="codex-code-mode"\n' },
    { path: "codex-rs/protocol/Cargo.toml", source: '[package]\nname="codex-protocol"\n' },
  ]);
  const state = (file: string, text: string) => raw.filter(node => node.file === resolve(root, file) && node.ruleId === "function_item" && node.text === text)
    .map(node => result.selections.get(`${file}#function_item@${node.range.byteOffset.start}`)?.state);
  return { ...result, state };
}

// Each fixture is legal Rust. The retained independent rustc executions return seven:
// artifacts/session-completion-2026-10-27/architecture-resolver-adversarial-review.json.
test("a qualified impl owner cannot shadow the actual type's default method", async () => {
  const result = await audit({
    "codex-rs/code-mode/src/lib.rs": "use codex_protocol::Build; pub fn exec() -> i32 { codex_protocol::a::T::make() }",
    "codex-rs/protocol/src/lib.rs": "pub trait Build { fn make() -> i32 { 7 } } pub mod a { pub struct T; impl super::Build for T {} impl super::b::T { pub fn make() -> i32 { 9 } } } pub mod b { pub struct T; }",
  });
  expect(result.state("codex-rs/protocol/src/lib.rs", "fn make() -> i32 { 7 }")).toEqual(["required"]);
  expect(result.state("codex-rs/protocol/src/lib.rs", "pub fn make() -> i32 { 9 }")).toEqual(["foreign-context"]);
  expect(result.unresolved).toEqual([]);
});

test("a called nested function remains an explicit behavioral obligation", async () => {
  const result = await audit({
    "codex-rs/code-mode/src/lib.rs": "pub fn exec() -> i32 { codex_protocol::active() }",
    "codex-rs/protocol/src/lib.rs": "pub fn active() -> i32 { fn needed() -> i32 { 7 } needed() }",
  });
  expect(result.state("codex-rs/protocol/src/lib.rs", "fn needed() -> i32 { 7 }")).toEqual(["required"]);
  expect(result.unresolved).toEqual([]);
});

test("a binary's same-named type cannot satisfy a reference to the package library", async () => {
  const result = await audit({
    "codex-rs/code-mode/src/lib.rs": "use codex_protocol::Build; pub fn exec() -> i32 { codex_protocol::T::make() }",
    "codex-rs/protocol/src/lib.rs": "pub trait Build { fn make() -> i32 { 7 } } pub struct T; impl Build for T {}",
    "codex-rs/protocol/src/main.rs": "struct T; impl T { fn make() -> i32 { 9 } } fn main() {}",
  });
  expect(result.state("codex-rs/protocol/src/lib.rs", "fn make() -> i32 { 7 }")).toEqual(["required"]);
  expect(result.state("codex-rs/protocol/src/main.rs", "fn make() -> i32 { 9 }")).toEqual(["foreign-context"]);
  expect(result.unresolved).toEqual([]);
});
