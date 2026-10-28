import { afterEach, expect, test } from "bun:test";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, resolve } from "node:path";
import { generateCurrentInventory } from "./current-inventory";

const temporary: string[] = [];
afterEach(async () => { await Promise.all(temporary.splice(0).map(path => rm(path, {recursive:true,force:true}))); });

async function inventory(sources: Record<string,string>) {
  const root = await mkdtemp(resolve(tmpdir(),"splice-reference-discovery-test-")); temporary.push(root);
  for (const [name,text] of Object.entries(sources)) { const target=resolve(root,name); await mkdir(dirname(target),{recursive:true}); await writeFile(target,text); }
  const git = (...args:string[]) => {
    const child=Bun.spawnSync(["git",...args],{cwd:root});
    expect(child.exitCode,child.stderr.toString()).toBe(0); return child.stdout.toString().trim();
  };
  git("init","-q"); git("add","--all");
  git("-c","user.name=Fixture","-c","user.email=fixture@invalid","commit","-q","-m","immutable source");
  return await generateCurrentInventory(root,git("rev-parse","HEAD"));
}

test("reference discovery follows a project type and renamed reexport beyond the previous crate allowlist", async () => {
  const result = await inventory({
    "codex-rs/code-mode/Cargo.toml":'[package]\nname="codex-code-mode"\n',
    "codex-rs/code-mode/src/lib.rs":"use codex_mcp::PreparedMcpCall; pub fn exec() { PreparedMcpCall::prepare(); }",
    "codex-rs/mcp/Cargo.toml":'[package]\nname="codex-mcp"\n',
    "codex-rs/mcp/src/lib.rs":"mod prepared; pub use prepared::RawCall as PreparedMcpCall;",
    "codex-rs/mcp/src/prepared.rs":"pub struct RawCall; impl RawCall { pub fn prepare() { helper(); } } fn helper() {} fn unrelated() {}",
    "codex-rs/mcp/src/unrelated.rs":"pub struct PreparedMcpCall; impl PreparedMcpCall { pub fn prepare() {} }",
  });
  expect(result.foreignScopeDiagnostics).toEqual([]);
  const functions=result.units.filter(unit=>unit.file==="codex-rs/mcp/src/prepared.rs"&&unit.kind==="function_item");
  expect(functions.map(unit=>[unit.label,unit.foreignScope?.state])).toEqual([["prepare","required"],["helper","required"],["unrelated","foreign-context"]]);
  expect(result.units.find(unit=>unit.file==="codex-rs/mcp/src/unrelated.rs"&&unit.label==="prepare")?.foreignScope?.state).toBe("foreign-context");
});

test("discovering a same-named declaration never resolves a nonexistent qualified module", async () => {
  const result=await inventory({
    "codex-rs/code-mode/Cargo.toml":'[package]\nname="codex-code-mode"\n',
    "codex-rs/code-mode/src/lib.rs":"pub fn exec() { codex_mcp::missing::PreparedMcpCall::prepare(); }",
    "codex-rs/mcp/Cargo.toml":'[package]\nname="codex-mcp"\n',
    "codex-rs/mcp/src/lib.rs":"mod missing; mod unrelated;",
    "codex-rs/mcp/src/missing.rs":"pub struct Other;",
    "codex-rs/mcp/src/unrelated.rs":"pub struct PreparedMcpCall; impl PreparedMcpCall { pub fn prepare() {} }",
  });
  expect(result.foreignScopeDiagnostics?.some(issue=>issue.expression==="codex_mcp::missing::PreparedMcpCall::prepare")).toBe(true);
  expect(result.units.filter(unit=>unit.file==="codex-rs/mcp/src/unrelated.rs"&&unit.kind==="function_item").map(unit=>unit.foreignScope?.state)).toEqual(["foreign-context"]);
});

test("a missing member behind ancestor glob reexports terminates with a blocking reference", async () => {
  const result=await inventory({
    "codex-rs/code-mode/Cargo.toml":'[package]\nname="codex-code-mode"\n',
    "codex-rs/code-mode/src/lib.rs":"use codex_mcp::nested::MissingType; pub fn exec(value: MissingType) {}",
    "codex-rs/mcp/Cargo.toml":'[package]\nname="codex-mcp"\n',
    "codex-rs/mcp/src/lib.rs":"mod nested; pub use nested::*;",
    "codex-rs/mcp/src/nested.rs":"pub struct Other;",
  });
  expect(result.files).toHaveLength(3);
  expect(result.foreignScopeDiagnostics?.some(issue=>issue.paths.includes("codex_mcp::nested::MissingType"))).toBe(true);
  expect(result.foreignScopeDiagnostics!.flatMap(issue=>issue.paths).length).toBeLessThan(10);
});
