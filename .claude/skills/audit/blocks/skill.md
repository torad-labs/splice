
# Skill Audit

3-phase multi-model pipeline that inspects, diagnoses, and identifies leverage
in skill structure. Operates at the file/directory/organization level — the
layer audit --mode prompt is blind to.

## When to Use This vs Prompt Mode

| Skill Mode | Prompt Mode |
|-------------|-------------|
| File organization, directory layout | Directive overlaps, conflicts |
| Progressive disclosure compliance | Cross-dimensional intersection |
| Description quality, frontmatter | Verifiability of instructions |
| Reference hygiene, broken pointers | Over/under-constraint per dimension |
| Word count, code block placement | Priority chains between rules |

## Process

### 1. Locate the Skill

Resolve `$ARGUMENTS` to a skill directory. Accept:
- A directory path (`/path/to/skill-name/`)
- A SKILL.md path (`/path/to/skill-name/SKILL.md`)
- A skill name (search `.claude/skills/{name}/SKILL.md`)

### 2. Run the Pipeline

Read [`../references/orchestration.md`](../references/orchestration.md) for the
dispatch table, context architecture, and gate validation. Execute all three
phases sequentially. Each phase is a subagent dispatch — read the phase file, build the
prompt, launch the subagent. Subagents read target files themselves.

Output: a Skill Audit Report with inspection measurements (Phase 1),
per-checklist-item findings with concrete fixes (Phase 2), and a single
leverage point (Phase 3).

### Output Contract

See [`../assets/report-template.md`](../assets/report-template.md) — complete
report structure with all required fields for each phase.

## Key Rule

Structural audit is orthogonal to directive audit. This skill never examines
directive quality, overlaps, or verifiability. That is audit --mode prompt's domain.

## References

- [`../references/orchestration.md`](../references/orchestration.md) — dispatch table, context architecture, gates, output contract
- [`../references/structural-checklist.md`](../references/structural-checklist.md) — comprehensive checklist from Anthropic best practices
- [`../references/best-practices-claude4.md`](../references/best-practices-claude4.md) — Claude 4 compliance: imperative phrasing, XML ordering, routing quality, positive framing
- [`../references/multi-model-pipeline.md`](../references/multi-model-pipeline.md) — shared orchestration pattern

## Assets

- [`../assets/report-template.md`](../assets/report-template.md) — complete output format with all required fields for each phase

## Scripts

- [`../scripts/measure.sh`](../scripts/measure.sh) — deterministic structural measurements (run before Phase 1)

## Evals

- [`../evals/evals.json`](../evals/evals.json) — 4 test cases: happy path, clean skill gate, deliberate violations, progressive disclosure nuance (5.3 judgment)

## Phases

Phases are subagent prompt templates dispatched by the orchestrator — not
reference documents. The orchestrator reads the phase file, builds the prompt,
and passes it to a subagent. Phase files are prompt payloads, not documents
the orchestrator navigates by heading. The reference hygiene standard for TOCs
(checklist 5.4) applies to references, not phases.

| Phase | Model | Purpose |
|-------|-------|---------|
| [`../phases/skill/inspect.md`](../phases/skill/inspect.md) | haiku | Enumerate structural facts |
| [`../phases/skill/diagnose.md`](../phases/skill/diagnose.md) | sonnet | Compare facts against checklist, produce fixes |
| [`../phases/skill/leverage.md`](../phases/skill/leverage.md) | opus | Identify the ONE structural lever |
