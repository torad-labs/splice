
# Agent Audit

3-phase multi-model pipeline for auditing prompt quality and consistency
across a multi-block agent. Variant of audit --mode prompt designed for agents
where prompts are embedded in code, spread across blocks, and must
maintain consistency across a pipeline.

## When to Use This vs Prompt Mode

| Prompt Mode | Agent Mode |
|-------------|-------------|
| Single prompt or skill file | Multi-file agent with pipeline blocks |
| Directives in one document | Directives embedded in TypeScript/code |
| Self-contained analysis | Cross-block + cross-boundary analysis |
| No data flow to check | Type contracts, tool schemas, data flow |

## Pipeline

Three phases, executed sequentially. Phase 2 depends on Phase 1 output.
Phase 3 depends on both.

| Phase | Model | Task | Output |
|-------|-------|------|--------|
| 1: Extract | Haiku (×N, parallel) | Extract directives + boundary specs per block | Per-block directive maps + boundary specs |
| 2: Synthesize | Sonnet | Per-boundary analysis + revised prompts | Conflict report + revised prompts |
| 3: Leverage | Opus | One structural weakness across the whole agent | Single leverage finding |

## Vocabulary

**Boundary spec**: The first-class unit of cross-block analysis. For each
connection between blocks: the type contract, tool schema, forwarded context,
implicit assumptions, and transformation type. Phase 1 emits one per connection.
Phase 2 analyzes one per boundary. This is the object that "cross-block conflict,"
"contract alignment," and "context forwarding" all describe facets of.

**Verifiable directive**: A directive where a third party can determine binary
pass/fail from the output alone, without access to the model's reasoning.

**Leverage point**: A design-level pattern at the boundary or contract layer
that, if changed, would prevent a category of cross-block conflicts from being
possible. Not a directive-level fix.

## Process

### 1. Inventory the Agent

Read the agent directory specified in `$ARGUMENTS`. Identify:

- **Block files**: files containing system prompts (look for `SYSTEM_PROMPT`,
  `buildSystemPrompt`, system prompt strings in code)
- **Pipeline file**: the orchestrator that chains blocks together
- **Types file**: TypeScript types/interfaces that define data contracts
- **Tool definitions**: tool schemas that constrain model output structure
- **Skill files**: markdown files loaded as block-level system prompts
- **Config files**: model assignments, temperature settings, token limits
- **QA/validation**: any block that checks the output of other blocks

Build an **agent map**. Include boundary count — it determines Phase 1
parallelization:

```
AGENT MAP:
  Blocks:         [name, file, model, prompt_lines]
  Boundaries:     [upstream → downstream, type contract, tool schema]
  Boundary count: N
  Pipeline:       [block_order, parallel_groups]
  Types:          [key interfaces, data flow]
  Tools:          [tool_name → output_schema]
  QA:             [what_checked, how_checked]
```

### 2. Phase 1 — Extract (Haiku × N, parallel sub-agents)

Each phase runs as a **sub-agent** that returns structured findings only.
The sub-agent keeps its reasoning internal — only the output schema comes back.

Read the extraction template from
[`../phases/agent/phase-extract.md`](../phases/agent/phase-extract.md).

**Parallelization rule**: Read boundary count from the agent map.
- 4 or more boundaries: launch one Haiku sub-agent per block, all in the
  same message (parallel Task calls with `model: "haiku"`).
- 3 or fewer boundaries: launch one Haiku sub-agent with all prompts
  concatenated and each block clearly labeled.

**What to pass each sub-agent**: The extracted system prompt for the assigned
block, the user message template from code, upstream/downstream type info,
and the extraction instructions from `phase-extract.md`.

**What each sub-agent returns** (tell it to return ONLY this):

```
BLOCK: [name]
FILE: [path]
MODEL: [assigned model]

DIRECTIVE MAP:
| # | Directive (abbreviated) | Dimension | Verifiable | Issues |
|---|------------------------|-----------|------------|--------|

OVERLAPS: [directive pairs within this block]
UNVERIFIABLE: [directives where pass/fail cannot be observed]
AGGRESSIVE LANGUAGE: [directives using CRITICAL/MUST/ALWAYS/NEVER]

BOUNDARY SPECS (one per connection this block participates in):
  BOUNDARY: [this_block] → [downstream_block]
    TYPE CONTRACT: [interface name, fields, which are optional]
    TOOL SCHEMA: [output shape this block must produce]
    FORWARDED CONTEXT: [what crosses, what's summarized, what's lost]
    IMPLICIT ASSUMPTIONS: [things downstream expects but this block doesn't guarantee]
    TRANSFORMATION: [none | reshaping | lossy summarization]
```

**Phase 1 rejection boundary — output scope**: Extract directives and boundary
specs. Do not suggest fixes, rewrites, or alternative phrasings. The output
must reference only the block(s) named in its Block Context header.

Collect all Phase 1 outputs before proceeding.

### 3. Phase 2 — Synthesize (Sonnet sub-agent)

Launch one Sonnet sub-agent. Pass it:
- The agent map
- All Phase 1 outputs (directive maps + boundary specs)
- The type definitions and tool schemas from the agent code
- The synthesis instructions from
  [`../phases/agent/phase-synthesize.md`](../phases/agent/phase-synthesize.md)
- The best practices checklist from
  [`../references/best-practices-agent.md`](../references/best-practices-agent.md)

**What the sub-agent returns** (tell it to return ONLY this):

