# Scaffolder — Block 4 Execution Spec

Generate production-ready AI SDK v6 tool and agent definitions from a
partition. Every tool gets literal Valibot code. Every agent gets typed
configuration. This block produces the PLAN that constrains all downstream
implementation. Every field must contain code, not descriptions.

## Inputs

- **agents**: Agent hierarchy from PARTITION (names, roles, concern IDs,
  cohesion scores)
- **concerns**: All concerns from Block 1 (with types, reads, writes,
  invariants)
- **sharedTypes**: Data types that flow between concerns
- **modularity**: Global Q score from the partition

## AI SDK v6 Invariants — Violations = Build Failure

| ID | Rule |
|----|------|
| I-1 | Agent is `ToolLoopAgent`. Not a custom class. |
| I-2 | Tools use `inputSchema` via `valibotSchema()`. Not `parameters`. |
| I-3 | `generateText` + `Output`. No `generateObject`. |
| I-5 | Config on constructor + `prepareCall` + `prepareStep`. |
| I-6 | `Output.object()`, `Output.array()`, `Output.choice()`, `Output.text()`. |
| I-7 | `stopWhen: stepCountIs()` or `hasToolCall()`. Not `maxSteps`. |
| I-8 | Tools are stateless. Inputs in, outputs out. |
| I-9 | Provider normalization in middleware via `wrapLanguageModel`. |
| I-10 | `abortSignal` propagation to all subagent `.generate()` calls. |

## Output Mapping — Apply BEFORE Generating (Deterministic)

Run `resolveOutputStrategy()` on every concern. Show the mapping table in
your output.

| Concern Type | Output Variant |
|-------------|---------------|
| validator | `Output.object({ schema: passFailSchema })` |
| policy | `Output.choice({ options })` |
| query (writes array) | `Output.array({ element })` |
| query (writes single) | `Output.object({ schema })` |
| transformer | `Output.object({ schema })` |
| command | `Output.text()` |
| adapter_in | `Output.object({ schema })` |
| adapter_out | `Output.text()` |
| event | `Output.text()` |
| domain | inferred from outputFields |

## Execution

### Step 1: Generate Tool Definitions

For each concern, produce a tool definition:

1. **name**: Same as concern ID.
2. **description**: For the LLM — when and how to use this tool. Be specific.
3. **inputSchemaCode**: Literal Valibot schema string. Use `v.object()`,
   `v.pipe()`, `v.description()`. This MUST be valid Valibot. Not a
   description. Not Zod. Literal code.
4. **outputSchemaCode**: Literal Valibot schema for the Output wrapper.
5. **executeSignature**: Typed function signature:
   `async ({ field1, field2 }, { abortSignal }) => { /* TODO */ }`
6. **outputStrategy**: From the deterministic mapping above.
7. **toModelOutputCode**: If tool returns >500 tokens, write a compression
   function.
8. **strict**: `true` for validators and commands.
9. **inputExamples**: 1-2 concrete examples for complex schemas.

### Step 1b: Detect Custom Output Needs — MANDATORY

Run ALL 5 rules on EVERY concern. Show the results.

| Rule | Trigger |
|------|---------|
| 1 | outputFields include `code`, `schema`, or `template` type → custom parser |
| 2 | Transformer with variable output format → fallback chain parser |
| 3 | Returns structured data + prose summary → composite parser |
| 4 | Generates domain syntax (SQL, regex, Three.js, Valibot) → domain parser |
| 5 | Output requires external validation → async validator |

When custom Output is needed:
- Set `outputStrategy: "custom"`.
- Produce `customOutputCode` with the full `{ type, responseFormat,
  parseOutput }` implementation.
- Produce `customOutputReason` explaining why built-in is insufficient.

### Step 2: Generate Agent Definitions

For each partition, produce an agent definition. The partition already
respects hyperedges — concerns bound by a hyperedge are GUARANTEED to be in
the same agent.

- If an agent contains `shared_transaction` hyperedge concerns, mention the
  atomic transaction requirement in agent instructions.
- If an agent has `shared_system` hyperedge, consider shared connection pool
  in prepareCall.

For each agent:

1. **name**: From partition.
2. **role**: One sentence.
3. **modelTier**: By cognitive shape:
   - `haiku`: Pattern matching, classification, simple extraction
   - `sonnet`: Pathfinding, code generation, multi-step reasoning
   - `opus`: Full structural analysis, complex architectural decisions
4. **tools**: Tool names assigned by the partition.
5. **instructions**: "Loaded from agents/{name}.md" — system prompts are pure
   functions of role + tools. No hardcoded data. No inline strings in .ts.
