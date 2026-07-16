# Agent Architecture Patterns

Verification patterns for multi-block agent pipelines. This document defines
what correct architecture looks like in source code — how to verify it through
static analysis.

**Relationship to Block Tool Architecture**: `block-tool-architecture.md` in
torad-toolkit defines what blocks ARE — the membrane pattern, seven invariants,
data flow taxonomy. This document defines what to CHECK when auditing an agent
against those principles. The theory defines correctness at the conceptual
level; this doc defines how to observe correctness from source code. Where a
section maps to a specific theory invariant, the mapping is noted.

Two consumers:
- **build-agent**: follows these patterns when creating new agents
- **agent-audit Phase 2**: checks existing agents against these patterns

Each section covers one separable architectural concern. A block can have
correct architecture with poor tool schemas — they're independently checkable.

**Scope boundary with best-practices.md**: This document covers structural
correctness (is the architecture sound?). Best practices covers operational
efficiency (tool search, PTC, aggressive language, subagent specs). Phase 2
checks both — they are separate references for separate concerns.

---

## 1. Type Contracts

The contract is the single source of truth for all structural constraints.
Built deterministically from input data BEFORE any model runs. Every block
receives the contract; no block derives its own version.

### Rules

1. Build the contract from input parameters using pure functions (no AI).
2. The contract is authoritative — AI output is advisory when it conflicts.
3. Every field that QA checks must trace back to a contract field.
4. Blocks receive the contract as input; they do not re-derive constraints.

### Storybook Example

`contract.ts` exports `buildContract(child_age, tone)` — a pure function
that returns `StoryContract` with age-band-derived fields: `vocabulary_ceiling`,
`sentence_length_range`, `words_per_scene`, `scene_count`, `scene_type_counts`,
`scene_contracts`, and `layout_rules`. Called once in `runStorybookAgent()`
before Block 1 (Brief). Every subsequent block receives the contract object.
QA checks `words_per_scene` against `contract.scene_contracts[scene_type]`.

### Verification Checklist

| ID | Check | How to Verify |
|----|-------|---------------|
| A1.1 | Contract built before Block 1 | Find `buildContract()` or equivalent call in orchestrator, confirm it executes before first tool invocation |
| A1.2 | Contract is deterministic | Verify contract builder uses no AI calls, no `Math.random()`, no `Date.now()`, no external state — trace all called functions |
| A1.3 | Every QA-checked field traced to contract | For each QA check, find the corresponding contract field it validates against |
| A1.4 | Blocks receive contract, not derive it | Confirm blocks accept the contract as a parameter, not re-computing constraints from raw input |

---

## 2. Block Architecture

The pipeline is a sequence of discrete blocks with typed boundaries. Each
block has exactly one output tool. The tool schema enforces shape; the prompt
enforces quality.

**Theory mapping**: Invariants 4, 7. Block definitions are stable — name,
schema, and output shape do not change (Invariant 4). Block handlers are
stateless — inputs in, outputs out (Invariant 7). The distinction matters:
the *handler* (e.g., `handleWrite()`) is a pure function that returns typed
output. The *tool wrapper* (the MCP tool registration in the orchestrator)
manages state — checking `completedSteps`, calling the handler, mutating
`PipelineState`, returning a summary. Invariant 7 applies to the handler,
not the wrapper.

### Rules

1. Each block has exactly one output tool with a schema that defines the
   output shape.
2. A `completedSteps` Set (or equivalent) prevents re-execution of any block.
3. Dependency checks run before execution — a block returns an error if its
   prerequisites haven't completed.
4. Errors are returned before state mutation — a failed block cannot leave
   partial data in pipeline state.
5. Multi-item blocks (e.g., "write all scenes") validate count and uniqueness
   of items produced.
6. Block handlers are pure functions of their inputs plus config — they
   return typed output without reading or writing shared mutable state.
   The tool wrapper manages state mutation. (Theory Invariant 7)

### Storybook Example

Each tool in `storybook-agent.ts` follows the pattern: check `completedSteps`
→ check dependencies → call handler → mutate state → add to `completedSteps`
→ return human-readable summary. The `handleWrite()` handler is a pure
function that accepts `(config, plan, enriched, contract)` and returns
`{ scenes, blockOutput }`. The wrapper then does `state.scenes = scenes`.
Errors in handlers are caught by the wrapper and returned as `isError: true`
before any state fields are set.

### Verification Checklist

