# Orchestration Protocol

Dispatch table, context architecture, gate validation, and output contract.

## Contents

- [Context Architecture](#context-architecture)
- [Pipeline](#pipeline)
- [Dispatch](#dispatch)
- [Gate Failure](#gate-failure)
- [Operational Rules](#operational-rules)
- [Output Contract](#output-contract)

## Context Architecture

The main context is the **orchestrator**. It stays lean by delegating heavy
work to phases — isolated subagents with their own context windows.

**What the orchestrator holds:**
- Skill directory path (one line)
- Script outputs (deterministic measurements from `scripts/measure.sh`)
- Phase 1 compact manifest (~150 words)
- Phase 2 compact summary (~100 words)
- Phase 3 leverage point (~80 words)
- Nothing else. No full phase reports. No target skill source code. No checklist content.

**What stays in temp files (written by subagents, read by next phase):**
- `/tmp/skill-audit-inspect.md` — Phase 1 full 12-section report (written by Phase 1 block)
- `/tmp/skill-audit-diagnose.md` — Phase 2 full findings + rewrites (written by Phase 2 block)
- Phase 3 reads both files directly; orchestrator reads them at report assembly time.

**What stays in phases:**
- Target skill source code (phase reads its own files via Read tool)
- Structural checklist content (diagnose phase reads it itself)
- Phase instructions (each phase reads its own prompt template)

**What runs as scripts:**
- Deterministic measurements (`scripts/measure.sh`) — word counts, line counts,
  character counts, directory listings, reference hygiene, second person scan,
  date references, path style scan. These run BEFORE Phase 1 and their output
  flows into the Haiku block as pre-verified data.

## Pipeline

| Step | Type | Model | Task | Rejection Boundary |
|------|------|-------|------|--------------------|
| Measure | script | — | Deterministic structural counts | Script must exit 0 |
| Inspect | block | Haiku | Judgment-based analysis + transcribe script counts | Do NOT diagnose or suggest fixes |
| Diagnose | block | Sonnet | Compare facts against checklist, produce findings + fixes | Do NOT re-inspect or invent new facts |
| Leverage | block | Opus | Identify the ONE structural choice with highest downstream impact | Do NOT list multiple improvements |

For the full multi-model pipeline pattern, see
[`references/multi-model-pipeline.md`](multi-model-pipeline.md).

## Dispatch

### Pre-Step: Measure

Run `scripts/measure.sh {skill_dir_path}` via Bash tool. This is deterministic —
no LLM involved. The output feeds into the Inspect block.

If the script fails (non-zero exit), halt and report the error to the user.

### Phases

Each phase: read the phase file at `phases/skill/{phase}.md`. Build the prompt from
its Prompt Template, filling `{placeholders}` with compact data. Launch via
`Task tool, model: [Model], subagent_type: general-purpose`.

| Phase | Block | Model | Orchestrator Passes | Subagent Reads | Subagent Writes | Gate |
|-------|-------|-------|---------------------|----------------|-----------------|------|
| Inspect | [`phases/skill/inspect.md`](../phases/skill/inspect.md) | haiku | skill dir path, measure.sh output | Target skill files (via Read tool) | `/tmp/skill-audit-inspect.md` (full report) | PHASE 1 MANIFEST present in response |
| Diagnose | [`phases/skill/diagnose.md`](../phases/skill/diagnose.md) | sonnet | skill dir path, checklist path, Phase 1 compact manifest | Target skill files + checklist + `/tmp/skill-audit-inspect.md` (via Read tool) | `/tmp/skill-audit-diagnose.md` (full findings) | PHASE 2 SUMMARY + VIOLATION FIXES present in response |
| Leverage | [`phases/skill/leverage.md`](../phases/skill/leverage.md) | opus | skill dir path, Phase 1 compact manifest, Phase 2 compact summary | Target skill files + `/tmp/skill-audit-inspect.md` + `/tmp/skill-audit-diagnose.md` (via Read tool) | — | Exactly one LEVERAGE POINT, no lists |

**Assembly:** After Phase 3, the orchestrator reads all three temp files and the Phase 3 response to assemble the final report. It does NOT concatenate raw phase outputs — it formats them into the Output Contract structure.

**Critical:** Do NOT inline target skill content in the Task prompt. Pass the
directory path. The subagent reads files itself. This keeps source content out
of the orchestrator's context.

## Gate Failure

If a Gate fails, re-run that phase before proceeding. Do not pass malformed
output forward.

If a phase fails its gate twice in succession, halt and report the malformed
output to the user before proceeding.

## Operational Rules

- Deterministic operations run as scripts, not LLM calls. If a number can be
  computed by bash, it belongs in `scripts/`.
- Phase 1 produces raw facts. Phase 2 produces judgments. Do not mix these
  in the same phase.
- Fixes must be concrete: "Move lines 45-89 of SKILL.md to
  references/stripe-helpers.md and replace with a pointer" — not "Consider
  extracting code blocks."
- The structural checklist is the source of truth for Phase 2. Sonnet checks
  every item. Items that do not apply are marked N/A, not skipped silently.

## Output Contract

See [`assets/report-template.md`](../assets/report-template.md) — complete
report structure with all required fields for each phase and assembly notes.
