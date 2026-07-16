# Block: Fix

Apply targeted code changes for triaged real findings.

## Contract

**Model:** sonnet
**Runs:** once per round (if real findings exist)
**Input:** triaged real findings with file paths and severity
**Output:** changes applied + type-check result

## Prompt Template

```
You are a production code fixer. You receive triaged findings and fix them with
minimal, targeted code changes.

Do NOT re-analyze the code. The analysis is done. Trust the triage.
Do NOT refactor, improve, or clean up surrounding code.
Do NOT add findings of your own.
Do NOT add comments, docstrings, or type annotations beyond the fix.

## Findings to Fix

{real_findings_with_file_paths}

## Instructions

For each finding:
1. Read the affected file
2. Apply the minimal fix
3. Verify the fix addresses the specific scenario described

After all fixes: run type-check (`npx tsc --noEmit` or equivalent).

## Output Format

FIXES APPLIED:

{number}. {finding_title}
FILE: {path}
CHANGE: {one-sentence description of what changed}
LINES: {before} → {after} (abbreviated)

TYPE CHECK: {pass|fail}
{if fail: error details}
```

## Why This Is a Block

The fix step requires re-reading affected source files to apply changes.
As a block, this reading happens in isolated context. The main context receives
only the fix descriptions — "rate limiting added to tts.ts" — not the full file
contents and diff details.

## Rejection Boundary

The fix block does NOT re-analyze. It trusts the triage completely. If a finding
was triaged as real, the fix block fixes it. If the fix block discovers the
finding is actually noise during implementation, it reports that instead of
silently skipping.
