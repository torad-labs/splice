# Phase 3: Leverage (Opus)

Subagent prompt template. Orchestrator fills `{placeholders}` before dispatch.

## Prompt

```
You receive three layers of analysis for a multi-block agent pipeline:
1. An AGENT MAP showing the block architecture, boundaries, and model assignments
2. PER-BLOCK ANALYSES from Phase 1 (directive maps + boundary specs per block)
3. A CROSS-BLOCK SYNTHESIS from Phase 2 (per-boundary findings, duplication,
   QA coverage, elimination candidates)

Your job is not to find more problems or suggest more improvements.

Your job is to identify the ONE structural weakness across the entire agent —
not a directive-level fix, but a design-level pattern that, if changed, would
prevent entire categories of cross-block conflicts from being possible.

This is the architectural equivalent of prompt-audit's leverage point, but at
the agent level: the boundary, contract, or structural choice that constrains
everything downstream.

You are not bound by Phase 2's no-merge constraint. If the leverage point is
boundary elimination or block consolidation, report it.

## Agent Map

{agent_map}

## Phase 1 Compact Manifests

{phase_1_manifests}

## Phase 2 Compact Summary

{phase_2_summary}

## Phase 1 Temp File Paths (read these for full directive maps + boundary specs)

{phase_1_temp_paths}

## Phase 2 Temp File Path (read this for full cross-block synthesis)

{phase_2_temp_path}

## Instructions

Read all layers. Hold them in tension. Look for the pattern:

WITHIN blocks:
- What do multiple per-block issues have in common?
- Is there a directive pattern that repeats across blocks and causes the same
  class of issue each time?

AT BOUNDARIES:
- Which boundary forced the most Phase 2 findings?
- Is there an implicit assumption that, if made explicit in the type contract,
  would eliminate a category of conflicts?
- Are there elimination candidates from Phase 2 that point to unnecessary
  boundaries? (transformation: none + no implicit assumptions = the two blocks
  may be one block)
- Do the model assignments match the cognitive demands at each boundary?

AT THE PIPELINE LEVEL:
- Is the block decomposition correct? (Blocks that should be merged? Split?
  Pipeline stages that are missing?)
- Is context being lost at boundaries? (Check the FORWARDED CONTEXT and
  TRANSFORMATION fields from boundary specs)
- Does the QA block have visibility into what it needs to check?

COMMON AGENT-LEVEL LEVERAGE POINTS:
- Technique/Contract fusion: prompts that mix "how to do the creative work"
  with "what the output format must be" at the same priority level
- Implicit contracts: blocks assume upstream output has properties that
  aren't in the type definitions or tool schemas
- Creative constraint stacking: too many rules on the creative block,
  forcing the model to juggle constraints instead of generating quality output
- QA blind spots: QA checks structural properties but can't validate the rules
  that actually matter for output quality
- Unnecessary boundaries: a boundary with no transformation that exists only
  because the blocks were conceived separately, not because they need separation
- Duplication as structural smell: the same rule appearing in 3+ blocks suggests
  it should be a pipeline-level contract, not a per-block directive

## Output

Report one leverage point. If multiple candidates, select the one where a
change prevents the largest number of cross-block conflicts.

LEVERAGE POINT: [the structural weakness — name it precisely]

IF CHANGED: [which Phase 2 per-boundary findings disappear, which Phase 1
per-block issues become unnecessary, which QA gaps close. Be specific about
finding numbers.]

IF NOT CHANGED: [what category of problems keeps recurring across pipeline runs.
Not specific bugs — the class of bugs that this structural choice enables.]

EVIDENCE: [which findings from Phase 1 and Phase 2 trace to this point.
Reference by block name, boundary name, and finding number.]

PROPOSED CHANGE: [the design-level change, stated precisely enough to implement.
This should be about pipeline architecture, boundary design, contract structure,
or prompt organization — not about specific directive wording. May include
boundary elimination or block consolidation.]

One lever. One move. No lists.
```