6. **stopCondition**: `hasToolCall("done")` for open-ended,
   `stepCountIs(N)` for pipelines.
7. **subAgents**: If partition has sub-partitions, wire as tools on parent.
8. **prepareStepCode**: Literal function body for model routing, tool gating,
   context compression.
9. **prepareCallCode**: Literal function body if agent accepts runtime options.
10. **callOptionsSchemaCode**: Literal Valibot wrapped in `valibotSchema()`.
    EVERY agent gets this. Not just the orchestrator.
11. **telemetryIntegrationCode**: Full `TelemetryIntegration` class using
    `bindTelemetryIntegration`. EVERY agent gets this.

### Step 3: Identify Middleware Requirements

Scan the architecture for:
- Multiple providers → `provider_normalization` middleware
- Database/API auth → `guardrail` middleware
- Cost tracking → `logging` middleware (or devToolsIntegration)
- Caching → `caching` middleware
- Rate limiting → `rate_limiting` middleware
- Open-source models → `extractReasoningMiddleware` (MANDATORY)

### Step 4: Define Telemetry Hooks

For each agent, specify which lifecycle callbacks:
- `onStart`: Log prompt, model, functionId
- `onStepStart`: Log step number, active tools
- `onToolCallStart`: Log tool name, input size estimate
- `onToolCallFinish`: Log duration, success/error, output size
- `onStepFinish`: Log tokens, finish reason, cost estimate
- `onFinish`: Log totals

### Step 5: Cross-Check

Verify ALL of these before producing output:
- Every concern has exactly one tool
- Every tool is assigned to exactly one agent
- Every agent has ≥1 tool and ≤7 tools
- Every subagent tool propagates `abortSignal` (I-10)
- No tool uses `parameters` (must be `inputSchema`)
- No agent uses `maxSteps` (must be `stopWhen`)
- All structured output uses `Output.*` (no `generateObject`)
- Every agent has `experimental_repairToolCall: true`
- `extractReasoningMiddleware` present for open-source models

## Output Format

Full scaffold JSON per `references/scaffold-format.md`.

## OUTPUT GATE — All must pass or output is INVALID

- [ ] `resolveOutputStrategy()` mapping table shown for EVERY concern
- [ ] All 5 custom Output detection rules run — results shown per concern
- [ ] Every `inputSchemaCode` contains literal `v.object({...})` — if it
  contains a description instead of code, you have reverted to training
- [ ] Every `inputSchemaCode` uses Valibot. ZERO `z.object()` anywhere.
- [ ] Every tool has `outputSchemaCode` or `customOutputCode`
- [ ] Every tool has `executeSignature` with typed parameters
- [ ] Every agent has `callOptionsSchemaCode` containing literal
  `valibotSchema(v.object({...}))`
- [ ] Every agent has `prepareCallCode` and `prepareStepCode`
- [ ] Every agent has `telemetryIntegrationCode`
- [ ] `biomeConfig` section present
- [ ] Cross-check in Step 5 passes

## FAIL CONDITIONS

These are from `references/review-rejections.md`, inlined here:

- **R-3**: Do NOT produce descriptions instead of code. If `inputSchemaCode`
  doesn't contain literal `v.object({...})`, you have reverted to training.
  Training produces recommendations. This skill produces code.
- **R-5**: Do NOT use Zod. Every schema is Valibot. `z.object()` in scaffold
  output = violation.
- **R-6**: Do NOT skip the Output mapping. Apply `resolveOutputStrategy()` to
  every concern BEFORE generating. Show the mapping table.
- **R-7**: Do NOT skip custom Output detection. Run all 5 rules on every
  concern. Show results.
- **R-11**: If the output looks like prose recommendations ("consider adding
  callOptionsSchema", "should use Valibot"), it is training output. The
  scaffold is a typed JSON with literal code for every field. If yours isn't,
  start over.

## MANDATORY RULES

- Produce ACTUAL code, not descriptions. `inputSchemaCode` must be valid
  Valibot.
- One tool per concern. Never merge concerns into a single tool.
- System prompts are pure functions. No hardcoded data, no references to
  specific users or contexts. Loaded from .md files.
- `toModelOutput` is critical. If a tool returns >500 tokens, the model
  doesn't need all of it. Compress.
- Subagent tools MUST forward `abortSignal`. This is I-10. Check every one.
- Be defensive with `stopWhen`. Add `stepCountIs(20)` as safety bound even
  with `hasToolCall("done")`.
- Always include `biomeConfig`. Without biome constraints, agents produce
  monolithic 200-line functions. Default: `maxLinesPerFile: 300`,
  `maxLinesPerFunction: 50`, `maxCognitiveComplexity: 15`.
