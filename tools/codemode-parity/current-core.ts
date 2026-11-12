import assert from "node:assert/strict";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { candidateCommand } from "./candidate-command";
import { artifact, sha256 } from "./qualification";
import type { ExecutedVerifier } from "./current-coverage";
import type { CurrentReference } from "./current-readiness";
import type { CurrentInventory } from "./current-inventory";
import { extractTruncationPolicy } from "./formatter-cases";
import { resolveCurrentSource } from "./current-source";
import { coreCaseIdentity, instrumentRust, sourceWitnessRust, parseLosslessJson, assertObservableJsonKeyOrder, type InstrumentedSource } from "./current-witness";
import { capturedCommand } from "./current-command";
export { capturedCommand } from "./current-command";
import { assertRustDependencyClosure } from "./current-rust-build";
import { tokenizerFixtures, assertTokenizerCorpus } from "./current-tokenizer-fixtures";
import { coreValueWitnesses, originalCoreSources } from "./current-core-values";
import { runCurrentCoreApi } from "./current-core-api";
import { budgetBoundaryFixtures } from "./current-boundary-fixtures";
import { jsonCorpusFixtures, assertJsonCorpus } from "./current-json-fixtures";
import { assertMcpSearchRank, mcpSearchRankFixtures } from "./current-cli/mcp-search-cases";