| ID | Check | How to Verify |
|----|-------|---------------|
| A2.1 | Each block has exactly one output tool | Count tools in MCP server; each block maps to one tool name |
| A2.2 | CompletedSteps prevents re-execution | Find the idempotency guard at the top of each tool wrapper |
| A2.3 | Dependency checks before execution | Find the prerequisite check (e.g., `if (!state.enriched)`) before the handler call |
| A2.4 | Errors before state mutation | Confirm error paths return before any `state.X = ...` assignment |
| A2.5 | Multi-item count and uniqueness validated | For blocks producing arrays, verify length matches contract and items have unique keys |
| A2.6 | Handler is a pure function | Confirm the handler accepts explicit parameters and returns typed output without touching shared state — the wrapper handles mutation |

---

## 3. Model Assignment

Match the model to the cognitive demands of the task. Haiku handles extraction
and validation. Sonnet handles structural design. Opus handles creative
generation and deep reasoning. Temperature controls the spectrum from
deterministic to creative.

**Theory mapping**: The theory doc explicitly excludes model selection from its
scope ("Which LanguageModel runs inside an execute handler — agent configuration").
This section fills that gap with auditable patterns.

### Rules

1. Model selection matches cognitive demands:
   - **Haiku**: validation, extraction, classification, deterministic analysis
   - **Sonnet**: structural design, moderate analysis, planning
   - **Opus**: creative generation, deep reasoning, spec-following under constraints
2. Temperature matches task type:
   - **t=0**: deterministic extraction (sentiment, classification)
   - **t=0.4**: spec-following generation (assemble HTML from specs)
   - **t=1**: creative generation (prose writing, visual design)
3. Expensive models are justified by the task — don't use Opus for extraction.
4. Model assignment is visible in config, not buried in block code.

### Storybook Example

`MODEL_DEFAULTS` in `types.ts` maps each block to its model:
- `brief: "claude-haiku-4-5"` — validation and enrichment (extraction)
- `plan: "claude-sonnet-4-5"` — structural design (scene architecture)
- `write: "claude-opus-4-6"` — creative prose generation (t=1)
- `sentiment: "claude-haiku-4-5"` — deterministic extraction (t=0)
- `design: "claude-opus-4-6"` — creative visual specs (t=1)
- `assemble: "claude-opus-4-6"` — spec-following code generation (t=0.4)
- `qa: "claude-haiku-4-5"` — structural verification

### Verification Checklist

| ID | Check | How to Verify |
|----|-------|---------------|
| A3.1 | Model matches cognitive demands | Map each block's task type (extraction/design/creative/reasoning) to model tier |
| A3.2 | Temperature matches task type | Verify t=0 for deterministic, t≈0.4 for spec-following, t=1 for creative |
| A3.3 | Expensive models justified | Confirm Opus blocks require creative generation or complex reasoning |
| A3.4 | Model assignment visible in config | Find a centralized model assignment (config object, constants file), not scattered through blocks |

---

## 4. Tool Schema Design

Each block has one output tool. The tool schema descriptions encode domain
rules — they are the contract between the model's understanding and the
system's expectations. Required fields match what downstream blocks need.

**Theory mapping**: Theory Invariant 4 states block definitions are stable —
name, schema, and output shape do not change. This section adds the quality
dimension: not just stable, but well-designed.

### Rules

1. One output tool per block — the schema defines the complete output shape.
2. Schema descriptions encode domain rules, not just type information
   (e.g., `"word_count — integer, must be within contract.words_per_scene range"`).
3. Required fields are exactly what downstream blocks need — no dead fields
   (in schema but never consumed) and no ghost references (consumed but not
   in schema).
4. Multi-item patterns: use tool calls to extract all items, then validate
   count and uniqueness against the contract.
5. Schema field names match type definitions — no implicit renaming between
   tool output and typed state.

### Storybook Example

The `write` block's tool schema requires `scene_number`, `title`, `prose`,
`word_count`, `setting_hint`, `emotional_beat`, and `interaction_type` — each
consumed by downstream blocks (sentiment reads `prose`, design reads
`setting_hint` and `emotional_beat`). The `design` block's tool produces
`SceneDesign` fields that map 1:1 to the `SceneDesign` interface. No field
exists in the schema that isn't consumed downstream or validated in QA.

### Verification Checklist

| ID | Check | How to Verify |
|----|-------|---------------|
| A4.1 | One output tool per block | Confirm each block registers exactly one tool in the MCP server |
| A4.2 | Schema descriptions encode domain rules | Read tool schema descriptions; they should include constraints, not just types |
| A4.3 | Required fields match downstream needs | Trace each required field to its consumer — every field should be read somewhere |
| A4.4 | No dead fields or ghost references | Cross-reference schema fields with downstream prompt references and type definitions |
| A4.5 | Schema field names match type definitions | Verify tool output field names align with TypeScript interface property names |

