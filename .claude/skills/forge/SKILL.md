---
name: forge
description: >-
  This skill should be used when the user asks to "forge this", "build from
  crystallized", "implement the plan", "forge the PRD", "build from product",
  or wants to implement a crystallized feature product with production quality
  baked in. Runs a 6-phase multi-model pipeline: Haiku ingests crystallized
  product output (HTML or markdown), Sonnet builds each component with harden
  constraints active, Opus fortifies the result. Constructive dual of harden.
user-invocable: true
disable-model-invocation: false
argument-hint: "[path to product directory or markdown files]"
---

# Forge

<context>
Constructive dual of harden. Takes crystallized product output and implements
it with harden's quality concerns baked in during construction. The pipeline
becomes: crystallize → **forge** → harden (confirmation only).

Same cognitive shapes as harden (Haiku/Sonnet/Opus), applied at construction
time instead of post-hoc.

## Phase Map

| Phase | Block | Model | Receives | Produces | Rejection Boundary |
|-------|-------|-------|----------|----------|--------------------|
| Ingest | 1-ingest | Haiku | Path to product directory | Structured extraction of all product files (HTML or MD) + meta.json | Extract only — do not interpret |
| Plan | 2-plan | Sonnet | Ingest output + codebase file listing | BUILD_ITEMS, BUILD_WAVES, DEPLOY_STEPS, INTERFACE_CONTRACTS, CONSTRAINT_MATRIX, rejection mappings | Do not write code — plan only |
| Build | 3-build | Sonnet ×N | One BuildItem per invocation | Implemented component files + self-check | Implement only the assigned component |
| Verify | 4-verify | Haiku ×N | File paths + harden constraints + criteria | Per-check PASS/FAIL with evidence | Find gaps only — do not fix |
| Assemble | 5-assemble | Sonnet | All component paths + connection table | Integration layer (imports, wiring, entry points) | Wire components — do not re-implement them |
| Fortify | 6-fortify | Opus | All files + ingest + verify + assembly | ONE weakness + fix, or CLEAN confirmation | Report one weakness maximum |

```
INGEST → PLAN → [BUILD → VERIFY]×W → ASSEMBLE → FORTIFY → SUMMARY
(haiku)  (sonnet)  (sonnet) (haiku)    (sonnet)   (opus)
                   wave-parallel:
                   independent items
                   in same wave run
                   concurrently
```

## Context Architecture

The main context is the **orchestrator**. It stays lean by delegating heavy
work to blocks — isolated subagents with their own context windows. Source code
never enters the orchestrator's context; the orchestrator holds paths and
verdicts only.

**What the orchestrator holds:**
- `$ARGUMENTS` path to crystallized product directory
- Build item list (names and statuses, not code)
- Per-component verify summaries (pass/fail + finding count)
- Assembly status
- Fortify result
- Observations from build blocks (propagated to subsequent builds)

**What stays in blocks:**
- Source code (blocks read files via Read tool)
- Implementation details (build block writes files, orchestrator sees paths)
- Full HTML content (ingest block parses, orchestrator sees structured text)
- Verification evidence (verify block inspects, orchestrator sees verdicts)
- Architectural analysis (fortify block reasons, orchestrator sees result)

**Why this separation matters:** Forge builds entire features — the combined
source code of all components would overflow the orchestrator's context. Each
block operates on its own subset: build reads/writes one component, verify
reads one component, fortify reads everything but runs once.

## When NOT to Use Forge

Do NOT use forge for code review, post-hoc quality checking, or hardening
existing code — use **harden** for that. Forge builds new implementations
from crystallized product output. Harden audits existing implementations.
</context>

<instructions>
## Execution

Read [`blocks/0-orchestrator.md`](blocks/0-orchestrator.md) for the full
step-by-step execution protocol. Run phases 1-6 sequentially, launching
each block as specified in the orchestrator protocol.
</instructions>

<constraints>
## Priority Chains

When directives conflict, apply these chains:

1. **Scope over constraint satisfaction**: If satisfying a Build constraint
   requires accessing a dependency component not in the BuildItem, implement
   the constraint as far as the component's own code allows and report the
   gap as an OBSERVATION. Scope boundary wins — the builder sees only its
   own component.

2. **Assembly progress over per-component perfection**: If a component's
   verify finding has severity `blocks-assembly` and both rebuilds fail —
   continue assembly and escalate to Fortify. Assembly progress wins.

3. **Progress over open question resolution**: If a PRD open question affects
   implementation, proceed with the most conservative option (fewest
   assumptions, narrowest behavior) and flag it in the report. Progress wins.