export async function prepareCurrentCoreOracleSources(directory: string, lock: CurrentReference, inventory: CurrentInventory, save: (name: string, value: string | Uint8Array) => Promise<unknown>): Promise<void> {
    const sourceRepository = await resolveCurrentSource(lock, lock.sourceRepositoryDefault ?? undefined);
    const sources = [["description.rs", "codex-rs/code-mode-protocol/src/description.rs"], ["json_schema_types.rs", "codex-rs/code-mode-protocol/src/json_schema_types.rs"],
      ["tool_definitions.rs", "codex-rs/tools/src/code_mode.rs"], ["tool_name.rs", "codex-rs/protocol/src/tool_name.rs"],
      ["native-header.rs", "codex-rs/core/src/tools/code_mode/output.rs"], ["native-response-adapter.rs", "codex-rs/core/src/tools/code_mode/response_adapter.rs"],
      ["native-image-detail.rs", "codex-rs/tools/src/image_detail.rs"], ["native-string.rs", "codex-rs/utils/string/src/truncate.rs"], ["native-output.rs", "codex-rs/utils/output-truncation/src/lib.rs"],
      ["typed-schema.rs", "codex-rs/tools/src/json_schema/types.rs"]];
    const sourceBytes = new Map<string, Buffer>();
    const instrumentedSources: InstrumentedSource[] = [];
    const saveRust = async (target: string, sourceFile: string, original: Buffer, sourceStart = 0) => {
      const instrumented = instrumentRust(original, sourceFile, inventory, sourceStart);
      await save(`original-${target}`, original); await save(target, instrumented.bytes);
      instrumentedSources.push({ sourceFile, sourceFileSha256: inventory.files.find(file => file.path === sourceFile)!.sha256, sourceStart,
        original: await artifact(resolve(directory, `original-${target}`)), instrumented: await artifact(resolve(directory, target)), insertions: instrumented.insertions });
      return instrumented.bytes.toString();
    };
    for (const [target, source] of sources) {
      const result = await capturedCommand(["git", "-C", sourceRepository, "show", `${lock.sourceRevision}:${source}`], directory);
      assert.equal(result.exitCode, 0, result.stderr); await saveRust(target!, source!, Buffer.from(result.stdout)); sourceBytes.set(source!, Buffer.from(result.stdout));
    }
    const policySource = await capturedCommand(["git", "-C", sourceRepository, "show", `${lock.sourceRevision}:codex-rs/protocol/src/protocol.rs`], directory);
    assert.equal(policySource.exitCode, 0, policySource.stderr); await save("native-policy.rs", extractTruncationPolicy(policySource.stdout));
    const snippets: string[] = [];
    for (const name of ["handle_runtime_response", "format_script_status", "truncate_code_mode_result", "resolve_max_tokens", "DEFAULT_MAX_OUTPUT_TOKENS"]) {
      const unit = inventory.units.find(unit => unit.label === name && ["function_item", "const_item"].includes(unit.kind) && unit.file.startsWith("codex-rs/core/"));
      assert.ok(unit, `source dependency inventory omitted ${name}`);
      if (!sourceBytes.has(unit.file)) { const result = await capturedCommand(["git", "-C", sourceRepository, "show", `${lock.sourceRevision}:${unit.file}`], directory); assert.equal(result.exitCode, 0, result.stderr); sourceBytes.set(unit.file, Buffer.from(result.stdout)); }
      snippets.push(await saveRust(`native-function-${name}.rs`, unit.file, sourceBytes.get(unit.file)!.subarray(unit.start, unit.end), unit.start));
    }
    await save("native-response-functions.rs", snippets.join("\n\n") + "\n");
    const toolOutputFile = "codex-rs/tools/src/tool_output.rs";
    const toolOutputSource = await capturedCommand(["git", "-C", sourceRepository, "show", `${lock.sourceRevision}:${toolOutputFile}`], directory);
    assert.equal(toolOutputSource.exitCode, 0, toolOutputSource.stderr);
    await save("native-tool-output-full.rs", toolOutputSource.stdout);
    const toolOutputBytes = Buffer.from(toolOutputSource.stdout);
    const methods = inventory.units.filter(unit => unit.file === toolOutputFile && unit.kind === "function_item").map(unit => ({ unit, source: toolOutputBytes.subarray(unit.start, unit.end).toString() }));
    const contentMethod = methods.find(item => item.unit.label === "content_items_to_code_mode_result");
    const jsonMethod = methods.find(item => item.unit.label === "code_mode_result" && item.source.includes("self.value.clone()"));
    const mcpMethod = methods.find(item => item.unit.label === "code_mode_result" && item.source.includes('fields.remove("_meta")'));
    assert.ok(contentMethod && jsonMethod && mcpMethod, "source inventory omitted native tool-output conversion methods");
    await saveRust("native-content-result.rs", toolOutputFile, Buffer.from(contentMethod.source), contentMethod.unit.start);
    const jsonMethodSource = await saveRust("native-json-output-method.rs", toolOutputFile, Buffer.from(jsonMethod.source), jsonMethod.unit.start);
    const mcpMethodSource = await saveRust("native-mcp-output-method.rs", toolOutputFile, Buffer.from(mcpMethod.source), mcpMethod.unit.start);
    await save("native-output-value-impls.rs", `impl JsonOutputValue {\n${jsonMethodSource}\n}\nimpl McpOutputValue {\n${mcpMethodSource}\n}\n`);
    const waitFile = "codex-rs/core/src/tools/code_mode/wait_handler.rs";
    const waitSource = await capturedCommand(["git", "-C", sourceRepository, "show", `${lock.sourceRevision}:${waitFile}`], directory);
    assert.equal(waitSource.exitCode, 0, waitSource.stderr);
    const waitBytes = Buffer.from(waitSource.stdout), argumentSnippets: string[] = [];
    // The complete original guards member declarations; only invoked compiled snippets earn function witnesses.
    await saveRust("native-wait-handler-full.rs", waitFile, waitBytes);
    for (const name of ["ExecWaitArgs", "default_wait_yield_time_ms", "parse_arguments"]) {
      const unit = inventory.units.find(unit => unit.file === waitFile && unit.label === name && ["function_item", "struct_item"].includes(unit.kind));
      assert.ok(unit, `source inventory omitted wait argument helper ${name}`); argumentSnippets.push((name === "ExecWaitArgs" ? "#[derive(Debug,Deserialize)]\n" : "") + await saveRust(`native-argument-${name}.rs`, waitFile, waitBytes.subarray(unit.start, unit.end), unit.start));
    }
    await save("native-wait-arguments.rs", argumentSnippets.join("\n\n"));
    await save("current-arguments-oracle.rs", await readFile(resolve(import.meta.dir, "current-arguments-oracle.rs")));
    await save("current-format-oracle.rs", await readFile(resolve(import.meta.dir, "current-format-oracle.rs")));
    await save("main.rs", await readFile(resolve(import.meta.dir, "current-core-oracle.rs")));
    await save("Cargo.toml", await readFile(resolve(import.meta.dir, "current-core-oracle.toml")));
    const projectLock = await readFile(resolve(import.meta.dir, "current-core-oracle.lock"), "utf8"), officialLock = await capturedCommand(["git", "-C", sourceRepository, "show", `${lock.sourceRevision}:codex-rs/Cargo.lock`], directory);
    assert.equal(officialLock.exitCode, 0); assert.equal(sha256(officialLock.stdout), inventory.sourceCargoLockSha256);
    assertRustDependencyClosure(officialLock.stdout, projectLock);
    await save("Cargo.lock", projectLock); await save("official-Cargo.lock", officialLock.stdout);
    await save("source-witness.rs", sourceWitnessRust);
    await save("source-witness-manifest.json", JSON.stringify({ schema: 1, sourceRevision: lock.sourceRevision, instrumentation: "function-entry-insertions-v1", sources: instrumentedSources }, null, 2) + "\n");
}