---

## 5. Context Forwarding

Only tool call inputs cross block boundaries. Conversation history does not
survive between blocks. Accumulated state is passed through typed structures,
not message transcripts. Tool returns are human-readable summaries for the
orchestrator, not data payloads for downstream blocks.

**Theory mapping**: The theory defines four data flow patterns — chain,
selective, accumulative, and compressed — as consequences of type overlap
between blocks. Identify which pattern the agent uses and verify it matches
the actual data flow. The theory also defines shared context as agent-level
configuration (identity, feature flags) that flows read-only alongside block
inputs.

### Rules

1. No conversation history is forwarded between blocks — each block starts
   with a fresh system prompt and structured input.
2. Tool returns are human-readable summaries for the orchestrator, not raw
   data for downstream consumption. Downstream blocks read from typed state.
3. Accumulated state is typed — `SceneData`, `PipelineState`, or equivalent
   structures carry data between blocks, not unstructured strings.
4. Each block receives only what it needs — not the entire pipeline state.
   The handler's function signature declares its dependencies.
5. Shared context (agent-level config, identity, feature flags) flows as a
   read-only parameter — blocks do not mutate it.

### Storybook Example

The storybook-agent uses the **accumulative** pattern: `PipelineState` holds
all prior block outputs, and each handler receives what it needs from that
accumulator. When `run_write` completes, it returns a summary string:
`"Wrote 10 scenes. Total words: 350."` The orchestrator uses this to decide
the next step. The actual prose data flows through `state.scenes` (typed as
`SceneProse[]`), which `run_sentiment` reads. No block receives the previous
block's conversation transcript — `handleSentiment()` accepts
`(config, scenes, enriched)` as explicit typed parameters. `config` is the
shared context — passed to every handler, never mutated.

### Verification Checklist

| ID | Check | How to Verify |
|----|-------|---------------|
| A5.1 | No conversation history forwarded | Confirm each block handler receives explicit parameters, not message arrays from prior blocks |
| A5.2 | Tool returns are summaries | Read the return values from each tool — they should be human-readable strings, not JSON data dumps |
| A5.3 | Accumulated state is typed | Find the typed state interface (e.g., `PipelineState`); verify data flows through typed fields |
| A5.4 | Blocks receive only what they need | Check handler function signatures — each should accept specific types, not the entire pipeline state |
| A5.5 | Data flow pattern identified | Name which theory pattern the agent uses (chain/selective/accumulative/compressed) and verify it matches actual data flow |
| A5.6 | Shared context is read-only | Find the agent-level config object and verify no block handler mutates it |

---

## 6. QA Architecture

QA runs in phases: structural checks (deterministic) before conformance
checks (heuristic) before domain checks before AI checks. Structural gates
AI — don't burn tokens on broken output. QA can run on the output artifact
alone, independent of how it was produced.

### Rules

1. Structural checks (word count, field presence, HTML validity) run before
   AI-based quality checks.
2. Structural pass gates AI checks — if structure fails, skip AI QA to save
   tokens.
3. QA can run on the output artifact alone — it does not need access to
   intermediate pipeline state or model reasoning.
4. Every contract field has a corresponding QA check — no constraint exists
   without verification.
5. QA checks are independently testable — each check is a pure function of
   (output, contract) that returns pass/fail.

### Storybook Example

`blocks/qa.ts` runs structural checks first: scene count matches contract,
word counts within `contract.scene_contracts[scene_type].words_per_scene`,
HTML has expected structure. Only after structural checks pass does it run
narrative QA (AI-based checks for story coherence, age-appropriateness).
Each check accepts the HTML output and the contract — it doesn't need to know
which model wrote the prose or what temperature was used.

### Verification Checklist

| ID | Check | How to Verify |
|----|-------|---------------|
| A6.1 | Structural checks before AI checks | Find the check execution order; deterministic checks should run first |
| A6.2 | QA can run on output artifact alone | Confirm QA function signature accepts (output, contract), not intermediate state |
| A6.3 | Every contract field has a QA check | Map each contract field to its QA check; report any uncovered fields |
| A6.4 | Structural pass gates AI QA | Find the gate condition — AI checks should be skipped if structural checks fail |
| A6.5 | QA checks are independently testable | Verify each check is a pure function with clear pass/fail semantics |

---

## 7. Orchestration

The orchestrator uses the MCP tool server factory pattern. Each tool follows
the same lifecycle: check completed → check deps → execute → mutate state →
return summary. Post-pipeline validation confirms all expected state fields
are populated.

