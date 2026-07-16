---
name: agent-audit
description: >-
  Use this skill when the user asks to "audit this agent", "check my agent
  prompts", "audit the pipeline", "review agent consistency", "check
  cross-block prompts", "agent prompt audit", or wants to analyze prompt
  quality and consistency across a multi-block agent pipeline. Runs a
  3-phase multi-model pipeline: Haiku extracts directives and boundary
  specs per block in parallel, Sonnet detects cross-block conflicts and
  contract gaps and produces revised prompts, Opus identifies the one
  structural leverage point across the whole agent.
user-invocable: true
argument-hint: "[path to agent directory]"
---

# Agent Audit

3-phase multi-model pipeline for auditing prompt quality and consistency
across a multi-block agent. Variant of prompt-audit designed for agents
where prompts are embedded in code, spread across blocks, and must
maintain consistency across a pipeline.

## When to Use This vs Prompt Audit

| Prompt Audit | Agent Audit |
|-------------|-------------|
| Single prompt or skill file | Multi-file agent with pipeline blocks |
| Directives in one document | Directives embedded in TypeScript/code |
| Self-contained analysis | Cross-block + cross-boundary analysis |
| No data flow to check | Type contracts, tool schemas, data flow |

## Pipeline

| Phase | Model | Task | Output |
|-------|-------|------|--------|
| 1: Extract | Haiku (×N, parallel) | Directives + boundary specs per block | Per-block directive maps |
| 2: Synthesize | Sonnet | Per-boundary analysis + revised prompts | Conflict report + revisions |
| 3: Leverage | Opus | One structural weakness across whole agent | Single leverage finding |

## Vocabulary

**Boundary spec**: The first-class unit of cross-block analysis. For each
connection between blocks: the type contract, tool schema, forwarded context,
implicit assumptions, and transformation type. Phase 1 emits one per
connection. Phase 2 analyzes one per boundary.

**Verifiable directive**: A directive where a third party can determine binary
pass/fail from the output alone, without access to the model's reasoning.

**Leverage point**: A design-level pattern at the boundary or contract layer
that, if changed, would prevent a category of cross-block conflicts from being
possible. Not a directive-level fix.

## Process

### 1. Locate the Agent

Resolve the agent directory from `$ARGUMENTS`. Validate it exists and contains
TypeScript files.

### 2. Run the Pipeline

Follow the dispatch table, context budget, gate validation, and assembly
protocol defined in [`references/orchestration.md`](references/orchestration.md).

The orchestration protocol covers: deterministic inventory via bash script,
agent map construction, Phase 1-3 dispatch with temp file handoffs, compact
manifests, parallelization rules, gate checks, and final assembly.

## Output Contract

```
## Agent Audit Report

### Agent Map
[blocks, boundaries, boundary count, pipeline, data flow]

### Phase 1: Per-Block Extraction (Haiku × N)
[per-block directive maps + boundary specs]

### Phase 2: Cross-Block Synthesis (Sonnet)

#### Per-Boundary Findings
[per-boundary: conflicts, contract gaps, context loss, GAPs from missing Phase 1 fields]

#### Pipeline-Wide Findings
[duplication, QA coverage, best practices, architecture patterns]

#### Revised Prompts
[per-block revised system prompts — standalone drop-in replacements]

### Phase 3: Leverage (Opus)
LEVERAGE POINT: [the structural weakness]
IF CHANGED: [which findings disappear]
IF NOT CHANGED: [what keeps recurring]
EVIDENCE: [which Phase 1/2 findings trace here]
PROPOSED CHANGE: [the design-level change]
```

## Key Rules

- Cross-block conflicts take priority over per-block overlaps. If the same
  directive is in both, report the cross-block conflict. Note the overlap
  but do not escalate it.
- Phase 2 does not merge blocks in revised prompts. Phase 3 can recommend
  boundary elimination or block consolidation. Different scopes, not a
  contradiction.
- Duplication is not automatically a problem. Flag it, classify it, state
  the reason.
- Each audit invocation is independent. Findings from one run do not feed
  into a subsequent run.
- The audit applies its own verifiability standard to itself.

## References

| File | Purpose |
|------|---------|
| [`references/orchestration.md`](references/orchestration.md) | Dispatch table, context budget, gates, assembly |
| [`references/best-practices.md`](references/best-practices.md) | Anthropic agent best practices (Sonnet reads) |
| [`references/agent-architecture.md`](references/agent-architecture.md) | Architecture patterns with theory invariants (Sonnet reads) |
| `../prompt-audit/references/rejection-framework.md` | Shared directive theory |
| `../prompt-audit/references/multi-model-pipeline.md` | Shared orchestration pattern |

## Blocks

| Block | Model | Purpose |
|-------|-------|---------|
| [`blocks/extract.md`](blocks/extract.md) | Haiku | Per-block directive extraction + boundary specs |
| [`blocks/synthesize.md`](blocks/synthesize.md) | Sonnet | Cross-block synthesis + revised prompts |
| [`blocks/leverage.md`](blocks/leverage.md) | Opus | Structural leverage identification |

## Scripts

| Script | Purpose |
|--------|---------|
| [`scripts/inventory.sh`](scripts/inventory.sh) | Deterministic file classification (runs before any model) |