type Fixture = Record<string, any>;
export function currentCoreFixtures(): Fixture[] {
  const vectors: Fixture[] = [];
  vectors.push(...budgetBoundaryFixtures());
  vectors.push(...mcpSearchRankFixtures());
  for (const type of ["string", "number", "boolean", "integer", "object", "array", "null"]) vectors.push({ operation: "schema_projection", schema: { type } });
  const schemaCases: any[] = [true, false, {}, { type: "string" }, { type: ["string", "null"] }, { enum: ["first", "λ", null, false, 2] },
    { const: { nested: ["x"] } }, { anyOf: [{ type: "integer" }, { type: "boolean" }] }, { oneOf: [{ type: "string" }, { type: "array" }] },
    { allOf: [{ anyOf: [{ type: "string" }, { type: "null" }] }, { type: "object" }] }, { type: "array", prefixItems: [{ type: "string" }, { type: "integer" }] },
    { type: "array", items: { type: "boolean" } }, { type: "object", properties: { "a-b": { type: "string", description: " two\n lines " }, z: { type: "number" } }, required: ["a-b"], additionalProperties: false },
    { type: "object", additionalProperties: { type: "number" } }, { $ref: "#/definitions/Result~1item~0v1", definitions: { "Result/item~v1": { type: "string" } } },
    { $ref: "#/$defs/node", $defs: { node: { type: "object", properties: { next: { $ref: "#/$defs/node" } } } } },
    { $ref: "https://example.invalid/external", type: "string" }, { $id: "nested", properties: { value: { $id: "inner", $ref: "#" } } },
    { $ref: "#/%24defs/a", $defs: { a: { type: "string" } }, type: "null" },
    { type: "object", properties: Object.fromEntries(Array.from({ length: 80 }, (_, index) => [`field_${index}`, { type: "string", description: "budget ".repeat(20) }])) },
  ];
  for (let index = 0; index < schemaCases.length; index++) for (const first of [false, true]) {
    const tool = { name: `fixture_${index}`, description: "Tool documentation.", input_schema: schemaCases[index], output_schema: schemaCases[schemaCases.length - index - 1], kind: "function" };
    vectors.push({ operation: "augment", tools: [tool], tool_description_first: first });
    vectors.push({ operation: "describe", tools: [tool], options: { code_mode_only: true } });
  }
  for (const namespace of [undefined, "mcp__fixture__"]) vectors.push({ operation: "describe",
    tools: ["z", "a", "z"].map(name => ({ name, namespace, namespace_description: " Namespace instructions. ", description: `Tool ${name}`, input_schema: { type: "object", properties: {} } })),
    options: { code_mode_only: true, messages: { exec_description: "", deferred_guidance: "", mcp_preamble: "" } } });
  const names = ["z", "a-b", "a_b", "λ雪", "9lead", "", "exec", "wait", "$valid", "leading__", "_private"];
  for (const first of [false, true]) vectors.push({ operation: "prepare", tool_description_first: first,
    tools: names.flatMap(name => [undefined, "functions", "mcp__fixture__", "edge_", "edge"].map(namespace => ({ name, namespace, description: `Tool ${name}`, namespace_description: " Namespace instructions. ", input_schema: { type: "object" }, kind: name === "_private" ? "freeform" : "function" }))) });
  for (const code_mode_only of [false, true]) for (const image_detail_visible of [false, true]) for (const discovery of ["catalog", "ranked_search"]) for (const messages of [undefined, { exec_description: "", deferred_guidance: "", mcp_preamble: "" }, { exec_description: "Yield {{ default_exec_yield_time_ms }} / {{ image_helper }}", deferred_guidance: "Custom discovery.", mcp_preamble: "type Sample = string;" }]) {
    vectors.push({ operation: "describe", tools: [], deferred: true, options: { code_mode_only, image_detail_visible, discovery, default_yield_time_ms: 1234, messages } });
  }
  const specs = [{ type: "function", name: "alpha", description: "Top-level", parameters: { type: "object", properties: { query: { type: "string" } }, required: ["query"] } },
    { type: "namespace", name: "mcp__fixture__", description: "Namespace guidance", tools: [{ type: "function", name: "search", description: "Search documents", parameters: { type: "object" }, output_schema: { type: "object", properties: { content: { type: "array" }, structuredContent: { type: "string" } }, required: ["content"] } }, { type: "custom", name: "raw", description: "Raw input" }] },
    { type: "tool_search", parameters: { type: "object", properties: { query: { type: "string" }, limit: { type: "integer" } }, required: ["query"] } }, { type: "web_search" }, { type: "function", name: "exec" }, { type: "function", name: "wait" }];
  for (const first of [false, true]) vectors.push({ operation: "collect", specs, tool_description_first: first });
  for (const budget of [0, 1, 16000, 24000]) vectors.push({ operation: "prepare", code_mode_input_schema_max_bytes: budget, tools: [{ name: "budget", description: "Budget", input_schema: schemaCases.at(-1), input_schema_max_bytes: budget }] });
  const tools = [{ name: "read_file", description: "Read repository file contents" }, { name: "search_files", description: "Search repository documents by query" }, { name: "weather", description: "Weather city forecast" }, { name: "write_file", description: "Write repository file contents" }].map(tool => ({ ...tool, input_schema: { type: "object" } }));
  for (const query of ["read file", "repository contents", "WEATHERS", "irrelevant", "the and of", "école 汉字 read", "read read read"]) {
    const entries = tools.map(tool => ({ tools: [tool], search_text: `${tool.name} ${tool.description}` }));
    for (const limit of [1, 2, 8, 101]) vectors.push({ operation: "search", entries, arguments: { query, limit } });
    vectors.push({ operation: "search", entries, allowed_names: ["search_files"], arguments: { query } });
    vectors.push({ operation: "rank", corpus: tools.map(tool => `${tool.name} ${tool.description}`), query });
  }
  const contentCases = [[], [{ type: "input_text", text: "🙂λ\n".repeat(100) }],
    ["auto", "low", "high", "original", null].map(detail => ({ type: "input_image", image_url: "data:image/png;base64,aGVsbG8=", detail }))];
  for (const status of ["yielded", "terminated", "result"]) for (const host_duration_ns of [undefined, "0", "62500000", "1000500000", "18446744073709551615"]) for (const supports_original of [false, true]) for (const show_cell_overhead of [false, true]) {
    for (const [index, content] of contentCases.entries()) vectors.push({ operation: "format", status, cell_id: "fixture-cell", error: status === "result" && index === 1 ? "fixture-source-error" : undefined,
      content, host_duration_ns, supports_original, show_cell_overhead, wall_millis: 350, handler_duration_millis: host_duration_ns === "62500000" ? 62 : 1000, max_output_tokens: index === 1 ? 20 : 10000 });
  }
  const payloads = [null, true, 3, "plain stdout", [1, "λ"], { _meta: { private: "keep generic" }, content: [{ type: "text", text: "kept", _meta: { nested: "retained" } }], structuredContent: { value: true }, isError: true }];
  const toolContent = [{ type: "input_text", text: " " }, { type: "input_text", text: "retained\n" }, { type: "input_image", file_id: "file-fixture" },
    { type: "input_image", image_url: "data:image/png;base64,aGVsbG8=" }, { type: "input_audio", audio_url: "data:audio/wav;base64,aGVsbG8=" }, { type: "encrypted_content", encrypted_content: "opaque" }];
  for (const disposition of ["tool_output", "reject"]) for (const is_error of [false, true]) {
    for (const payload of payloads) vectors.push({ operation: "tool_output", origin: "generic", disposition, is_error, payload });
    vectors.push({ operation: "tool_output", origin: "mcp", disposition, is_error, payload: payloads.at(-1) });
    vectors.push({ operation: "tool_output", disposition, is_error, content: toolContent });
    vectors.push({ operation: "tool_output", disposition, is_error, output: '{"keep":"literal text"}' });
  }
  for (const payload of [{ _meta: { private: true }, alpha: 1, beta: 2 }, { alpha: 1, _meta: { private: true }, beta: 2 }, { alpha: 1, beta: 2, _meta: { private: true } }]) {
    for (const origin of ["mcp", "generic"]) vectors.push({ operation: "tool_output", origin, disposition: "tool_output", is_error: false, payload });
  }
  const directives = ["", "{}", "null", "[]", '{"yield_time_ms":0}', '{"max_output_tokens":0}', '{"yield_time_ms":1,"yield_time_ms":2}',
    '{"yield_time_ms":null}', '{"max_output_tokens":null}', '{"yield_time_ms":-1}', '{"yield_time_ms":1.5}', '{"yield_time_ms":1e3}',
    '{"yield_time_ms":"3"}', '{"max_output_tokens":false}', '{"yield_time_ms":9007199254740991}', '{"yield_time_ms":9007199254740992}',
    '{"max_output_tokens":18446744073709551615}', '{"yield_time_ms":18446744073709551616}', '{"first":1,"another":2}', '{"yield_time_ms":', '{"yield_time_ms":2,}'];
  for (const directive of directives) vectors.push({ operation: "exec_arguments", source: `// @exec: ${directive}\ntext('λ');` });
  for (const source of ["", " \n\t", "text('raw');", "// @exec: {}", "\n// @exec: {}\ntext('later');", "   // @exec: {}\r\ntext('after');", "// @exec:\n", "// @exec: {}\n\t"]) vectors.push({ operation: "exec_arguments", source });
  vectors.push({ operation: "exec_arguments", source: "text('configured');", default_yield_time_ms: 1 });
  const waitArguments = ['{}', '{"cell_id":""}', '{"cell_id":"λ"}', '{"cell_id":"a","cell_id":"b"}', '{"cell_id":null}', '{"cell_id":7}', '{"cell_id":true}',
    '{"cell_id":"a","yield_time_ms":0}', '{"cell_id":"a","yield_time_ms":null}', '{"cell_id":"a","yield_time_ms":1e3}', '{"cell_id":"a","yield_time_ms":-1}',
    '{"cell_id":"a","yield_time_ms":18446744073709551615}', '{"cell_id":"a","yield_time_ms":18446744073709551616}', '{"cell_id":"a","max_tokens":null}',
    '{"cell_id":"a","max_tokens":-1}', '{"cell_id":"a","max_tokens":1.5}', '{"cell_id":"a","max_tokens":18446744073709551615}',
    '{"cell_id":"a","terminate":true}', '{"cell_id":"a","terminate":null}', '{"cell_id":"a","terminate":1}', '{"cell_id":"a","extra":{"a":1}}',
    '["a"]', '["a",1,null,true]', '["a",1,null,true,2]', '[]', 'null', '{"cell_id":"a",}', '{"cell_id":', '{"cell_id":"a"} tail',
    '{\n "cell_id":"λ",\n "yield_time_ms":false\n}', '{"cell_id":"escaped\\n\\u03bb"}', '{"cell_id":"a","yield_time_ms":1,"yield_time_ms":2}',
    '{"cell_id":"a","yield_time_ms":37,"max_tokens":19,"terminate":false}'];
  for (const arguments_ of waitArguments) vectors.push({ operation: "wait_arguments", arguments: arguments_ });
  vectors.push(...readFileSync(resolve(import.meta.dir, "current-schema-fixtures.jsonl"), "utf8").trimEnd().split("\n").map(line => { const { id: _id, ...body } = parseLosslessJson(line); return body; }));
  // Repeated identical inputs add no witness. Each named case must have exactly one raw row per side.
  return [...new Map(vectors.map(value => [coreCaseIdentity(value).caseId, { id: coreCaseIdentity(value).caseId, ...value }])).values()];
}

