import { afterAll, beforeAll, expect, test } from "bun:test";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, resolve } from "node:path";
import { apiMemberValues } from "./current-api-values";
import { artifact, sha256 } from "./qualification";
import type { CurrentInventory, SourceUnit } from "./current-inventory";
import type { ApiJob } from "./current-core-api-witness";

const modelPath = "codex-rs/protocol/src/models.rs", definitionPath = "codex-rs/code-mode-protocol/src/definition.rs";
const image = "InputImage {\n        #[serde(flatten)]\n        image: ImageReference,\n        detail: Option<String>,\n    }";
const text = "InputText { text: String }";
const content = `pub enum FunctionCallOutputContentItem {\n    ${image},\n    ${text},\n}`;
const inline = "Inline { image_url: String }", file = "File { file_id: String }";
const imageReference = `pub enum ImageReference {\n    ${inline},\n    ${file},\n}`;
const models = `#[serde(tag = "type", rename_all = "snake_case")]\n${content}\n\n#[serde(untagged)]\n${imageReference}\n`;
const definition = "pub struct ToolDefinition {\n    pub name: String,\n    pub description: String,\n}";
const driver = `fn evaluate() { match "response_content_item" { "response_content_item" => roundtrip::<codex_protocol::models::FunctionCallOutputContentItem>(value), _ => () }; prepare_code_mode_tool_definitions(None); json!({"prepared_by_spec":prepared_by_spec}); }`;
let directory: string, inventory: CurrentInventory, build: { sourceRepository: string; driverSources: Awaited<ReturnType<typeof artifact>>[] };

function unit(path: string, source: string, kind: string, label: string, declaration: string): SourceUnit {
  const start = Buffer.byteLength(source.slice(0, source.indexOf(declaration)));
  if (!source.includes(declaration)) throw new Error(`test declaration absent: ${label}`);
  return { id: `${path}#${kind}@${start}`, file: path, kind, label, line: source.slice(0, source.indexOf(declaration)).split("\n").length,
    start, end: start + Buffer.byteLength(declaration), sha256: sha256(declaration), ownership: "ported" };
}
function git(...args: string[]): string {
  const result = Bun.spawnSync(["git", "-C", directory, ...args], { stdout: "pipe", stderr: "pipe" });
  if (result.exitCode !== 0) throw new Error(result.stderr.toString());
  return result.stdout.toString().trim();
}
beforeAll(async () => {
  directory = await mkdtemp(resolve(tmpdir(), "splice-api-values-"));
  for (const [path, source] of [[modelPath, models], [definitionPath, definition], ["src/main.rs", driver]]) {
    await mkdir(dirname(resolve(directory, path!)), { recursive: true }); await writeFile(resolve(directory, path!), source!);
  }
  git("init", "--quiet"); git("add", "."); git("-c", "user.name=Source fixture", "-c", "user.email=fixture@localhost", "commit", "--quiet", "-m", "immutable value fixture");
  const units = [
    unit(modelPath, models, "enum_item", "FunctionCallOutputContentItem", content),
    unit(modelPath, models, "enum_variant", "InputImage", image), unit(modelPath, models, "enum_variant", "InputText", text),
    unit(modelPath, models, "field_declaration", "image", "image: ImageReference"), unit(modelPath, models, "field_declaration", "detail", "detail: Option<String>"),
    unit(modelPath, models, "field_declaration", "text", "text: String"),
    unit(modelPath, models, "enum_item", "ImageReference", imageReference),
    unit(modelPath, models, "enum_variant", "Inline", inline), unit(modelPath, models, "enum_variant", "File", file),
    unit(modelPath, models, "field_declaration", "image_url", "image_url: String"), unit(modelPath, models, "field_declaration", "file_id", "file_id: String"),
    unit(definitionPath, definition, "struct_item", "ToolDefinition", definition),
    unit(definitionPath, definition, "field_declaration", "name", "pub name: String"), unit(definitionPath, definition, "field_declaration", "description", "pub description: String"),
  ];
  inventory = { schema: 2, generator: "fixture", sourceRevision: git("rev-parse", "HEAD"), sourceTree: git("rev-parse", "HEAD^{tree}"),
    sourceCargoLockSha256: "", astGrepVersion: "", discoveredCrates: [], grpcMethods: [], globalHelpers: [], denominatorSha256: "", units,
    files: [{ path: modelPath, sha256: sha256(models), gitBlob: "", discovery: "module", units: 11 }, { path: definitionPath, sha256: sha256(definition), gitBlob: "", discovery: "crate", units: 3 }] };
  build = { sourceRepository: directory, driverSources: [await artifact(resolve(directory, "src/main.rs"))] };
});
afterAll(async () => { await rm(directory, { recursive: true, force: true }); });

function job(referencePath: Array<string | number> = ["value"]): ApiJob {
  return { id: "image", reference_input: { operation: "protocol_shape", type: "response_content_item" }, candidate_input: { operation: "format" }, reference_path: referencePath, candidate_path: ["content", 0] };
}
const item = { type: "input_image", image_url: "https://image.invalid/a", detail: "original" };

test("flattened image reference fields bind only the uniquely serialized untagged variant", async () => {
  const values = await apiMemberValues([job()], [{ result: { value: item } }], [{ result: { content: [item] } }], inventory, build);
  const ids = new Set(values.map(value => value.id));
  for (const label of ["ImageReference", "Inline", "image", "image_url"]) expect(ids.has(inventory.units.find(unit => unit.label === label)!.id)).toBe(true);
  expect(ids.has(inventory.units.find(unit => unit.label === "File")!.id)).toBe(false);
  expect(ids.has(inventory.units.find(unit => unit.label === "file_id")!.id)).toBe(false);
});

test("an uncompared sibling cannot inherit a typed image witness", async () => {
  const values = await apiMemberValues([job(["value", "detail"])], [{ result: { value: item } }], [{ result: { content: ["original"] } }], inventory, build);
  expect(values.map(value => value.id)).toEqual([inventory.units.find(unit => unit.label === "detail")!.id]);
});

test("a differing candidate image rejects the source observation", async () => {
  await expect(apiMemberValues([job()], [{ result: { value: item } }], [{ result: { content: [{ ...item, image_url: "different" }] } }], inventory, build)).rejects.toThrow();
});

test("prepared ToolDefinition values come only from their compared collector output", async () => {
  const plan: ApiJob = { id: "prepared", reference_input: { operation: "tool_spec_shape" }, candidate_input: { operation: "collect" }, reference_path: ["prepared_by_spec", 0], candidate_path: ["prepared"] };
  const value = { name: "alpha", description: "Alpha" };
  const values = await apiMemberValues([plan], [{ result: { prepared_by_spec: [[value]], api: [{ strict: true }] } }], [{ result: { prepared: [value] } }], inventory, build);
  expect(values).toHaveLength(3);
  expect(values.every(value => value.id.startsWith(definitionPath))).toBe(true);
  expect(values.every(value => value.path[0] === "prepared" && value.path[1] === "prepared_by_spec")).toBe(true);
});

test("unchanged driver identity cannot bless changed immutable source hashes", async () => {
  const changed = structuredClone(inventory); changed.files[0]!.sha256 = "0".repeat(64);
  await expect(apiMemberValues([job()], [{ result: { value: item } }], [{ result: { content: [item] } }], changed, build)).rejects.toThrow();
});
