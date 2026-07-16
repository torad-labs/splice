---
name: pipeline-xray
description: >-
  Use this skill when the user asks to "xray the pipeline", "trace data flow",
  "check seam integrity", "pipeline xray", "map block inputs/outputs", "find
  dead fields", "check pipeline data flow", or wants to analyze data-flow
  integrity across a multi-block agent pipeline. Runs a 3-phase multi-model
  pipeline: Haiku extracts per-block data flow maps in parallel, Sonnet
  diagnoses cross-seam issues (dead fields, orphan reads, format drift),
  Opus identifies the one structural leverage point across the whole pipeline.
user-invocable: true
argument-hint: "[path to agent directory]"
---

# Pipeline X-Ray

3-phase multi-model pipeline for tracing data flow through multi-block agent
pipelines, mapping every seam, and finding dead fields, false positives, and
format mismatches.

## When to Use This vs Agent Audit

| Agent Audit | Pipeline X-Ray |
|-------------|---------------|
| Examines what prompts SAY | Examines what code DOES with data |
| Directive quality and consistency | Data flow integrity between blocks |
| Cross-block prompt conflicts | Dead fields, orphan reads, format drift |
| Prompt-level leverage | Data-flow-level leverage |

Neither is redundant. Agent-audit catches prompt-level issues. Pipeline-xray
catches data-flow-level issues — the layer between what prompts say and what
code actually connects.

## Pipeline

| Phase | Model | Task | Output |
|-------|-------|------|--------|
| 1: Extract | Haiku (xN, parallel) | Per-block data flow maps | Per-block input/output maps |
| 2: Diagnose | Sonnet | Cross-seam analysis | Dead fields, drift, blind spots |
| 3: Leverage | Opus | One structural weakness | Single leverage finding |

## Vocabulary

**Seam**: The connection between two pipeline blocks. Defined by the TypeScript
type crossing the boundary, the tool schema constraining output, and the
PipelineState fields carrying data.

**Dead field**: A field written by one block but never read by any downstream
block. Accumulated type debris.

**Orphan read**: A field consumed by a block but never written by any upstream
block. Relies on initialization or external input.

**Format drift**: A QA pattern or validation regex that matches output from a
previous architecture but not the current one. False positive source.

**Shadow dependency**: Block A depends on Block B's output format through string
matching or position parsing, without an explicit type contract.

## Process

### 1. Locate the Agent

Resolve the agent directory from `$ARGUMENTS`. Validate it exists and contains
TypeScript files.

### 2. Run the Deterministic Script

Execute `scripts/extract-flow.sh $AGENT_DIR` to produce
`/tmp/pipeline-xray-inventory.txt`. This extracts file inventory, handler
signatures, state type fields, tool schema fields, state writes, state reads,
QA patterns, post-process injections, and type imports.

### 3. Build the Seam Map

Read the script output and agent source files. Build a seam map showing pipeline
order, per-block data flow, and per-seam type contracts.

### 4. Run the Pipeline

Follow the dispatch table, context budget, gate validation, and assembly
protocol defined in [`references/orchestration.md`](references/orchestration.md).

## Output Contract

```
## Pipeline X-Ray Report

### Seam Map
[blocks in pipeline order, with arrows showing data flow]
[per-seam: upstream block -> downstream block, type contract, fields crossing]

### Phase 1: Per-Block Data Flow (Haiku x N)
[per-block: reads, writes, tool, seam contracts, format assumptions]

### Phase 2: Seam Analysis (Sonnet)

#### Dead Fields
[fields written but never consumed downstream]

#### Orphan Reads
[fields consumed but never written upstream]

#### Format Drift
[QA patterns matching outdated architecture]

#### Blind Spots
[seams with no validation coverage]

#### Contract Gaps
[tool schema vs TypeScript type mismatches]

#### Injection Order Risks
[post-process dependencies]

#### Shadow Dependencies
[implicit format dependencies without type contracts]

DEAD FIELDS: [N] | ORPHAN READS: [N] | FORMAT DRIFT: [N]
BLIND SPOTS: [N] | CONTRACT GAPS: [N] | SHADOW DEPS: [N]

### Phase 3: Leverage (Opus)
LEVERAGE POINT: [the structural weakness]
IF CHANGED: [what improves]
IF NOT CHANGED: [what keeps accumulating]
EVIDENCE: [which findings trace here]
PROPOSED CHANGE: [the design-level change]
```

## References

| File | Purpose |
|------|---------|
| [`references/orchestration.md`](references/orchestration.md) | Dispatch table, context budget, gates, assembly |

## Blocks

| Block | Model | Purpose |
|-------|-------|---------|
| [`blocks/extract.md`](blocks/extract.md) | Haiku | Per-block data flow extraction + seam contracts |
| [`blocks/diagnose.md`](blocks/diagnose.md) | Sonnet | Cross-seam diagnosis (7 categories) |
| [`blocks/leverage.md`](blocks/leverage.md) | Opus | Structural leverage identification |

## Scripts

| Script | Purpose |
|--------|---------|
| [`scripts/extract-flow.sh`](scripts/extract-flow.sh) | Deterministic data flow extraction (runs before any model) |
