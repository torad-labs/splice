import { afterEach, expect, test } from "bun:test";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, resolve } from "node:path";
import { foreignMemberScope, type AstMatch, type ScopeFile } from "./current-foreign-scope";
import { astRules } from "./current-inventory";

const temporary: string[] = [];
afterEach(async () => { await Promise.all(temporary.splice(0).map(path => rm(path, { recursive: true, force: true }))); });

async function scope(sources: Record<string, string>, reverse = false) {
  const root = await mkdtemp(resolve(tmpdir(), "splice-foreign-scope-test-")); temporary.push(root);
  const files: ScopeFile[] = Object.entries(sources).map(([path, source]) => ({ path, source }));
  for (const file of files) { const path = resolve(root, file.path); await mkdir(dirname(path), { recursive: true }); await writeFile(path, file.source); }
  const proc = Bun.spawn([resolve(import.meta.dir, "../../node_modules/.bin/ast-grep"), "scan", "--inline-rules", astRules(), "--json=compact", "--no-ignore", "hidden", root], { stdout: "pipe", stderr: "pipe" });
  const [out, err, code] = await Promise.all([new Response(proc.stdout).text(), new Response(proc.stderr).text(), proc.exited]);
  expect(code, err).toBe(0);
  const raw: AstMatch[] = JSON.parse(out); if (reverse) raw.reverse();
  const result = foreignMemberScope(files, raw, root, ["codex-rs/code-mode"], [
    { path: "codex-rs/code-mode/Cargo.toml", source: '[package]\nname="codex-code-mode"\n[dependencies]\nserde_json="1"\n' },
    { path: "codex-rs/protocol/Cargo.toml", source: '[package]\nname="codex-protocol"\n[dependencies]\nserde="1"\n' },
  ]);
  const selected = (file: string, text: string) => raw.filter(node => node.file === resolve(root, file) && node.text.slice(0,node.text.indexOf("{")).includes(text) && node.ruleId === "function_item")
    .map(node => result.selections.get(`${file}#function_item@${node.range.byteOffset.start}`)?.state);
  return { ...result, selected };
}

test("associated helpers remain required through crate-root reexports and aliases", async () => {
  const result = await scope({
    "codex-rs/code-mode/src/lib.rs": "use codex_protocol::ToolName as Name; pub fn exec() { Name::new(); }",
    "codex-rs/protocol/src/lib.rs": "mod tool_name; pub use tool_name::ToolName;",
    "codex-rs/protocol/src/tool_name.rs": "pub struct ToolName; impl ToolName { pub fn new() {} pub fn unused() {} }",
  });
  expect(result.selected("codex-rs/protocol/src/tool_name.rs", "fn new")).toEqual(["required"]);
  expect(result.selected("codex-rs/protocol/src/tool_name.rs", "fn unused")).toEqual(["foreign-context"]);
  expect(result.unresolved).toEqual([]);
});

test("serializing an inferred local DTO retains its complete wire fields and exact local serde helper", async () => {
  const file="codex-rs/protocol/src/lib.rs",source=`use serde::Serialize;
pub fn active() {
    #[derive(Default, Serialize)]
    struct WireReply {
        #[serde(serialize_with = "write_chunk")]
        chunk_id: Option<String>,
        exit_code: Option<i32>,
        state: WireState,
    }
    #[derive(Default, Serialize)]
    enum WireState { #[default] Ready, Other }
    fn write_chunk<S: serde::Serializer>(value: &Option<String>, serializer: S) -> Result<S::Ok, S::Error> { value.serialize(serializer) }
    let result = WireReply { state: WireState::Ready, ..Default::default() };
    let _ = serde_json::to_value(result);
}
pub struct OtherReply { chunk_id: bool, exit_code: bool }
`;
  const result=await scope({"codex-rs/code-mode/src/lib.rs":"pub fn exec() { codex_protocol::active(); }",[file]:source});
  const state=(kind:string,text:string)=>result.selections.get(`${file}#${kind}@${Buffer.byteLength(source.slice(0,source.indexOf(text)))}`)?.state;
  for(const field of["chunk_id: Option<String>","exit_code: Option<i32>","state: WireState"])expect(state("field_declaration",field)).toBe("required");
  expect(state("enum_variant","Other }")).toBe("required");
  expect(result.selected(file,"fn write_chunk")).toEqual(["required"]);
  for(const field of["chunk_id: bool","exit_code: bool"])expect(state("field_declaration",field)).toBe("foreign-context");
});

