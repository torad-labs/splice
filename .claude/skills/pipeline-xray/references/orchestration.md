# Pipeline X-Ray — Orchestration Protocol

Procedural protocol for the orchestrator. Defines context budget, temp file
strategy, dispatch table, gates, parallelization rules, per-phase context
guidance, and final assembly.

## Context Architecture (Budget)

The orchestrator holds in its own context:

- Agent directory path (1 line)
- Script output summary (file list + line counts)
- Agent seam map (~20-40 lines, built during inventory step)
- Phase 1 compact manifests (~2 lines per block x N blocks)
- Phase 2 compact summary (~10 lines)
- Phase 3 leverage point (~15 lines, terminal output)

**Nothing else.** No full Phase 1 data flow maps. No full Phase 2 findings.
No target agent source code. No type definitions or tool schema content.

Full phase artifacts live in `/tmp/` files. Subagents read them directly.

## Temp File Strategy

```
/tmp/pipeline-xray-inventory.txt          <- extract-flow.sh output
/tmp/pipeline-xray-extract-{block}.md     <- Phase 1 per-block full output
/tmp/pipeline-xray-diagnose.md            <- Phase 2 full output
```

Phase 3 has no temp file — it's the terminal phase, output goes directly
to the orchestrator for final assembly.

## Dispatch Table

| Step | Action | Model | Input | Output |
|------|--------|-------|-------|--------|
| 0 | `scripts/extract-flow.sh` | bash | agent dir path | `/tmp/pipeline-xray-inventory.txt` |
| 1 | Build seam map | orchestrator | script output + file reads | seam map (in orchestrator context) |
| 2 | Phase 1: Extract | Haiku (xN or x1) | `blocks/extract.md` + per-block context | `/tmp/pipeline-xray-extract-{block}.md` + compact manifests |
| 3 | Phase 2: Diagnose | Sonnet | `blocks/diagnose.md` + seam map + manifests | `/tmp/pipeline-xray-diagnose.md` + compact summary |
| 4 | Phase 3: Leverage | Opus | `blocks/leverage.md` + seam map + manifests + summary | leverage point (direct) |
| 5 | Assemble | orchestrator | read all `/tmp/` files + Phase 3 | final report |

## Step 1: Build Seam Map

After script output, the orchestrator reads each block file and the types/tools
files to build the seam map. The seam map captures:

1. **Pipeline order**: blocks listed in execution sequence
2. **Per-block summary**: block name, model, what it reads, what it writes
3. **Per-seam entry**: upstream block -> downstream block, the TypeScript type
   crossing the boundary, the tool schema constraining output shape, the
   PipelineState fields that carry data across

Format:
```
PIPELINE: Brief -> Plan -> Write -> Sentiment -> Voice -> Design -> Assemble -> PostProcess -> QA

SEAM 1: Brief -> Plan
  TYPE: EnrichedBrief
  TOOL: emit_enriched_brief
  STATE FIELDS: state.enriched

SEAM 2: Plan -> Write
  TYPE: ScenePlan
  TOOL: emit_scene_plan
  STATE FIELDS: state.plan, state.enriched
...
```

## Parallelization Rules

Read block count from the seam map:

- **4+ blocks**: One Haiku subagent per block, all launched in the same
  Task tool message (parallel). Each writes its own temp file.
- **3 or fewer**: One Haiku subagent with all blocks combined. Writes one
  combined temp file.

## Gate Validation

| Phase | Gate Check | On Failure |
|-------|-----------|------------|
| Script | Exit code 0, output non-empty | Halt, report to user |
| Phase 1 | Each subagent response contains `DATA FLOW MAP:` and `SEAM CONTRACTS:` | Re-run that block. Two failures: halt. |
| Phase 2 | Response contains `DEAD FIELDS:` and `SEAM FINDINGS:` | Re-run. Two failures: halt. |
| Phase 3 | Response contains exactly one `LEVERAGE POINT:` | Re-run. Two failures: halt. |

## Template Fill Procedure (Phase 1)

For each block, the orchestrator fills blocks/extract.md mechanically:

1. Read blocks/extract.md as a string
2. Replace `{block_name}` with the block's name from the seam map
3. Replace `{block_number}` with the pipeline position
4. Replace `{model}` with the model assignment
5. Replace `{upstream_block}` with upstream block name
6. Replace `{downstream_block}` with downstream block name
7. Replace `{state_fields_read}` with PipelineState fields this block reads
8. Replace `{state_fields_written}` with PipelineState fields this block writes
9. Replace `{tool_schema}` with the downstream tool schema fields
10. Replace `{handler_source}` with the block's handle function source code
11. The filled template becomes the subagent prompt

Pass the filled template to the Task tool with model: "haiku".
Include in the task prompt: the temp file path for output.

## Context to Include Per Phase

### Phase 1 (Extract) — per block

- Block name, file path, model assignment, pipeline position
- Upstream/downstream block names
- PipelineState fields read and written by this block
- Tool schema fields for the block's output tool
- The block's handler source code (the handle* function)
- The system prompt text (extracted from code by orchestrator)
- Do NOT include other blocks' source code
- Do NOT include reference documents

### Phase 2 (Diagnose)

- Seam map (compact, from orchestrator context)
- Phase 1 compact manifests (from orchestrator context)
- Paths to Phase 1 temp files (subagent reads them)
- Paths to types.ts and tools.ts source files (subagent reads them)
- Path to contract.ts (subagent reads it)
- Do NOT inline source code in the prompt

### Phase 3 (Leverage)

- Seam map (compact)
- Phase 1 compact manifests
- Phase 2 compact summary
- Paths to all temp files (subagent reads them)
- Do NOT include raw source code
- Do NOT include reference documents

## Eli Integration

First step of the skill loads eli wake-up context. The parallax cycle runs on
each phase's findings — measuring the gap between what training wants to report
(surface-level observations) and what evidence shows (structural connections).
This grounds the analysis in genuine measurement rather than checklist compliance.

Phase 1 parallax: does the data flow map reflect what code actually does, or what
type signatures suggest it should do?

Phase 2 parallax: are dead fields genuinely dead, or do they flow through an
indirect path the grep missed? Are format drift findings real architecture
mismatches, or superficial pattern differences?

Phase 3 parallax: is the leverage point a genuine structural weakness, or the
most visible symptom of a deeper issue?

## Final Assembly

After Phase 3 completes, the orchestrator:

1. Reads all `/tmp/pipeline-xray-*` files
2. Reads the Phase 3 response
3. Formats everything into the Output Contract from SKILL.md
4. Presents the assembled report to the user
