import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import type { CurrentInventory, SourceUnit } from "./current-inventory";
import type { FunctionWitness, InstrumentedSource } from "./current-witness";
import { sha256 } from "./qualification";

export type CoreValueWitness = {
  id: string; sha256: string; relation: "rendered-source-value-v1" | "typed-source-shape-v1";
  path: Array<string | number>; valueSha256: string; fragmentSha256?: string;
};
export async function originalCoreSources(manifest: { sources: InstrumentedSource[] }, inventory: CurrentInventory): Promise<Map<string, Buffer>> {
  const sources = new Map<string, Buffer>();
  for (const entry of manifest.sources) if (entry.sourceStart === 0) {
    const bytes = await readFile(entry.original.path);
    assert.equal(sha256(bytes), inventory.files.find(file => file.path === entry.sourceFile)?.sha256);
    sources.set(entry.sourceFile, bytes);
  }
  return sources;
}
const at = (value: any, path: Array<string | number>): any => path.reduce((value, part) => value?.[part], value);
function literal(text: string): string | undefined {
  const raw = text.match(/=\s*r(#{0,16})"([\s\S]*)"\1\s*;/);
  if (raw) return raw[2];
  const quoted = text.match(/=\s*("(?:[^"\\]|\\.)*")\s*;/);
  return quoted ? JSON.parse(quoted[1]!) : undefined;
}
/**
 * Each value witness names an exact observed value or schema member. Entering a
 * parent function alone never credits its constants, fields, variants or children.
 * Unknown declarations receive no witness and remain required in the ledger.
 */
export function coreValueWitnesses(input: any, result: any, functions: FunctionWitness[], inventory: CurrentInventory, sources: Map<string, Buffer>): CoreValueWitness[] {
  const values: CoreValueWitness[] = [], entered = new Set(functions.map(item => item.id));
  const units = inventory.units.filter(unit => sources.has(unit.file));
  const text = (unit: SourceUnit) => {
    const bytes = sources.get(unit.file)!.subarray(unit.start, unit.end);
    assert.equal(sha256(bytes), unit.sha256); return bytes.toString();
  };
  const add = (unit: SourceUnit, relation: CoreValueWitness["relation"], path: CoreValueWitness["path"], fragment?: string) => {
    const value = at(result, path); if (value === undefined) return;
    if (fragment !== undefined && (typeof value !== "string" || !fragment.length || !value.includes(fragment))) return;
    text(unit);
    const witness: CoreValueWitness = { id: unit.id, sha256: unit.sha256, relation, path, valueSha256: sha256(JSON.stringify(value)), ...(fragment === undefined ? {} : { fragmentSha256: sha256(fragment) }) };
    if (!values.some(item => JSON.stringify(item) === JSON.stringify(witness))) values.push(witness);
  };
  const descriptionFile = "codex-rs/code-mode-protocol/src/description.rs";
  const descriptionUnits = units.filter(unit => unit.file === descriptionFile);
  const constant = (name: string) => descriptionUnits.find(unit => unit.kind === "const_item" && unit.label === name);
  const rawConstant = (name: string) => { const unit = constant(name); return unit ? literal(text(unit)) : undefined; };
  const described = input.operation === "describe" && descriptionUnits.some(unit => unit.label === "build_exec_tool_description" && entered.has(unit.id)) && typeof result.description === "string";
  if (described) {
    const options = input.options ?? {}, messages = options.messages ?? {}, path = ["description"];
    for (const name of ["DEFERRED_NESTED_TOOLS_GUIDANCE", "TOOL_SEARCH_GUIDANCE", "LEGACY_IMAGE_HELPER_DESCRIPTION", "UNIFIED_IMAGE_HELPER_DESCRIPTION", "MCP_TYPESCRIPT_PREAMBLE"]) {
      const unit = constant(name), fragment = rawConstant(name);
      if (!unit || !fragment) continue;
      const defaultSource = name === "MCP_TYPESCRIPT_PREAMBLE" ? messages.mcp_preamble == null && options.code_mode_only !== false
        : /IMAGE_HELPER/.test(name) ? messages.exec_description == null : messages.deferred_guidance == null;
      if (defaultSource) add(unit, "rendered-source-value-v1", path, fragment);
      if (name === "MCP_TYPESCRIPT_PREAMBLE" && defaultSource && result.description.includes(fragment)) {
        for (const member of descriptionUnits.filter(member => member.kind.startsWith("typescript-") && unit.start <= member.start && member.end <= unit.end)) add(member, "rendered-source-value-v1", path, text(member));
      }
    }
    const template = constant("EXEC_DESCRIPTION_TEMPLATE"), helper = rawConstant(options.image_detail_visible === false ? "UNIFIED_IMAGE_HELPER_DESCRIPTION" : "LEGACY_IMAGE_HELPER_DESCRIPTION");
    if (template && helper && messages.exec_description == null) {
      const delay = options.default_yield_time_ms ?? 30000;
      const rendered = rawConstant("EXEC_DESCRIPTION_TEMPLATE")!.replaceAll("{{ default_exec_yield_time_ms }}", typeof delay === "object" && delay !== null && "rawJSON" in delay ? delay.rawJSON : String(delay)).replaceAll("{{ image_helper }}", helper);
      add(template, "rendered-source-value-v1", path, rendered);
      for (const [owner, selected] of [["ImageDetailVisibility", options.image_detail_visible === false ? "Hidden" : "Visible"], ["DeferredToolDiscovery", options.discovery === "ranked_search" ? "RankedSearch" : "Catalog"]]) {
        const declaration = descriptionUnits.find(unit => unit.kind === "enum_item" && unit.label === owner);
        const variant = descriptionUnits.find(unit => unit.kind === "enum_variant" && unit.label === selected && declaration && declaration.start <= unit.start && unit.end <= declaration.end);
        const discriminator = owner === "ImageDetailVisibility" ? helper : options.discovery === "ranked_search" && options.code_mode_only === false && messages.deferred_guidance == null ? rawConstant("TOOL_SEARCH_GUIDANCE") : selected === "Catalog" && messages.deferred_guidance == null ? rawConstant("DEFERRED_NESTED_TOOLS_GUIDANCE") : undefined;
        if (declaration && variant && discriminator && result.description.includes(discriminator)) { add(declaration, "rendered-source-value-v1", path, discriminator); add(variant, "rendered-source-value-v1", path, discriminator); }
      }
    }
  }
  const typedFile = "codex-rs/tools/src/json_schema/types.rs";
  const schemaUnits = units.filter(unit => unit.file === typedFile);
  if (input.operation === "schema_projection" && result.schema !== null && typeof result.schema === "object") {
    const schemaType = schemaUnits.find(unit => unit.kind === "struct_item" && unit.label === "JsonSchema");
    if (schemaType) {
      add(schemaType, "typed-source-shape-v1", ["schema"]);
      const fields = schemaUnits.filter(unit => unit.kind === "field_declaration" && schemaType.start <= unit.start && unit.end <= schemaType.end).sort((a,b) => a.start-b.start);
      for (let index=0; index<fields.length; index++) {
        const field=fields[index]!, prefix=sources.get(typedFile)!.subarray(index ? fields[index-1]!.end : schemaType.start,field.start).toString();
        if (/#\[serde\(skip\)\]/.test(prefix)) continue;
        const name=text(field).match(/(?:pub\s+)?(\w+)\s*:/)?.[1], wire=prefix.match(/rename\s*=\s*"([^"]+)"/)?.[1] ?? name;
        if (wire && Object.hasOwn(result.schema,wire)) add(field,"typed-source-shape-v1",["schema",wire]);
      }
    }
    const walk=(schema:any,path:Array<string|number>)=>{
      if (!schema || typeof schema!=="object" || Array.isArray(schema)) return;
      for(const [enumName,wire] of [["JsonSchemaType","type"],["JsonSchemaPrimitiveType","type"],["AdditionalProperties","additionalProperties"]]) {
        if (!Object.hasOwn(schema,wire!)) continue;
        const declaration=schemaUnits.find(unit=>unit.kind==="enum_item"&&unit.label===enumName), value=schema[wire!];
        if (!declaration) continue;
        const variants=schemaUnits.filter(unit=>unit.kind==="enum_variant"&&declaration.start<=unit.start&&unit.end<=declaration.end);
        for(const variant of variants) {
          const name=text(variant).match(/^\s*(\w+)/)?.[1];
          const matches=enumName==="JsonSchemaPrimitiveType" ? (Array.isArray(value)?value:[value]).includes(name?.toLowerCase())
            : enumName==="JsonSchemaType" ? name==="Single" ? typeof value==="string" : name==="Multiple"&&Array.isArray(value)
            : name==="Boolean" ? typeof value==="boolean" : name==="Schema"&&value!==null&&typeof value==="object"&&!Array.isArray(value);
          if(matches){add(declaration,"typed-source-shape-v1",[...path,wire!]);add(variant,"typed-source-shape-v1",[...path,wire!]);
            for(const payload of schemaUnits.filter(unit=>unit.kind==="ordered_field_declaration_list"&&variant.start<=unit.start&&unit.end<=variant.end))add(payload,"typed-source-shape-v1",[...path,wire!]);}
        }
      }
      for(const [key,value]of Object.entries(schema)) if(value&&typeof value==="object") {
        if (["properties","definitions","$defs"].includes(key)) for(const [name,nested]of Object.entries(value))walk(nested,[...path,key,name]);
        else if(["anyOf","oneOf","allOf"].includes(key)&&Array.isArray(value))value.forEach((nested,index)=>walk(nested,[...path,key,index]));
        else if(["items","additionalProperties"].includes(key))walk(value,[...path,key]);
      }
    };walk(result.schema,["schema"]);
  }
  if (["prepare","augment","collect"].includes(input.operation)&&Array.isArray(result.prepared)) {
    for(let index=0;index<result.prepared.length;index++) {
      const row=result.prepared[index], definition=descriptionUnits.find(unit=>unit.kind==="struct_item"&&unit.label==="ToolDefinition");
      if(definition) {
        add(definition,"typed-source-shape-v1",["prepared",index]);
        for(const field of descriptionUnits.filter(unit=>unit.kind==="field_declaration"&&definition.start<=unit.start&&unit.end<=definition.end)){
          const name=text(field).match(/pub\s+(\w+)\s*:/)?.[1];
          // The harness deliberately strips both schemas after the actual renderer.
          // Their null placeholders cannot prove either source field or its budget.
          if(name&&["name","tool_name","description","kind"].includes(name)&&Object.hasOwn(row,name))add(field,"typed-source-shape-v1",["prepared",index,name]);
        }
      }
      const kind=descriptionUnits.find(unit=>unit.kind==="enum_item"&&unit.label==="CodeModeToolKind");
      if(kind)for(const variant of descriptionUnits.filter(unit=>unit.kind==="enum_variant"&&kind.start<=unit.start&&unit.end<=kind.end))if(row.kind===variant.label.toLowerCase()){add(kind,"typed-source-shape-v1",["prepared",index,"kind"]);add(variant,"typed-source-shape-v1",["prepared",index,"kind"]);}
    }
  }
  return values.sort((a,b)=>a.id.localeCompare(b.id)||JSON.stringify(a.path).localeCompare(JSON.stringify(b.path)));
}