```
PER-BOUNDARY FINDINGS:
  BOUNDARY: [upstream] → [downstream]
    CONFLICTS: [directive pairs, or "none"]
    CONTRACT GAPS: [dead fields, ghost references, or "none"]
    CONTEXT LOSS: [lossy transformations, implicit assumptions, or "none"]
    ELIMINATION CANDIDATE: [yes/no]

DUPLICATION:
| Directive | Blocks | Classification | Recommendation |
|-----------|--------|---------------|----------------|

QA COVERAGE:
| Directive | Block | QA Check | Status |
|-----------|-------|----------|--------|

BEST PRACTICES:
| Check | Status | Notes |
|-------|--------|-------|

REVISED PROMPTS:
[per-block, only blocks with changes, complete revised text]

CHANGES SUMMARY:
[numbered list linking each change to a finding]
```

**Phase 2 constraint — take Phase 1 as-is**: If a required boundary field
is missing from Phase 1, record as `GAP: [field] not emitted by Phase 1`.
Do not re-run Phase 1.

Sonnet performs analysis per boundary, then pipeline-wide:

**Per-Boundary**: For each boundary spec, check conflict (directives that
can't both be satisfied), contract alignment (dead fields, ghost references),
and context loss (lossy transformations, implicit assumptions).

**Pipeline-Wide**: Duplication detection (identical, paraphrased, contradictory),
QA coverage mapping, best practices check.

**Revised Prompts**: For each block with findings — merge overlaps with
priority chains, replace aggressive framing with boundary specs, rewrite
unverifiable directives as binary pass/fail or remove. Each revised prompt
is a standalone drop-in replacement.

### 4. Phase 3 — Leverage (Opus sub-agent)

Launch one Opus sub-agent (use `subagent_type: "deep-think"`). Pass it:
- The agent map
- All Phase 1 outputs
- The complete Phase 2 output
- The leverage instructions from
  [`../phases/agent/phase-leverage.md`](../phases/agent/phase-leverage.md)

Do NOT include the raw source code — the Phase 1/2 analyses contain
everything needed.

**What the sub-agent returns** (tell it to return ONLY this):

```
LEVERAGE POINT: [the structural weakness — name it precisely]

IF CHANGED: [which Phase 2 findings disappear, which Phase 1 issues
become unnecessary, which QA gaps close]

IF NOT CHANGED: [what category of problems keeps recurring]

EVIDENCE: [which Phase 1/2 findings trace here — by block, boundary, finding number]

PROPOSED CHANGE: [the design-level change, precise enough to implement]
```

Opus is not bound by Phase 2's no-merge constraint. If the leverage point
is boundary elimination or block consolidation, report it. Phase 2 produces
drop-in replacements within the existing design; Phase 3 evaluates the design
itself. These are different scopes, not a contradiction.

### 5. Present Results

Compile the sub-agent outputs into one report:

```
## Agent Audit Report

### Agent Map
[from step 1]

### Phase 1: Per-Block Extraction (Haiku × N)
[Phase 1 sub-agent outputs, concatenated]

### Phase 2: Cross-Block Synthesis (Sonnet)
[Phase 2 sub-agent output]

### Phase 3: Leverage (Opus)
[Phase 3 sub-agent output]
```

## Key Rules

- Cross-block conflicts take priority over per-block overlaps in reporting.
  If the same directive is involved in both, report the cross-block conflict.
  Note the per-block overlap but do not escalate it.
- Phase 2 does not merge blocks in revised prompts. Phase 3 can recommend
  boundary elimination or block consolidation. These are different scopes of
  authority, not a contradiction.
- Duplication is not automatically a problem. Flag it, recommend, and state
  the reason.
- Each audit invocation is independent. Findings from one run do not
  automatically feed into a subsequent run.
- The audit applies its own verifiability standard to itself. If this skill
  contains unverifiable directives, report them as findings. The audit does
  not produce a revised version of this skill during the same run — that is
  a separate invocation of audit --mode prompt.

## Priority Chains

When rules conflict, these chains determine which wins:

1. **Cross-block vs per-block**: A directive involved in a cross-block conflict
   is reported under cross-block findings. The per-block overlap is noted but
   not escalated.

2. **Phase 2 no-merge vs Phase 3 consolidation**: Phase 3 wins when recommending
   structural changes. Phase 2 scope is bounded to the existing block design.
   Phase 3 scope is the design itself.

3. **Missing Phase 1 field vs re-run**: If Phase 1 output is missing a required
   boundary field, Phase 2 records the gap and continues. Re-running Phase 1 is
   not permitted within the same audit invocation.

4. **Self-application**: If the audit's own directives are found unverifiable,
   report them as findings. Do not produce a revised version of this skill
   inline — that is a separate invocation of audit --mode prompt, not part of this run.

## References

- [`../phases/agent/phase-extract.md`](../phases/agent/phase-extract.md) — Haiku per-block extraction template
- [`../phases/agent/phase-synthesize.md`](../phases/agent/phase-synthesize.md) — Sonnet cross-block synthesis template
- [`../phases/agent/phase-leverage.md`](../phases/agent/phase-leverage.md) — Opus structural leverage template
- [`../references/best-practices-agent.md`](../references/best-practices-agent.md) — Anthropic agent best practices (tool search, PTC, model assignment, context forwarding, anti-patterns)
- [`../references/rejection-framework.md`](../references/rejection-framework.md) — shared directive theory
- [`../references/multi-model-pipeline.md`](../references/multi-model-pipeline.md) — shared orchestration pattern
