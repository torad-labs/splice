---
name: audit
description: >-
  This skill should be used when a prompt, agent, or skill needs quality
  review — "audit this prompt", "improve my prompt", "prompt audit",
  "audit this agent", "check my agent prompts", "agent audit", "audit
  this skill", "check skill structure", "skill audit", or "/audit". Runs
  a 3-phase multi-model pipeline (Haiku/Sonnet/Opus): prompt mode checks
  directive quality in a single file; agent mode checks cross-block
  consistency in multi-file pipelines; skill mode checks structural
  organization, progressive disclosure, and reference hygiene. Delivers
  Phase 1 findings, Phase 2 fixes, and a single highest-leverage
  bottleneck from Phase 3.
user-invocable: true
argument-hint: "[--mode prompt|agent|skill] [path to target]"
---

# Audit

<instructions>

Detect the mode, load the corresponding block file, execute all three pipeline phases in sequence, and deliver the consolidated report.

## Step 1 — Detect Mode

Detect mode in this priority order:

1. If `--mode prompt|agent|skill` is specified, use it.
2. If target contains `blocks/*.ts` or `tools.ts` → **agent mode**.
3. If target contains `SKILL.md` and `references/` → **skill mode**.
4. If target is a single file or pasted text → **prompt mode**.
5. Default: **prompt mode**.

## Step 2 — Load Block File

| Mode | Target | Load |
|------|--------|------|
| prompt | Single file or pasted text | `blocks/prompt.md` |
| agent | Directory with `blocks/*.ts` or `tools.ts` | `blocks/agent.md` |
| skill | Directory with `SKILL.md` and `references/` | `blocks/skill.md` |

## Step 3 — Execute All Three Phases

Run all three phases in sequence. Do not skip any phase and do not combine them into one pass. (Each model has a distinct cognitive shape — Haiku extracts what Opus would rationalize away; Opus synthesizes what Haiku would fragment. Skipping a phase produces a worse output than running a slower pipeline.)

| Phase | Model | Shape | Hard boundary |
|-------|-------|-------|---------------|
| 1 | Haiku | Extract every gap — report without fixing | Report gaps only. Produce zero fixes. |
| 2 | Sonnet | Fix every gap from Phase 1 — rewrite and resolve | Use Phase 1 output as input. Do not re-analyze from scratch. |
| 3 | Opus | Identify the single highest-leverage structural bottleneck | Output exactly one finding. Select the highest-impact one. |

<constraints>

- Phase 1: report gaps only. Produce zero fixes.
- Phase 2: use Phase 1 output as input. Do not re-analyze from scratch.
- Phase 3: output exactly one finding. Select the highest-impact one.
- Never skip a phase. Never combine phases into one pass.

</constraints>

## Step 4 — Deliver Report

Load and use [`assets/report-template.md`](assets/report-template.md). Produce the consolidated report for the mode. Include Phase 1 findings, Phase 2 revisions, and Phase 3 leverage finding. Do not summarize — deliver the full output.

</instructions>

<output_format>

Follow [`assets/report-template.md`](assets/report-template.md) exactly. Full output — no summaries.

</output_format>

## References

Mode-specific references are listed in each block file.

- [`assets/report-template.md`](assets/report-template.md) — output format (all modes)
- [`scripts/measure.sh`](scripts/measure.sh) — deterministic structural measurements (skill mode, run before Phase 1)
- [`scripts/validate.sh`](scripts/validate.sh) — structural validation for any skill directory; run with path argument, reports PASS/WARN/FAIL
- [`evals/evals.json`](evals/evals.json) — skill mode test cases
- [`evals/trigger-evals.json`](evals/trigger-evals.json) — trigger phrase routing evals
