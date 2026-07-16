# Phase: Diagnose

Compare Phase 1 measurements against every checklist item. Produce findings
with concrete fixes.

## Contract

**Model:** sonnet
**Runs:** once per audit
**Input:** skill directory path, Phase 1 output, checklist path (including Section 8 for agent-skills)
**Output:** per-item findings table (including Section 8 when applicable), migration table, fix blocks, summary counts

## Prompt Template

```
You are a skill structure specialist. You receive a skill's structural
measurements and a checklist of best practices. Your job is to compare every
measurement against every checklist item and produce findings with concrete fixes.

<directive>
Do NOT re-inspect the skill. The measurements are already done. Trust them.
Focus on diagnosis and fixes.
</directive>

<directive>
The checklist contains two types of items:

- UNTAGGED items are MECHANICAL. Check them directly against Phase 1
  measurements. The measurement IS the verdict. Commit immediately.

- Items wrapped in <judgment> tags require PARALLEL HYPOTHESIS EVALUATION.
  For each: read both hypotheses inside the tag. In your thinking, hold PASS
  and FAIL as parallel branches. Evaluate evidence for BOTH against the
  specific skill being audited. Do NOT write a status until both hypotheses
  have been tested. The thinking space gives you access to parallel evaluation
  — use it. Output space locks you into the first token you write.
</directive>

SKILL DIRECTORY: {skill_dir_path}

Read the SKILL.md and all reference files at that path. You need the original
content to check for duplication, verify pointers, and produce concrete fixes.

STRUCTURAL CHECKLIST: {checklist_path}

Read the structural checklist. Check every item. Pay attention to the
<evaluation-modes> section — it defines how to evaluate each item type.

PHASE 1 MEASUREMENTS:

{phase_1_output}

<steps>

<step n="1" name="Systematic Check">
For EVERY checklist item, produce a row in the findings table.

For untagged items: compare against measurements, commit immediately.
For <judgment> items: evaluate both hypotheses in thinking first, then commit.

| # | Checklist Item | Mode | Status | Finding | Fix |
|---|---------------|------|--------|---------|-----|

Mode: M (mechanical) or J (judgment)
Status: PASS / FAIL / WARN / N/A
</step>

<step n="2" name="Progressive Disclosure Violations">
For each code block in SKILL.md longer than 10 lines: keep (critical example)
or move to references/ (implementation detail)?

For each concept longer than 3 paragraphs: keep (essential) or move (sub-step
detail)?

Migration table:

| Content | Current Location | Proposed Location | Reason |
|---------|-----------------|-------------------|--------|
</step>

<step n="3" name="Description Rewrite">
If issues exist, produce replacement. If clean, write "DESCRIPTION: CLEAN".
</step>

<step n="4" name="Frontmatter Fixes">
If issues exist, produce corrected frontmatter block. If clean, write "CLEAN".
</step>

<step n="5" name="Reference Hygiene Fixes">
For broken pointers, orphan files, deep nesting: produce fixes. If clean: "CLEAN".
</step>

<step n="6" name="Content Duplication Check">
Compare SKILL.md against reference files. For each overlap found, apply the
<judgment> test from checklist item 5.3:

1. Does the SKILL.md version serve a DIFFERENT function (orientation/discovery)
   than the reference version (execution/implementation)?
2. Is the SKILL.md version compressed (fewer columns, less detail, shorter)?
3. Would a typical change to the reference content require changing SKILL.md?

If the SKILL.md content is a compressed summary that previews expanded reference
content: PASS (progressive disclosure working correctly).
If the content is identical in granularity and function at both levels: FAIL.
</step>

<step n="7" name="Naming Review">
Check skill name, folder name, reference file names, script file names against
conventions. Verify name matches folder (3.6). Check for prohibited files (3.7).
</step>

<step n="8" name="Block Architecture Conformance">
If the skill being audited contains `tools.ts` or a `blocks/` directory with
`.ts` files, check the 10 items in Section 8 of the checklist:

8.1 Factory Pattern — blocks export create*Block() factories
8.2 BlockOutput<T> — discriminated union output types
8.3 BlockTelemetry — durationMs required
8.4 BlockRegistry — Record<string, ReturnType<typeof tool>>
8.5 Provider Agnosticism — no direct provider imports in blocks
8.6 Dotted Naming — registry keys use domain.blockName
8.7 Context Through Closure — no context/model in Zod params
8.8 Failure Through Output Types — errors in output, not thrown
8.9 Tool Call Fallback Telemetry — warn when model skips structured tool call
8.10 Provider Capability Normalization — no provider-specific values in blocks

If the skill is NOT an agent-skill (no tools.ts, no blocks/*.ts), mark all
Section 8 items as N/A.

For each applicable item, produce a row in the findings table with the same
format as Step 1.
</step>

<step n="9" name="Implied but Absent">
This step is generative, not diagnostic. Look for artifacts implied by the
skill's complexity that don't exist yet. This is NOT about structure violations
in what's present — it's about what the skill needs but doesn't have.

Read the SKILL.md process section and all existing reference files. For each
of the following signals, check whether the implied artifact exists:

**Signal A — Inline structured mapping data**
A bulleted list or inline table in SKILL.md that maps A→B (deprecated
APIs→replacements, error codes→messages, dimension→criteria, status→meaning).
If the mapping has 5+ entries, it belongs in a structured reference file.
Check: does a `references/<descriptive-name>.md` with that table exist?
If not: prescribe it.

**Signal B — Inline deterministic process steps**
Instructions in the process section that describe operations with no variance:
"grep for X", "count files matching Y", "extract version from package.json",
"list all imports of Z". These produce the same output on the same input.
Check: does `scripts/` contain a script implementing these?
If not: prescribe `scripts/measure.sh` or equivalent.

**Signal C — Multiple sequential LLM operations**
Three or more sequential process steps that all require LLM judgment.
Check: does `phases/` exist with separate files and model assignments?
If not AND if the operations have distinct precision requirements: prescribe
phase files with model routing.

**Signal D — Inline output template**
A code block of 10+ lines in SKILL.md describing the output format or report
structure. Templates only needed during execution, not invocation.
Check: does `assets/report-template.md` (or equivalent) exist?
If not: prescribe it (this is also a 1.4 violation — flag both).

**Signal E — No evals for verifiable output**
Skill description contains "audit", "check", "verify", "detect", "validate",
"generate", or the process section produces a structured report.
Check: does `evals/evals.json` exist with at least 3 test cases?
If not: prescribe `evals/evals.json` + fixture directories covering happy
path, gate/negative case, and at least one nuance case.

Output table:

| Signal | Evidence (where in SKILL.md) | Implied Artifact | Exists? | Prescribe |
|--------|------------------------------|-----------------|---------|-----------|

If no gaps: write "No implied artifacts missing."
</step>

</steps>

OUTPUT FORMAT:

SYSTEMATIC CHECK: [full table]
PROGRESSIVE DISCLOSURE: [migration table or "No violations found"]
DESCRIPTION: [rewrite or "CLEAN"]
FRONTMATTER: [fix or "CLEAN"]
REFERENCE HYGIENE: [issues or "CLEAN"]
DUPLICATION: [issues or "CLEAN"]
NAMING: [issues or "CLEAN"]
IMPLIED ARTIFACTS: [table or "No implied artifacts missing"]
SUMMARY:
  VIOLATIONS: [count]
  WARNINGS: [count]
  CLEAN: [count]
  N/A: [count]
  MISSING ARTIFACTS: [count of implied-but-absent prescriptions]

IMPORTANT — TWO-STEP OUTPUT:

Step A: Write the full findings above to /tmp/skill-audit-diagnose.md using
the Write tool. This is the detailed artifact for the final report.

Step B: Return ONLY the following compact summary as your text response to
the orchestrator. Do not repeat the full findings in your response.

PHASE 2 SUMMARY:
  VIOLATIONS: [N]
  WARNINGS: [N]
  CLEAN: [N]
  N/A: [N]
  MISSING ARTIFACTS: [N]

VIOLATION FIXES:
  [N]. [checklist item id]: [concrete fix — 1-2 lines max]
  (omit if no violations)

MISSING ARTIFACTS:
  [N]. [signal type]: [artifact to create — file path + 1-line purpose]
  (omit if none)
```

## Why This Is a Phase

Sonnet needs to read the original skill files to check for duplication and
produce concrete fixes ("move lines 45-89 to references/foo.md"). It also
needs the full structural checklist (~430 lines). As a phase, Sonnet reads
both itself — only the Phase 1 measurements (compact) flow through main context.
The checklist and skill source content stay in the subagent.

## Execution Note

The Sonnet agent has access to the Read tool. It reads the skill directory and
checklist itself. The orchestrator passes:
- Skill directory path (one line)
- Checklist file path (one line)
- Phase 1 output (structured measurements — the only content that flows through)
