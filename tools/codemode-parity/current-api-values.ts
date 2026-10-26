import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import type { CurrentInventory, SourceUnit } from "./current-inventory";
import type { ApiBuildProof, ApiJob } from "./current-core-api-witness";
import type { CoreValueWitness } from "./current-core-values";
import { capturedCommand } from "./current-command";
import { sha256 } from "./qualification";
import { assertObservableJsonKeyOrder } from "./current-witness";

type Path = Array<string | number>;
const rawNumber = (value: unknown) => (JSON as typeof JSON & { isRawJSON(value: unknown): boolean }).isRawJSON(value);
const prefix = (path: Path, parent: Path) => parent.length <= path.length && parent.every((value, index) => value === path[index]);
const at = (value: any, path: Path) => { for (const part of path) { if (value === null || typeof value !== "object" || !Object.hasOwn(value, part)) return undefined; value = value[part]; } return value; };

/** Each member needs its own present, equal typed output path; entering its owner grants nothing. */
export async function apiMemberValues(jobs: ApiJob[], expected: any[], actual: any[], inventory: CurrentInventory, build: Pick<ApiBuildProof, "sourceRepository" | "driverSources">): Promise<CoreValueWitness[]> {
  const mainProof = build.driverSources.find(proof => proof.path.endsWith("/src/main.rs")); assert.ok(mainProof);
  const main = await readFile(mainProof.path, "utf8"); assert.equal(sha256(main), mainProof.sha256);
  const sources = new Map<string, Buffer>(), values = new Map<string, CoreValueWitness>();
  const source = async (file: string) => {
    if (!sources.has(file)) { const read = await capturedCommand(["git", "-C", build.sourceRepository, "show", `${inventory.sourceRevision}:${file}`], import.meta.dir); assert.equal(read.exitCode, 0); const bytes = Buffer.from(read.stdout); assert.equal(sha256(bytes), inventory.files.find(candidate => candidate.path === file)?.sha256); sources.set(file, bytes); }
    return sources.get(file)!;
  };
  const declarations = inventory.units.filter(unit => ["struct_item", "enum_item"].includes(unit.kind));
  const imports = (text: string): Map<string, string[]> => {
    const result = new Map<string, string[]>();
    const items = (body: string, base = "") => {
      let depth = 0, start = 0; const parts: string[] = [];
      for (let index = 0; index < body.length; index++) { if (body[index] === "{") depth++; if (body[index] === "}") depth--; if (body[index] === "," && depth === 0) { parts.push(body.slice(start, index)); start = index + 1; } } parts.push(body.slice(start));
      for (const raw of parts) { const part = raw.trim(), brace = part.indexOf("{"); if (brace >= 0) { items(part.slice(brace + 1, part.lastIndexOf("}")), base + part.slice(0, brace)); continue; }
        const [path, renamed] = part.split(/\s+as\s+/); if (!path) continue; const target = base + path, alias = renamed ?? target.split("::").at(-1)!; result.set(alias, [...result.get(alias) ?? [], target]); }
    };
    for (const use of text.matchAll(/\buse\s+([^;]+);/g)) items(use[1]!);
    return result;
  };
  const mainImports = imports(main);
  const resolveType = async (name: string, fromFile?: string, visited = new Set<string>()): Promise<SourceUnit | undefined> => {
    const visit = `${fromFile ?? "driver"}:${name}`; if (visited.has(visit)) return undefined; const seen = new Set(visited); seen.add(visit);
    const short = name.split("::").at(-1)!; let candidates = declarations.filter(unit => unit.label === short);
    if (fromFile && !name.includes("::")) {
      const local = candidates.filter(unit => unit.file === fromFile); if (local.length === 1) return local[0];
      for (const target of imports((await source(fromFile)).toString()).get(name) ?? []) { const found = await resolveType(target, fromFile, seen); if (found) return found; }
    } else if (!name.includes("::")) for (const target of mainImports.get(name) ?? []) { const found = await resolveType(target, undefined, seen); if (found) return found; }
    if (name.startsWith("codex_protocol::models::")) candidates = candidates.filter(unit => unit.file === "codex-rs/protocol/src/models.rs");
    else if (name.startsWith("codex_protocol::openai_models::")) candidates = candidates.filter(unit => unit.file === "codex-rs/protocol/src/openai_models.rs");
    else if (name.startsWith("codex_protocol::")) candidates = candidates.filter(unit => unit.file.startsWith("codex-rs/protocol/"));
    else if (name.startsWith("codex_code_mode::") || name.startsWith("codex_code_mode_protocol::")) candidates = candidates.filter(unit => unit.file.startsWith("codex-rs/code-mode-protocol/"));
    else if (name.startsWith("codex_tools::")) candidates = candidates.filter(unit => unit.file.startsWith("codex-rs/tools/"));
    if (!candidates.length && /^codex_\w+::/.test(name)) {
      const parts = name.split("::"), crate = parts[0]!.slice(6).replaceAll("_", "-"), module = parts.slice(1, -1).join("/"), base = `codex-rs/${crate}/src/`;
      const moduleFile = inventory.files.find(file => [base + (module || "lib") + ".rs", base + module + "/mod.rs"].includes(file.path));
      if (moduleFile) {
        const paths = imports((await source(moduleFile.path)).toString());
        for (const imported of [...paths.get(short) ?? [], ...(paths.get("*") ?? []).map(path => path.replace(/\*$/, short))]) {
          const target = /^codex_\w+::/.test(imported) ? imported : imported.startsWith("crate::") ? parts[0] + imported.slice(5) : parts[0] + "::" + imported;
          const found = await resolveType(target, moduleFile.path, seen); if (found) return found;
        }
      }
    }
    return candidates.length === 1 ? candidates[0] : undefined;
  };
  const children = (owner: SourceUnit, kinds: string[]) => inventory.units.filter(unit => unit.file === owner.file && unit.start > owner.start && unit.end < owner.end && kinds.includes(unit.kind) &&
    !inventory.units.some(parent => parent.file === owner.file && parent.id !== owner.id && ["struct_item", "enum_item", "enum_variant"].includes(parent.kind) && parent.start > owner.start && parent.start < unit.start && parent.end > unit.end && parent.end < owner.end));
  const attributes = async (unit: SourceUnit, owner?: SourceUnit) => {
    const bytes = await source(unit.file), before = inventory.units.filter(previous => previous.file === unit.file && previous.end <= unit.start && previous.start >= (owner?.start ?? 0)).reduce((end, previous) => Math.max(end, previous.end), owner?.start ?? 0);
    return bytes.subarray(before, unit.start).toString();
  };
  const rename = (name: string, attributes: string, all = "") => attributes.match(/\brename\s*=\s*"([^"]+)"/)?.[1] ??
    (all === "lowercase" ? name.toLowerCase() : all === "snake_case" ? name.replace(/[A-Z]/g, (char, index) => (index ? "_" : "") + char.toLowerCase()) : name);
  for (const [index, job] of jobs.entries()) {
    assert.equal(job.observations?.length ?? 0, 0, "manual API member claims cannot replace a derived typed value path");
    const emit = (unit: SourceUnit, path: Path) => {
      if (!prefix(path, job.reference_path)) return;
      const targetPath = [...job.candidate_path, ...path.slice(job.reference_path.length)], sourceValue = at(expected[index].result, path), targetValue = at(actual[index].result, targetPath);
      assert.notEqual(sourceValue, undefined); assert.deepEqual(targetValue, sourceValue); assertObservableJsonKeyOrder(targetValue, sourceValue);
      if (!values.has(unit.id)) values.set(unit.id, { id: unit.id, sha256: unit.sha256, relation: "typed-source-shape-v1", path: [job.id, ...path], valueSha256: sha256(JSON.stringify(sourceValue)) });
    };
    const walk = async (type: SourceUnit, value: any, path: Path, depth = 0): Promise<void> => {
      if (depth > 40 || value === null || value === undefined || rawNumber(value) || !prefix(path, job.reference_path) && !prefix(job.reference_path, path)) return;
      emit(type, path);
      let owner = type;
      if (type.kind === "enum_item") {
        const attrs = await attributes(type), tag = attrs.match(/\btag\s*=\s*"([^"]+)"/)?.[1], all = attrs.match(/\brename_all\s*=\s*"([^"]+)"/)?.[1];
        const discriminator = typeof value === "string" ? value : tag ? value[tag] : undefined;
        if (typeof discriminator !== "string") return;
        const matches: SourceUnit[] = [];
        for (const variant of children(type, ["enum_variant"])) if (rename(variant.label, await attributes(variant, type), all) === discriminator) matches.push(variant);
        if (matches.length !== 1) return;
        owner = matches[0]!; emit(owner, path);
      }
      if (typeof value !== "object" || Array.isArray(value)) return;
      for (const member of children(owner, ["field_declaration"])) {
        const key = rename(member.label, await attributes(member, owner)); if (!Object.hasOwn(value, key)) continue;
        const memberPath = [...path, key]; emit(member, memberPath);
        const text = (await source(member.file)).subarray(member.start, member.end).toString(); assert.equal(sha256(text), member.sha256);
        const typeText = text.slice(text.indexOf(":") + 1), names = [...typeText.matchAll(/(?:[A-Za-z_]\w*::)*[A-Z][A-Za-z_0-9]*/g)].map(match => match[0]);
        const resolved = await Promise.all(names.map(name => resolveType(name, member.file)));
        const nested = [...new Map(resolved.filter((unit): unit is SourceUnit => !!unit).map(unit => [unit.id, unit])).values()];
        if (nested.length !== 1) continue;
        const child = value[key];
        if (/\bVec\s*</.test(typeText) && Array.isArray(child)) for (const [childIndex, item] of child.entries()) await walk(nested[0]!, item, [...memberPath, childIndex], depth + 1);
        else if (/\b(?:BTreeMap|HashMap)\s*</.test(typeText) && child && typeof child === "object") for (const [childKey, item] of Object.entries(child)) await walk(nested[0]!, item, [...memberPath, childKey], depth + 1);
        else await walk(nested[0]!, child, memberPath, depth + 1);
      }
    };
    if (job.reference_input.operation === "protocol_shape" && job.reference_path[0] === "value") {
      const kind = job.reference_input.type; assert.equal(typeof kind, "string");
      const arm = main.match(new RegExp(`"${kind}"\\s*=>\\s*(?:roundtrip\\s*::<([^>]+)>\\s*\\(value\\)|match\\s+serde_json::from_value\\s*::<([^>]+)>\\s*\\(value\\.clone\\(\\)\\))`));
      const type = arm && await resolveType((arm[1] ?? arm[2])!.trim()); if (type) await walk(type, expected[index].result.value, ["value"]);
    }
    if (job.reference_input.operation === "protocol_shape" && job.reference_input.type === "output_content_item" && job.reference_path[0] === "adapted") {
      assert.ok(main.includes('"adapted":response_adapter::into_function_call_output_content_items(vec![item])'));
      const adapter = build.driverSources.find(proof => proof.path.endsWith("/src/response_adapter.rs")); assert.ok(adapter);
      const bytes = await readFile(adapter.path); assert.equal(sha256(bytes), adapter.sha256); assert.equal(sha256(bytes), inventory.files.find(file => file.path === "codex-rs/core/src/tools/code_mode/response_adapter.rs")?.sha256);
      const type = await resolveType("codex_protocol::models::FunctionCallOutputContentItem");
      if (type && Array.isArray(expected[index].result.adapted)) for (const [item, value] of expected[index].result.adapted.entries()) await walk(type, value, ["adapted", item]);
    }
    if (job.reference_input.operation === "model_metadata" && job.reference_path[0] === "models" && typeof job.reference_path[1] === "number") {
      const model = await resolveType("codex_protocol::openai_models::ModelInfo"); assert.ok(model);
      for (const [wire, field] of [["supports_original", "supports_image_detail_original"], ["use_responses_lite", "use_responses_lite"]]) if (job.reference_path.length === 3 && job.reference_path[2] === wire) {
        assert.ok(main.includes(`"${wire}":m.${field}`), "metadata projection no longer reads the actual source model field");
        const member: SourceUnit | undefined = children(model, ["field_declaration"]).find(unit => unit.label === field); assert.ok(member); emit(member, job.reference_path);
      }
      // This is the complete typed CodeMode message subtree, not the unrelated
      // model roster or prompt fields sharing ModelMessages/ModelInfo.
      const messagePath: Path = ["models", job.reference_path[1]!, "model_messages", "tools", "code_mode"], messages = await resolveType("codex_protocol::openai_models::CodeModeToolMessages");
      assert.ok(main.includes('"model_messages":m.model_messages')); if (messages && at(expected[index].result, messagePath) !== undefined) await walk(messages, at(expected[index].result, messagePath), messagePath);
    }
  }
  return [...values.values()].sort((a, b) => a.id.localeCompare(b.id));
}
