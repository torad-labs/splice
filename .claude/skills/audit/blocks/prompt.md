
# Prompt Audit

3-phase multi-model pipeline that finds, fixes, and identifies leverage in prompts.

## How It Works

Three models. Three cognitive shapes. Not three speeds of the same thing.

| Phase | Model | Task | Rejection Boundary |
|-------|-------|------|--------------------|
| Find | Haiku | Extract directives, detect overlaps, cross-dimensional conflicts | Do NOT suggest fixes |
| Fix | Sonnet | Resolve overlaps, add priority chains, produce revised version | Do NOT re-analyze |
| Leverage | Opus | Identify the ONE structural change with highest downstream impact | Do NOT list multiple improvements |

For the full multi-model pipeline pattern, see [`../references/multi-model-pipeline.md`](../references/multi-model-pipeline.md).

## Process

### 1. Read the Target

Read the prompt, skill, or instruction set provided via `$ARGUMENTS`. If a file path is given, read the file. If text is pasted inline, use that. If a skill name is given, find its SKILL.md.

### 2. Phase 1 — Find (Haiku)

Launch a Haiku agent via `Task tool, model: haiku, subagent_type: general-purpose`.

Build the prompt from the template in [`../phases/prompt/phase-find.md`](../phases/prompt/phase-find.md). Inline the full target prompt/skill content. Haiku extracts directives, maps dimensions, detects same-dimension overlaps, runs the cross-dimensional intersection test, checks verifiability, and flags over/under-constraint.

Haiku does NOT suggest improvements. Only finds and reports.

### 3. Phase 2 — Fix (Sonnet)

Launch a Sonnet agent via `Task tool, model: sonnet, subagent_type: general-purpose`.

Build the prompt from [`../phases/prompt/phase-fix.md`](../phases/prompt/phase-fix.md). Pass the original prompt AND the full Phase 1 output. If the target is a SKILL.md, also pass the best practices reference from [`../references/best-practices-prompt.md`](../references/best-practices-prompt.md).

Sonnet resolves overlaps, adds priority chains for unavoidable conflicts, fills under-constrained dimensions, and produces a complete revised version.

Sonnet does NOT re-analyze. It trusts Phase 1's findings.

### 4. Phase 3 — Leverage (Opus)

Launch an Opus agent via `Task tool, model: opus, subagent_type: general-purpose`.

Build the prompt from [`../phases/prompt/phase-leverage.md`](../phases/prompt/phase-leverage.md). Pass ALL three artifacts: original prompt, Phase 1 analysis, Phase 2 revision.

Opus identifies the ONE structural change — a directive, structural choice, or missing concept — that would produce the largest downstream improvement. Output format is verifiable: IF CHANGED / IF NOT CHANGED / EVIDENCE.

Opus does NOT list multiple improvements. One lever. One move.

### 5. Present Results

Combine all three phases into a consolidated report:

```
## Prompt Audit Report

### Phase 1: Analysis (Haiku)
[directive map, overlaps, conflicts, over/under-constraint, verifiability]

### Phase 2: Revision (Sonnet)
[changes made, priority chains added, complete revised version]

### Phase 3: Leverage (Opus)
LEVERAGE POINT: [the single structural bottleneck]
IF CHANGED: [what improves]
IF NOT CHANGED: [what stays brittle]
EVIDENCE: [which findings trace here]
PROPOSED CHANGE: [the actual change]
```

## Key Rules

- Each phase runs sequentially — Phase 2 depends on Phase 1, Phase 3 depends on both.
- Never add directives. The goal is fewer, sharper boundaries — not more.
- Each directive must control exactly ONE dimension. If it controls two, split it.
- The space BETWEEN boundaries is where the model's capability lives. Protect it.

## References

- [`../references/rejection-framework.md`](../references/rejection-framework.md) — the theory (dimensions, overlaps, goldilocks zone)
- [`../references/best-practices-prompt.md`](../references/best-practices-prompt.md) — directive quality dimensions, goldilocks zone, anti-patterns for prompt mode
- [`../phases/prompt/phase-find.md`](../phases/prompt/phase-find.md) — Haiku prompt template
- [`../phases/prompt/phase-fix.md`](../phases/prompt/phase-fix.md) — Sonnet prompt template
- [`../phases/prompt/phase-leverage.md`](../phases/prompt/phase-leverage.md) — Opus prompt template
- [`../references/multi-model-pipeline.md`](../references/multi-model-pipeline.md) — shared orchestration pattern