export async function runCurrentCore(java: string, jar: string, evidence: string, runId: string, lock: CurrentReference, inventory: CurrentInventory): Promise<ExecutedVerifier> {
  const execution: ExecutedVerifier = { id: "current.tools-and-description", runId, startedAt: new Date().toISOString(), finishedAt: "",
    inventorySha256: inventory.denominatorSha256, sourceRevision: lock.sourceRevision, candidateJarSha256: sha256(await readFile(jar)), nativeHostSha256: lock.nativeHostSha256,
    state: "failed", findings: [], artifacts: [] };
  const directory = resolve(evidence, `${runId}.current-core`); await mkdir(directory, { recursive: true });
  const save = async (name: string, value: string | Uint8Array) => { const path = resolve(directory, name); await writeFile(path, value); execution.artifacts.push(await artifact(path)); return path; };
  try {
    await prepareCurrentCoreOracleSources(directory, lock, inventory, save);
    let toolchain = await capturedCommand(["rustup", "run", "1.99.1", "rustc", "--version", "--verbose"], directory);
    if (toolchain.exitCode !== 0) {
      const bootstrap = await capturedCommand(["rustup", "toolchain", "install", "1.99.1", "--profile", "minimal"], directory, undefined, 180_000);
      await save("toolchain-bootstrap.json", JSON.stringify(bootstrap, null, 2) + "\n"); assert.equal(bootstrap.exitCode, 0, bootstrap.stderr);
      toolchain = await capturedCommand(["rustup", "run", "1.99.1", "rustc", "--version", "--verbose"], directory);
    }
    await save("toolchain.json", JSON.stringify(toolchain, null, 2) + "\n");
    assert.equal(toolchain.exitCode, 0); assert.match(toolchain.stdout, /release: 1\.99\.1\b/); assert.match(toolchain.stdout, /commit-hash: 58cf8f9c35b355199727627d07ae34a4e2846d67\b/);
    let build = await capturedCommand(["cargo", "+1.99.1", "build", "--offline", "--locked", "--manifest-path", resolve(directory, "Cargo.toml")], directory, undefined, 180_000);
    if (build.exitCode !== 0 && /no matching package|failed to download|attempting to make an HTTP request/i.test(build.stderr)) {
      await save("offline-cache-miss.json", JSON.stringify(build, null, 2) + "\n");
      const fetched = await capturedCommand(["cargo", "+1.99.1", "fetch", "--locked", "--manifest-path", resolve(directory, "Cargo.toml")], directory, undefined, 180_000);
      await save("dependency-bootstrap.json", JSON.stringify(fetched, null, 2) + "\n"); assert.equal(fetched.exitCode, 0, `locked Rust dependency bootstrap failed: ${fetched.stderr}`);
      build = await capturedCommand(["cargo", "+1.99.1", "build", "--offline", "--locked", "--manifest-path", resolve(directory, "Cargo.toml")], directory, undefined, 180_000);
    }
    await save("build.json", JSON.stringify(build, null, 2) + "\n"); assert.equal(build.exitCode, 0, build.stderr);
    const fixtures = [...currentCoreFixtures(), ...await tokenizerFixtures(save), ...jsonCorpusFixtures()];
    for (const fixture of fixtures) fixture.id = coreCaseIdentity(fixture).caseId;
    const uniqueFixtures = [...new Map(fixtures.map(fixture => [fixture.id, fixture])).values()];
    fixtures.splice(0, fixtures.length, ...uniqueFixtures);
    assertTokenizerCorpus(fixtures.filter(fixture => fixture.operation === "tokenize"));
    assertJsonCorpus(fixtures.filter(fixture => fixture.corpus?.startsWith("json-")));
    const input = fixtures.map(value => JSON.stringify(value)).join("\n") + "\n"; await save("input.jsonl", input);
    const reference = await capturedCommand([resolve(directory, "target/debug/splice-current-core-oracle")], directory, input, 180_000);
    const candidate = await capturedCommand(candidateCommand(java, jar, "--current-core-fixture", runId), directory, input, 180_000);
    await save("reference.json", JSON.stringify(reference, null, 2) + "\n"); await save("candidate.json", JSON.stringify(candidate, null, 2) + "\n");
    assert.equal(reference.exitCode, 0, reference.stderr); assert.equal(candidate.exitCode, 0, candidate.stderr);
    const expected = reference.stdout.trimEnd().split("\n").map(line => parseLosslessJson(line)), actual = candidate.stdout.trimEnd().split("\n").map(line => parseLosslessJson(line));
    assert.equal(expected.length, fixtures.length, "reference returned incomplete/duplicate fixture output"); assert.equal(actual.length, fixtures.length, "candidate returned incomplete/duplicate fixture output");
    for (let index = 0; index < fixtures.length; index++) {
      assert.equal(expected[index].id, fixtures[index]!.id); assert.equal(actual[index].id, fixtures[index]!.id); assert.equal(actual[index].nonce, runId);
      assert.deepEqual(actual[index].result, expected[index].result, `current production core disagreement at ${fixtures[index]!.id}`);
      assertMcpSearchRank(fixtures[index]!, expected[index].result);
      if (fixtures[index]!.operation === "tool_output") assertObservableJsonKeyOrder(actual[index].result.value, expected[index].result.value);
      if (fixtures[index]!.operation === "json_value" && Object.hasOwn(expected[index].result, "value")) assertObservableJsonKeyOrder(actual[index].result.value, expected[index].result.value);
    }
    const witnessArtifacts = { input: await artifact(resolve(directory, "input.jsonl")), reference: await artifact(resolve(directory, "reference.json")),
      candidate: await artifact(resolve(directory, "candidate.json")), sourceManifest: await artifact(resolve(directory, "source-witness-manifest.json")) };
    const originalSources = await originalCoreSources(JSON.parse(await readFile(witnessArtifacts.sourceManifest.path, "utf8")), inventory);
    execution.cases = fixtures.flatMap((fixture, index) => {
      const referenceFunctions = expected[index].source_witnesses ?? [], referenceValues = coreValueWitnesses(fixture, expected[index].result, referenceFunctions, inventory, originalSources);
      return referenceFunctions.length || referenceValues.length ? [{ ...coreCaseIdentity(fixture), referenceFunctions, referenceValues, ...witnessArtifacts }] : [];
    });
    const actualApi = await runCurrentCoreApi(java, jar, evidence, runId, lock, inventory);
    execution.artifacts.push(...actualApi.artifacts); execution.cases.push(...actualApi.cases); execution.findings.push(...actualApi.findings);
    assert.equal(actualApi.findings.length, 0, "actual-crate API production pairing did not complete");
    assert.equal(sha256(await readFile(jar)), execution.candidateJarSha256, "candidate jar changed during current core execution");
    await save("comparison.json", JSON.stringify({ schema: 2, runId, sourceRevision: lock.sourceRevision, fixtures: fixtures.length,
      semantics: "Exact ordered fixture IDs and typed JSON results; no last-value maps or output trimming beyond the JSONL separator. Source algorithms are unchanged; only wire/data-type shims are local.", mismatches: [] }, null, 2) + "\n");
    execution.state = "executed";
  } catch (error) { execution.findings.push(error instanceof Error ? error.message : String(error)); }
  execution.finishedAt = new Date().toISOString(); return execution;
}
