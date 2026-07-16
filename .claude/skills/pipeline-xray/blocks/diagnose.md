# Phase 2: Diagnose (Sonnet)

Subagent prompt. Orchestrator provides seam map, compact manifests, and paths
to Phase 1 temp files + agent source.

## Prompt

```
You receive two layers of analysis for a multi-block agent pipeline:
1. A SEAM MAP showing the pipeline architecture, blocks, and data flow
2. PER-BLOCK DATA FLOW ANALYSES from Phase 1 (input/output maps, seam contracts,
   format assumptions per block)

Your job is to find cross-seam integrity issues — problems that only become
visible when you look at how blocks connect, not at blocks in isolation.

## Seam Map

{seam_map}

## Phase 1 Compact Manifests

{phase_1_manifests}

## Phase 1 Temp File Paths (read these for full data flow maps)

{phase_1_temp_paths}

## Agent Source Paths (read these for types, tools, contract)

{agent_source_paths}

## Instructions

Read all Phase 1 temp files. Read the agent's types.ts, tools.ts, and
contract.ts. Then analyze across all seams.

### Category 1: Dead Fields

Fields written by one block but never read by any downstream block.
Cross-reference Phase 1 WRITES from each block against READS from all
downstream blocks.

A field is dead if:
- Block A writes state.X
- No block after A reads state.X
- The field is not part of the final output (html, qaReport, metadata)

For each dead field: which block writes it, what type it carries, which
blocks could plausibly consume it.

### Category 2: Orphan Reads

Fields read by a block but never written by any upstream block.
These rely on initialization or external input that may not be guaranteed.

Cross-reference Phase 1 READS from each block against WRITES from all
upstream blocks.

For each orphan: which block reads it, where it expects the data to come
from, what happens if it's missing.

### Category 3: Format Drift

QA patterns or validation regexes that match output from a previous
architecture but not the current one.

Cross-reference Phase 1 FORMAT ASSUMPTIONS against the actual output
shape of the upstream block. If a regex expects a pattern that the
upstream block no longer produces, that's format drift — a false positive
waiting to happen.

For each drift: the regex, what it expects, what upstream actually
produces, whether this causes false passes or false fails.

### Category 4: QA Blind Spots

Seams with no validation coverage. For each seam in the pipeline:
- Is there any block that validates the data crossing this seam?
- If QA only runs at the end, which seams are unchecked?
- What failure modes could pass through unchecked seams?

### Category 5: Type Contract Gaps

Fields in tool schemas that aren't reflected in TypeScript types, or
fields in TypeScript types that aren't in tool schemas.

Compare tools.ts required/optional fields against types.ts interfaces
for each tool output type.

For each gap: the field name, which side has it, what the consequence is.

### Category 6: Injection Order Risks

Post-process steps that depend on specific content being present or absent.
If post-process injects content at a specific HTML location, and the
upstream block's output format varies, the injection may fail silently.

For each risk: the injection, what it depends on, what happens when the
dependency is missing.

### Category 7: Shadow Dependencies

Block A depends on Block B's output format without an explicit type
contract. The dependency exists in code (string matching, regex,
position-based parsing) but not in types.

For each shadow dependency: which blocks, what the implicit contract is,
what would break if the upstream block changed its output format.

## Two-Step Output

STEP A — Write the full analysis to `/tmp/pipeline-xray-diagnose.md`.
Use these exact section headers (gate validation depends on them):

DEAD FIELDS:
[per-field: writer block, type, potential consumers]

ORPHAN READS:
[per-field: reader block, expected source, missing-data behavior]

FORMAT DRIFT:
[per-finding: regex, expected pattern, actual upstream output]

QA BLIND SPOTS:
[per-seam: upstream -> downstream, what's unchecked]

TYPE CONTRACT GAPS:
[per-gap: field, tool schema vs TypeScript type]

INJECTION ORDER RISKS:
[per-risk: injection, dependency, failure mode]

SHADOW DEPENDENCIES:
[per-dependency: blocks, implicit contract, breakage scenario]

SEAM FINDINGS:
[summary table: seam, categories affected, severity]

STEP B — Return this compact summary to the orchestrator:

DEAD FIELDS: [N] | ORPHAN READS: [N] | FORMAT DRIFT: [N] | BLIND SPOTS: [N]
CONTRACT GAPS: [N] | INJECTION RISKS: [N] | SHADOW DEPS: [N]
CRITICAL: [list of critical findings, one line each]

Do not suggest fixes. Only find and report.
```
