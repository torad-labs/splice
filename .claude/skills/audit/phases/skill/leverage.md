# Phase: Leverage

Identify the ONE structural choice with highest downstream impact.

## Contract

**Model:** opus
**Runs:** once per audit, after Phase 1 and Phase 2
**Input:** skill directory path, Phase 1 output, Phase 2 output
**Output:** single leverage point with 5 required fields

## Prompt Template

```
You receive the results of a structural audit on a Claude Code skill.

Your job is not to find more violations or suggest more fixes.

Your job is to identify the ONE structural choice — in the skill's organization,
disclosure architecture, or file decomposition — that would produce the largest
downstream improvement to the skill's effectiveness when invoked by Claude.

SKILL DIRECTORY: {skill_dir_path}

Read the SKILL.md and all supporting files at that path to understand the
original skill structure.

Read /tmp/skill-audit-inspect.md for the full Phase 1 inspection report.
Read /tmp/skill-audit-diagnose.md for the full Phase 2 findings.

PHASE 1 COMPACT MANIFEST:

{phase_1_manifest}

PHASE 2 COMPACT SUMMARY:

{phase_2_summary}

INSTRUCTIONS:

Read the original skill, then hold all three artifacts in tension:

- What do multiple Phase 2 violations have in common? (Shared structural cause)
- What organizational choice forced the most Phase 2 findings?
- What single assumption, if questioned, would eliminate an entire category?
- Is there a file boundary that SEEMS correct but forces content into the wrong
  disclosure level?
- Would the skill work better with a different decomposition across files?

The lever is often not a single file move — it might be:
- A reconceptualization of what SKILL.md is FOR in this specific skill
- A missing intermediate document that would bridge SKILL.md and references/
- An organizational principle that would make multiple fixes unnecessary

OUTPUT FORMAT:

LEVERAGE POINT: [specific structural element]
IF CHANGED: [concrete prediction — which Phase 2 violations disappear]
IF NOT CHANGED: [what stays brittle even after Phase 2 fixes]
EVIDENCE: [which Phase 1 measurements and Phase 2 findings trace here]
PROPOSED CHANGE: [the actual structural change — precise enough to implement]

MISSING ARTIFACTS:
List the files this skill needs but doesn't have. Only include files that
are clearly implied by the skill's domain and complexity. Do not pad.
If none are missing, write "None — skill is fully decomposed."

| File | Purpose | Should Contain |
|------|---------|---------------|
| scripts/measure.sh | [why needed] | [what operations go here] |
| phases/classify.md | [why needed] | [which model, what input/output] |
| references/rubric.md | [why needed] | [what structured data] |
| evals/evals.json | [why needed] | [how many cases, what coverage] |
(include only files that don't exist and are genuinely needed)

Do not list multiple leverage points. One lever. One move. The missing
artifacts table is additive — it names what to build after the lever is pulled.
```

## Why This Is a Phase

Opus needs the full picture — original skill structure, Phase 1 measurements,
and Phase 2 diagnosis. As a phase, Opus reads the skill files itself. The
orchestrator passes compact Phase 1 + Phase 2 outputs. The skill's source
content (which could be large) stays in the subagent's context.

## Execution Note

The Opus agent has access to the Read tool. It reads the skill directory itself.
The orchestrator passes:
- Skill directory path (one line)
- Phase 1 output (compact measurements)
- Phase 2 output (compact findings + summary counts)