test("tuple enum variants retain their own name when their payload contains a static lifetime", async () => {
  const result = await scope({
    "codex-rs/code-mode/src/lib.rs": "use codex_protocol::ToolEnvironments; pub fn exec() { ToolEnvironments::single(); }",
    "codex-rs/protocol/src/lib.rs": "pub enum ToolEnvironments { Arguments(Vec<(&'static str, String)>), All } impl ToolEnvironments { pub fn single() -> Self { Self::Arguments(vec![]) } }",
  });
  expect(result.unresolved).toEqual([]);
  const variant = [...result.selections].find(([id]) => id.includes("#enum_variant@"));
  expect(variant?.[1].state).toBe("required");
});

test("inline module reexports bind the exact helper without importing a same-named sibling", async () => {
  const result = await scope({
    "codex-rs/code-mode/src/lib.rs": "use codex_protocol::outer::Payload; pub fn exec() { Payload::make(); }",
    "codex-rs/protocol/src/lib.rs": "pub mod outer { pub use self::inner::Payload; mod inner { pub struct Payload; impl Payload { pub fn make() { helper(); } } fn helper() {} } } mod unrelated { pub struct Payload; impl Payload { pub fn make() {} } }",
  });
  expect(result.unresolved).toEqual([]);
  expect(result.selected("codex-rs/protocol/src/lib.rs", "fn make")).toEqual(["required", "foreign-context"]);
  expect(result.selected("codex-rs/protocol/src/lib.rs", "fn helper")).toEqual(["required"]);
});

test("an imported source macro remains a required dependency", async () => {
  const result = await scope({
    "codex-rs/code-mode/src/lib.rs": "use codex_protocol::report_error; pub fn exec() { report_error!(\"failure\"); }",
    "codex-rs/protocol/src/lib.rs": "macro_rules! report_error { ($message:expr) => { panic!(\"{}\", $message) }; } pub(crate) use report_error;",
  });
  expect(result.unresolved).toEqual([]);
  expect([...result.selections].filter(([id]) => id.includes("#macro_definition@")).map(([,selection])=>selection.state)).toEqual(["required"]);
});

test("a called local helper shadows the global helper while an uncalled local body stays outside the closure", async () => {
  const result=await scope({
    "codex-rs/code-mode/src/lib.rs":"pub fn exec() { codex_protocol::active(); }",
    "codex-rs/protocol/src/lib.rs":"pub fn active() { fn needed() { leaf(); } fn unused() { unrelated(); } needed(); } fn needed() {} fn leaf() {} fn unrelated() {}",
  });
  expect(result.unresolved).toEqual([]);
  expect(result.selected("codex-rs/protocol/src/lib.rs","fn needed")).toEqual(["required","foreign-context"]);
  expect(result.selected("codex-rs/protocol/src/lib.rs","fn leaf")).toEqual(["required"]);
  expect(result.selected("codex-rs/protocol/src/lib.rs","fn unused")).toEqual(["foreign-context"]);
  expect(result.selected("codex-rs/protocol/src/lib.rs","fn unrelated")).toEqual(["foreign-context"]);
});