**Theory mapping**: The theory defines two orchestrator modes — code
orchestrator (deterministic sequence) and LLM orchestrator (dynamic dispatch).
Both use the same block definitions. This section covers the LLM orchestrator
pattern where the model decides tool invocation order, and the safety patterns
that make that work.

### Rules

1. Use `createSdkMcpServer()` (or equivalent MCP server factory) to register
   all pipeline tools in one place.
2. Each tool follows the lifecycle: check completed → check deps → execute
   handler → mutate state → add to completedSteps → return.
3. Tool dependency chains are explicit — each tool's description states its
   prerequisite (e.g., "Requires run_plan first").
4. Errors cannot corrupt state — the `try/catch` boundary is outside the
   state mutation, and errors return before mutation.
5. Post-pipeline validation checks that all expected state fields are
   populated before building the final result.
6. The orchestrator system prompt lists tools in execution order with one-line
   descriptions.

### Storybook Example

`createStorybookToolServer(state, characters)` registers 8 tools. Each follows
the identical pattern visible in `storybook-agent.ts`:

```
if (state.completedSteps.has("run_X")) → return error
if (!state.prerequisite) → return error
try {
  const result = await handleX(state.config, ...inputs);
  state.output = result;
  state.completedSteps.add("run_X");
  return { content: [{ type: "text", text: "Summary..." }] };
} catch (err) {
  return { isError: true, content: [{ type: "text", text: `ERROR: ...` }] };
}
```

After `runOrchestrator()` completes, `runStorybookAgent()` validates that all
8 state fields are populated — if any are missing, it throws with a list of
the missing fields.

### Verification Checklist

| ID | Check | How to Verify |
|----|-------|---------------|
| A7.1 | MCP server factory pattern | Find `createSdkMcpServer()` or equivalent single registration point for all tools |
| A7.2 | Each tool follows the lifecycle | Verify check-completed → check-deps → execute → mutate → return in each tool |
| A7.3 | Tool dependencies are explicit | Read each tool's description string; it should name its prerequisite |
| A7.4 | Errors cannot corrupt state | Confirm the try/catch wraps the handler call, and error paths return before state mutation |
| A7.5 | Post-pipeline validates completeness | Find the post-orchestration check that verifies all expected state fields exist |
| A7.6 | Orchestrator prompt lists tools in order | Read the system prompt; it should list tools sequentially with one-line descriptions |

---

## 8. State Management

A single `PipelineState` type holds all pipeline data. Input fields are set
before the pipeline starts; output fields are optional and populated by their
respective blocks. Each tool wrapper mutates only its designated output fields.
A `completedSteps` Set tracks which blocks have run. `BlockOutput` tracks cost
metrics per block.

**Theory mapping**: The theory's "Orchestrator State" describes the accumulator
as the orchestrator's private concern — blocks never see it directly. In the
MCP server pattern, the tool wrapper IS the orchestrator's boundary. The
handler returns typed output; the wrapper decides what to store where.
Telemetry flows through handler return types (the handler returns
`{ result, blockOutput }`) AND is accumulated by the wrapper into
`state.blockOutputs`. Both happen — the handler satisfies Theory Invariant 6
(telemetry in output types) and the wrapper performs the accumulation.

### Rules

1. A single `PipelineState` type (or equivalent) holds all mutable state.
2. Input fields (`brief`, `contract`, `config`) are set before the pipeline
   starts and are not mutated by blocks.
3. Output fields (`enriched`, `plan`, `scenes`, etc.) are optional in the
   type definition and set by their respective blocks.
4. A `completedSteps` Set tracks execution — blocks check this before running.
5. `BlockOutput` (or equivalent) tracks cost metrics: `input_tokens`,
   `output_tokens`, `duration_ms`, `model` per block.
6. Each tool wrapper mutates only its designated output fields. Some blocks
   produce multiple related outputs (e.g., `run_write` sets both `scenes`
   and `sceneData`; `run_design` sets both `designs` and `designContracts`).
   The rule is: a wrapper never touches fields owned by another block.

### Storybook Example

`PipelineState` in `types.ts`:
- Input fields: `brief`, `contract`, `config`, `startTime` — set in
  `runStorybookAgent()` before orchestration.
- Output fields: `enriched?`, `plan?`, `scenes?`, `sentiments?`, `designs?`,
  `designContracts?`, `sceneData?`, `html?`, `qaReport?` — each optional,
  each set by its owning block's wrapper.
- Metadata: `blockOutputs: BlockOutput[]`, `modelsUsed: Record<string, string>`,
  `completedSteps: Set<string>`.