4. **Fortify over Verify**: If Fortify changes a file that Verify already
   passed, Fortify takes priority. Opus sees structural patterns across all
   components that per-component Haiku cannot.

</constraints>

## Block Reference

| Block | File | Model | Per-component | Execution | Isolation |
|-------|------|-------|---------------|-----------|-----------|
| orchestrator | `blocks/0-orchestrator.md` | — | no | read by orchestrator | — |
| ingest | `blocks/1-ingest.md` | haiku | no | once | none |
| plan | `blocks/2-plan.md` | sonnet | no | once | none |
| build | `blocks/3-build.md` | sonnet | yes | wave-parallel | worktree |
| verify | `blocks/4-verify.md` | haiku | yes (×2 forks) | after wave merge | none |
| assemble | `blocks/5-assemble.md` | sonnet | no | once | none |
| fortify | `blocks/6-fortify.md` | opus | no | once | worktree |

<constraints>
## Key Rules

1. Blocks read their own files. The orchestrator sends paths, not contents.
2. Build waves respect the dependency graph. Items with no mutual
   dependencies run concurrently within the same wave.
3. Each Build invocation receives only its component's context — not the
   full plan, not other components' details.
4. Verify runs after each Build, not after all Builds.
5. Maximum 2 rebuild attempts per component. On the second attempt, include
   prior retry failures alongside new ones.
6. Assemble wires components — it does not modify them.
7. Fortify runs ONCE after assembly and sees everything.
8. Open questions get the most conservative implementation: fewest assumptions,
   narrowest behavior.
9. Immune memory entries are active constraints — treat them as hard rules,
   not preferences.
10. Observations from each wave are accumulated and forwarded to all items
    in the next wave. Items within the same wave do not see each other's
    observations.
11. Deploy-type items (publish, release, deploy) are listed as DEPLOY_STEPS,
    not BUILD_ITEMS. They appear in the forge report as manual post-forge
    actions.

</constraints>

<output_format>
## Output

Forge produces:

### 1. Code Files

Actual implementation written to the codebase by Build and Assemble blocks.

### 2. Forge Report

```markdown
## Forge Report

### Product
- Title: [from ingest]
- Slug: [from ingest]
- ELBO: [score]

### Build Results
| Component | Type | Status | Verify | Retries | Escalated |
|-----------|------|--------|--------|---------|-----------|

### Integration
- Assembly status: [complete | partial]
- Connection verification: [pass count / total]
- INTEGRATION_ISSUES: [count, or none]

### Fortify
- Result: [WEAKNESS_FOUND | CLEAN]
- [If weakness: description + fix summary]
- [If clean: strongest area + watch area]
- [If unstable fix: flag here]

### Open Questions
| Question | Conservative Choice | Component |
|----------|---------------------|-----------|

### Acceptance Criteria
| Component | Criteria Met | Total | Pass Rate |
|-----------|-------------|-------|-----------|

### Deploy Steps (Manual)
| Step | Pre-conditions | Action |
|------|---------------|--------|

### Ready for Harden
[Yes — all components pass, assembly complete, fortify clean/fixed]
[No — list unresolved items including any ESCALATED components]
```

</output_format>

## Boundary Summary

| Boundary | Direction | Transformation | Key Risk |
|----------|-----------|---------------|----------|
| Ingest → Plan | forward | reshaping (HTML/MD → structured text, lossless) | Missing sections propagate as missing constraints |
| Plan → Build | forward | lossy (full plan → single component context), interface contracts preserve cross-component shapes | Builder sees its own contract but not other components' internals |
| Build → Verify | forward | reshaping (worktree merge → file list + status) | Verify re-reads from merged main branch, not from worktree |
| Verify → Build (retry) | backward | lossy (full verification → failures only) | Builder does not re-verify passes |
| All Verify → Assemble | forward | lossy (per-component → status only) | Assemble does not see individual findings |
| Assemble → Fortify | forward | none (Opus needs everything) | No information lost — Opus reads all files |

## Scripts

- [`scripts/validate.sh`](scripts/validate.sh) — structural validation; run with skill path, reports PASS/WARN/FAIL

## Evals

- [`evals/trigger-evals.json`](evals/trigger-evals.json) — trigger accuracy cases

## References

- [`references/multi-model-pipeline.md`](references/multi-model-pipeline.md) — shared cognitive shape map and orchestration pattern
- [`references/harden-concerns.md`](references/harden-concerns.md) — harden detection targets inverted into construction constraints
- [`references/html-extraction.md`](references/html-extraction.md) — CSS selector mapping for crystallized product HTML
- [`references/md-extraction.md`](references/md-extraction.md) — heading/section mapping for crystallized product markdown