test("reexport cycle resolution is independent of declaration and file order", async () => {
  const sources = {
    "codex-rs/code-mode/src/lib.rs": "use codex_protocol::{CellId, RuntimeResponse}; pub fn exec(id: CellId) { let _ = RuntimeResponse::new(id); }",
    "codex-rs/protocol/src/lib.rs": "mod session; mod response; pub use session::CellId; pub use response::RuntimeResponse;",
    "codex-rs/protocol/src/session.rs": "use crate::RuntimeResponse; pub struct CellId; impl CellId { pub fn new() -> Self { Self } pub fn reply(&self) -> RuntimeResponse { RuntimeResponse::new(Self::new()) } }",
    "codex-rs/protocol/src/response.rs": "use crate::CellId; pub struct RuntimeResponse { id: CellId } impl RuntimeResponse { pub fn new(id: CellId) -> Self { Self { id } } }",
  };
  const forward = await scope(sources), backward = await scope(Object.fromEntries(Object.entries(sources).reverse()), true);
  const states = (result: typeof forward) => [...result.selections].map(([id, value]) => [id, value.state]).sort(([a], [b]) => a!.localeCompare(b!));
  expect(states(backward)).toEqual(states(forward));
  expect([...backward.selections]).toEqual([...forward.selections]);
  expect(backward.unresolved).toEqual(forward.unresolved);
  expect(forward.unresolved).toEqual([]);
});

test("a library and binary root cannot shadow a public reexport", async () => {
  const result = await scope({
    "codex-rs/code-mode/src/lib.rs": "use codex_protocol::ToolName; pub fn exec() { ToolName::new(); }",
    "codex-rs/protocol/src/lib.rs": "mod tool_name; pub use tool_name::ToolName;",
    "codex-rs/protocol/src/main.rs": "fn main() {}",
    "codex-rs/protocol/src/tool_name.rs": "pub struct ToolName; impl ToolName { pub fn new() {} }",
  });
  expect(result.selected("codex-rs/protocol/src/tool_name.rs", "fn new")).toEqual(["required"]);
  expect(result.unresolved).toEqual([]);
});

test("type aliases retain associated project helpers", async () => {
  const result = await scope({
    "codex-rs/code-mode/src/lib.rs": "use codex_protocol::Alias; pub fn exec() { Alias::new(); }",
    "codex-rs/protocol/src/lib.rs": "pub struct ToolName; pub type Alias = ToolName; impl ToolName { pub fn new() {} }",
  });
  expect(result.selected("codex-rs/protocol/src/lib.rs", "fn new")).toEqual(["required"]);
  expect(result.unresolved).toEqual([]);
});

test("glob reexports retain associated methods without selecting unrelated methods", async () => {
  const result = await scope({
    "codex-rs/code-mode/src/lib.rs": "use codex_protocol::*; pub fn exec() { ToolName::new(); }",
    "codex-rs/protocol/src/lib.rs": "mod tool_name; pub use tool_name::*;",
    "codex-rs/protocol/src/tool_name.rs": "pub struct ToolName; impl ToolName { pub fn new() {} pub fn unused() {} }",
  });
  expect(result.selected("codex-rs/protocol/src/tool_name.rs", "fn new")).toEqual(["required"]);
  expect(result.selected("codex-rs/protocol/src/tool_name.rs", "fn unused")).toEqual(["foreign-context"]);
});

test("unresolved project imports produce blocking diagnostics", async () => {
  const result = await scope({
    "codex-rs/code-mode/src/lib.rs": "use codex_protocol::missing::ToolName; pub fn exec() { ToolName::new(); }",
    "codex-rs/protocol/src/tool_name.rs": "pub struct ToolName; impl ToolName { pub fn new() {} }",
  });
  expect(result.unresolved.some(issue => issue.expression === "ToolName::new" && issue.paths.includes("codex_protocol::missing::ToolName::new"))).toBe(true);
});

test("external imports and prelude types are not unresolved project references", async () => {
  const result = await scope({ "codex-rs/code-mode/src/lib.rs": "use std::sync::Arc; use serde_json::Value; pub fn exec(input: Value) { let _ = Arc::new(input); }" });
  expect(result.unresolved).toEqual([]);
});

test("serde follows its payload schema without serializing an incidental runtime trait", async () => {
  const result = await scope({
    "codex-rs/code-mode/src/lib.rs": "use codex_protocol::{Payload, Runtime}; pub fn exec(input: Payload, runtime: &dyn Runtime) { let _ = serde_json::to_value(input); }",
    "codex-rs/protocol/src/lib.rs": "pub struct Payload { pub answer: String } pub trait Runtime { fn unrelated(&self) {} }",
  });
  expect(result.selected("codex-rs/protocol/src/lib.rs", "fn unrelated")).toEqual(["foreign-context"]);
  expect(result.unresolved).toEqual([]);
});