`BlockOutput` tracks `block_number`, `block_name`, `output`, `model`,
`input_tokens`, `output_tokens`, `duration_ms`. Handlers return `blockOutput`
as part of their typed output; wrappers push it to `state.blockOutputs`.

### Verification Checklist

| ID | Check | How to Verify |
|----|-------|---------------|
| A8.1 | Single PipelineState type exists | Find one interface/type that holds all mutable pipeline state |
| A8.2 | Input fields immutable during pipeline | Confirm `brief`, `contract`, `config` are set before orchestration and not reassigned by any tool |
| A8.3 | Output fields are optional | Verify output fields use `?` (optional) in the type definition |
| A8.4 | completedSteps Set exists | Find the `Set<string>` or equivalent that tracks block execution |
| A8.5 | BlockOutput tracks cost metrics | Find the type that records `input_tokens`, `output_tokens`, `duration_ms`, `model` per block |
| A8.6 | Each wrapper mutates only its own fields | Audit each tool wrapper to confirm it only sets fields designated to its block — never another block's fields |
| A8.7 | Telemetry flows through handler return types | Confirm handlers return `blockOutput` as part of typed output, not as a side effect (Theory Invariant 6) |

---

## 9. Error Recovery

Blocks fail. Models timeout. API rate limits hit. An architecture without
error recovery is an architecture that stops on first failure. Error strategy
is a design decision, not an afterthought.

### Rules

1. Every block has a defined failure mode — it either retries, falls back,
   or propagates the error. The choice is explicit, not implicit.
2. Transient failures (API timeouts, rate limits) have a retry strategy with
   bounded attempts and backoff.
3. Non-transient failures (invalid input, schema violation) propagate
   immediately — retrying won't help.
4. The pipeline has a cost budget. If cumulative cost exceeds the budget,
   remaining blocks are skipped and the pipeline returns partial results
   with an explanation.

### Storybook Example

Each tool in `storybook-agent.ts` catches errors and returns
`{ isError: true, content: [...] }` before state mutation. This is a
propagation strategy — errors immediately stop the pipeline (the LLM
orchestrator sees the error and does not invoke the next tool). No retry
logic exists; transient failures cause full pipeline failure. No cost
budget is enforced — cost is tracked post-hoc in `blockOutputs` but
never compared against a limit during execution.

### Verification Checklist

| ID | Check | How to Verify |
|----|-------|---------------|
| A9.1 | Every block has a defined failure mode | For each tool, identify: does it retry, fall back, or propagate? If none is explicit, flag as missing |
| A9.2 | Transient failures have retry strategy | Search for retry logic (loops, backoff, max attempts) around inference calls |
| A9.3 | Non-transient failures propagate immediately | Confirm schema violations and invalid inputs return errors without retry |
| A9.4 | Cost budget exists | Find a max cost threshold in config or constants and a check that compares cumulative cost before each block |

---

## 10. Observability

An agent that fails silently is worse than one that fails loudly. Every block
should produce enough metadata to reconstruct what happened, what it cost,
and where it went wrong — without re-running the pipeline.

**Theory mapping**: Theory Invariant 6 states telemetry flows through output
types. This section extends that principle beyond cost metrics to structured
artifacts that enable debugging and replay.

### Rules

1. Every block emits cost metrics in its output (input_tokens, output_tokens,
   duration_ms, model). This is the minimum telemetry envelope.
2. Intermediate artifacts (block outputs) are stored for debugging — not just
   the final pipeline output.
3. The pipeline emits a structured summary after completion: total cost,
   per-block metrics, models used, completion status.
4. Failed runs produce enough context to diagnose the failure without
   re-running the pipeline.

### Storybook Example

`storybook-agent.ts` tracks `BlockOutput` per block (input_tokens,
output_tokens, duration_ms, model) and pushes to `state.blockOutputs`.
After pipeline completion, it calculates total cost via
`estimateCost(blockOutputs)`. The `storeBlock` callback in `StoryConfig`
allows callers to persist intermediate artifacts (HTML, metadata), but
not all blocks invoke it — only `post_process` calls `config.storeBlock`.

### Verification Checklist

| ID | Check | How to Verify |
|----|-------|---------------|
| A10.1 | Every block emits cost metrics | Confirm each handler returns a `blockOutput` object with token counts and duration |
| A10.2 | Intermediate artifacts stored | Find the artifact storage mechanism (callback, database write) and verify it's called per-block or at key checkpoints |
| A10.3 | Pipeline emits structured summary | Find the post-pipeline summary that reports total cost, per-block metrics, and completion status |
| A10.4 | Failed runs produce diagnostic context | Confirm error responses include which block failed, the error message, and what state existed at failure time |
