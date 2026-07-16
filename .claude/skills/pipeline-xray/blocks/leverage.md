# Phase 3: Leverage (Opus)

Subagent prompt template. Orchestrator fills `{placeholders}` before dispatch.

## Prompt

```
You receive three layers of analysis for a multi-block agent pipeline:
1. A SEAM MAP showing the pipeline architecture, blocks, and data flow
2. PER-BLOCK DATA FLOW ANALYSES from Phase 1 (input/output maps per block)
3. A CROSS-SEAM DIAGNOSIS from Phase 2 (dead fields, orphan reads, format
   drift, blind spots, contract gaps, injection risks, shadow dependencies)

Your job is not to find more problems or suggest per-finding fixes.

Your job is to identify the ONE structural pattern that, if changed, would
eliminate the most seam integrity issues. Not a field-level fix — a
design-level change to how blocks connect, how data flows, or how contracts
are structured.

## Seam Map

{seam_map}

## Phase 1 Compact Manifests

{phase_1_manifests}

## Phase 2 Compact Summary

{phase_2_summary}

## Phase 1 Temp File Paths (read these for full data flow maps)

{phase_1_temp_paths}

## Phase 2 Temp File Path (read this for full cross-seam diagnosis)

{phase_2_temp_path}

## Instructions

Read all layers. Hold them in tension. Look for the pattern:

AT THE DATA FLOW LEVEL:
- Which seam is the source of the most downstream findings?
- Is there a field or type that, if restructured, would collapse multiple
  dead fields and orphan reads into a single well-defined contract?
- Are there shadow dependencies that trace to the same missing type?

AT THE VALIDATION LEVEL:
- Do format drift findings and QA blind spots share a common cause?
- Is there a validation gap that, if closed at the right seam, would
  catch multiple categories of issues before they propagate?

AT THE ARCHITECTURE LEVEL:
- Is the pipeline decomposition correct for data flow? (Blocks that pass
  data through unchanged suggest unnecessary seams)
- Are there blocks doing both generation and validation that should be
  separated? Or validation blocks that can't see what they need to check?
- Does the contract system (types.ts + tools.ts + contract.ts) cover all
  seams, or do some seams rely on implicit conventions?

COMMON PIPELINE-LEVEL LEVERAGE POINTS:
- Missing intermediate type: two blocks communicate through PipelineState
  fields without a named type contract, creating shadow dependencies
- Over-broad state object: PipelineState carries everything, making it
  invisible which blocks actually need which fields
- Validation at the wrong seam: QA runs at the end but issues originate
  at earlier seams where they could be caught cheaper
- Format coupling: downstream blocks parse upstream output by string
  pattern instead of structured type, creating silent breakage on format change
- Dead contract fields: type definitions that grew over time without
  pruning, carrying fields no block consumes

## Output

Report one leverage point. If multiple candidates, select the one where a
change prevents the largest number of cross-seam findings from being possible.

LEVERAGE POINT: [the structural pattern — name it precisely]

IF CHANGED: [which Phase 2 findings disappear, which dead fields become
unnecessary, which shadow dependencies get explicit types. Be specific
about finding counts and categories.]

IF NOT CHANGED: [what category of problems keeps accumulating across
pipeline iterations. Not specific bugs — the class of seam integrity
issues this structural choice enables.]

EVIDENCE: [which findings from Phase 1 and Phase 2 trace to this point.
Reference by block name, seam, and finding category.]

PROPOSED CHANGE: [the design-level change, stated precisely enough to
implement. This should be about type structure, pipeline architecture,
contract design, or validation placement — not about specific field names
or code patches. May include seam elimination or block restructuring.]

One lever. One move. No lists.
```