test("receiver dispatch follows inherited trait methods", async () => {
  const result = await scope({
    "codex-rs/code-mode/src/lib.rs": "use codex_protocol::Runtime; pub fn exec(runtime: &dyn Runtime) { runtime.handle(); }",
    "codex-rs/protocol/src/lib.rs": "pub trait Executor { fn handle(&self) {} fn unrelated(&self) {} } pub trait Runtime: Executor {}",
  });
  expect(result.selected("codex-rs/protocol/src/lib.rs", "fn handle")).toEqual(["required"]);
  expect(result.selected("codex-rs/protocol/src/lib.rs", "fn unrelated")).toEqual(["foreign-context"]);
});
test("typed serde fields retain named visitor/default helpers without selecting unrelated functions", async () => {
  const result = await scope({
    "codex-rs/code-mode/src/lib.rs": "use codex_protocol::Payload; pub fn exec(input: Payload) { let _ = serde_json::to_value(input); }",
    "codex-rs/protocol/src/lib.rs": 'pub struct Payload { #[serde(default = "default_mode", deserialize_with = "deserialize_mode")] pub mode: String } fn default_mode() -> String { String::new() } fn deserialize_mode() {} fn unrelated() {}',
  });
  expect(result.selected("codex-rs/protocol/src/lib.rs", "fn default_mode")).toEqual(["required"]);
  expect(result.selected("codex-rs/protocol/src/lib.rs", "fn deserialize_mode")).toEqual(["required"]);
  expect(result.selected("codex-rs/protocol/src/lib.rs", "fn unrelated")).toEqual(["foreign-context"]);
});
test("const function names remain callable dependency members", async () => {
  const result = await scope({
    "codex-rs/code-mode/src/lib.rs": "use codex_protocol::Policy; pub fn exec() { Policy::new(); }",
    "codex-rs/protocol/src/lib.rs": "pub struct Policy; impl Policy { pub const fn new() -> Self { Self } pub const fn unrelated() -> Self { Self } }",
  });
  expect(result.selected("codex-rs/protocol/src/lib.rs", "fn new")).toEqual(["required"]);
  expect(result.selected("codex-rs/protocol/src/lib.rs", "fn unrelated")).toEqual(["foreign-context"]);
});
test("calling CodeMode does not make an unrelated application entrypoint part of the CodeMode API", async () => {
  const result = await scope({
    "codex-rs/code-mode/src/lib.rs": "pub fn exec() {}",
    "codex-rs/protocol/src/lib.rs": "pub fn spec_for_model_request() { codex_code_mode::normalize_code_mode_identifier(); } pub fn unrelated() {}",
  });
  expect(result.selected("codex-rs/protocol/src/lib.rs", "fn spec_for_model_request")).toEqual(["foreign-context"]);
  expect(result.selected("codex-rs/protocol/src/lib.rs", "fn unrelated")).toEqual(["foreign-context"]);
});

test("a generic consumer becomes required when an actual CodeMode root calls it", async () => {
  const result = await scope({
    "codex-rs/code-mode/src/lib.rs": "pub fn exec() { codex_protocol::spec_for_model_request(); } pub fn normalize_code_mode_identifier() {}",
    "codex-rs/protocol/src/lib.rs": "pub fn spec_for_model_request() { codex_code_mode::normalize_code_mode_identifier(); } pub fn unrelated() {}",
  });
  expect(result.selected("codex-rs/protocol/src/lib.rs", "fn spec_for_model_request")).toEqual(["required"]);
  expect(result.selected("codex-rs/protocol/src/lib.rs", "fn unrelated")).toEqual(["foreign-context"]);
});

test("session startup mentioning CodeMode cannot pull unrelated analytics into the API closure", async () => {
  const result = await scope({
    "codex-rs/code-mode/src/lib.rs": "pub struct CodeModeSession; pub fn exec() {}",
    "codex-rs/protocol/src/session.rs": "pub fn new_session() { let _ = codex_code_mode::CodeModeSession; publish_analytics(); } fn publish_analytics() {}",
  });
  expect(result.selected("codex-rs/protocol/src/session.rs", "fn new_session")).toEqual(["foreign-context"]);
  expect(result.selected("codex-rs/protocol/src/session.rs", "fn publish_analytics")).toEqual(["foreign-context"]);
});
test("typed serialization dispatch retains a handwritten serde implementation", async () => {
  const result = await scope({
    "codex-rs/code-mode/src/lib.rs": "use codex_protocol::Payload; pub fn exec(value: Payload) { let _ = serde_json::to_string(&value); }",
    "codex-rs/protocol/src/lib.rs": "pub struct Payload; impl serde::Serialize for Payload { fn serialize(&self) { self.helper(); } } impl Payload { fn helper(&self) {} fn unrelated(&self) {} }",
  });
  expect(result.selected("codex-rs/protocol/src/lib.rs", "fn serialize")).toEqual(["required"]);
  expect(result.selected("codex-rs/protocol/src/lib.rs", "fn helper")).toEqual(["required"]);
  expect(result.selected("codex-rs/protocol/src/lib.rs", "fn unrelated")).toEqual(["foreign-context"]);
});
test("sorting a typed collection retains its handwritten Ord comparator", async () => {
  const result = await scope({
    "codex-rs/code-mode/src/lib.rs": "use codex_protocol::ToolName; pub fn exec(mut names: Vec<ToolName>) { names.sort(); }",
    "codex-rs/protocol/src/lib.rs": "pub struct ToolName; impl Ord for ToolName { fn cmp(&self, other: &Self) {} } impl ToolName { fn unrelated(&self) {} }",
  });
  expect(result.selected("codex-rs/protocol/src/lib.rs", "fn cmp")).toEqual(["required"]);
  expect(result.selected("codex-rs/protocol/src/lib.rs", "fn unrelated")).toEqual(["foreign-context"]);
});
test("an unsserialized type mention cannot pull its handwritten serde branches into scope", async () => {
  const result = await scope({
    "codex-rs/code-mode/src/lib.rs": "use codex_protocol::Payload; pub fn exec(value: Payload) { value.run(); }",
    "codex-rs/protocol/src/lib.rs": "pub struct Payload; impl serde::Serialize for Payload { fn serialize(&self) { self.unrelated(); } } impl Payload { fn run(&self) {} fn unrelated(&self) {} }",
  });
  expect(result.selected("codex-rs/protocol/src/lib.rs", "fn run")).toEqual(["required"]);
  expect(result.selected("codex-rs/protocol/src/lib.rs", "fn serialize")).toEqual(["foreign-context"]);
  expect(result.selected("codex-rs/protocol/src/lib.rs", "fn unrelated")).toEqual(["foreign-context"]);
});
test("an application telemetry seed is outside the API but its actually called helper stays required", async () => {
  const result = await scope({
    "codex-rs/code-mode/src/lib.rs": "use codex_protocol::telemetry::record_code_mode; pub fn exec() { record_code_mode(); }",
    "codex-rs/protocol/src/telemetry.rs": "pub fn record_code_mode() {} pub fn unrelated_reducer() { codex_code_mode::CodeModeEvent(); }",
  });
  expect(result.selected("codex-rs/protocol/src/telemetry.rs", "fn record_code_mode")).toEqual(["required"]);
  expect(result.selected("codex-rs/protocol/src/telemetry.rs", "fn unrelated_reducer")).toEqual(["foreign-context"]);
});
test("implicit conversions retain authored implementations of the called parameter type", async () => {
  const result = await scope({
    "codex-rs/code-mode/src/lib.rs": "use codex_protocol::Schema; pub fn exec() { Schema::object(false.into()); }",
    "codex-rs/protocol/src/lib.rs": "pub struct Schema; pub enum Properties { Bool(bool) } impl Schema { pub fn object(properties: Properties) -> Self { Self } } impl From<bool> for Properties { fn from(value: bool) -> Self { Self::Bool(value) } }",
  });
  expect(result.selected("codex-rs/protocol/src/lib.rs", "fn from")).toEqual(["required"]);
});
