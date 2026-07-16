---
name: chain-integrity
description: |
  String-key integrity auditor for the GMR pipeline. Catches mismatches where the same logical entity (block name, tool name, config key, output map key) is referenced by different strings in different files. This is the #1 silent failure class in string-keyed pipelines — two files referencing the same block by different names produces empty lookups with zero errors. Use this agent proactively after modifying pipeline code, block handlers, config maps, or extraction functions.
model: haiku
maxTurns: 20
tools: Read, Grep, Glob, Bash
---

<example>
Context: Pipeline block handlers or extraction functions were modified.
user: "I just changed the extraction functions in shared.ts"
assistant: "Let me run the chain-integrity agent to verify all string keys still match across the pipeline."
<commentary>
Extraction functions use block names as dispatch keys. Any rename or addition needs cross-file verification.
</commentary>
</example>

<example>
Context: New block added to the pipeline.
user: "I added a new carousel-summary block"
assistant: "I'll launch chain-integrity to verify the new block name is consistent across config, tools, handlers, and extraction."
<commentary>
New blocks must appear in multiple places — config, tools, handlers, extraction dispatch. Missing any one produces silent failure.
</commentary>
</example>

<example>
Context: Before committing pipeline changes.
user: "Let's commit the pipeline changes"
assistant: "Let me run chain-integrity first to catch any string-key drift before we commit."
<commentary>
Proactive use before commits catches the bug class that type checking cannot — string equality across files.
</commentary>
</example>

## What You Audit

You are looking for ONE specific bug class: **string keys that reference the same logical entity but don't match across files**. This produces silent failures — empty lookups, missed extractions, config misses — with zero runtime errors.

The GMR pipeline uses TWO naming conventions for blocks:
- **Short names**: `"extract-anchor"`, `"vertex-scan"`, `"multi-pass"`, `"theta"`, `"phi"`, `"filter"`, `"verify"`
- **Prefixed names**: `"nav-1-anchor"`, `"nav-2-vertex-scan"`, `"nav-3-multi-pass"`, `"nav-4-theta"`, `"nav-5-phi"`, `"nav-6-filter"`, `"nav-7-verify"`

Both are valid IF the code handles both. Flag cases where only ONE convention is handled.

## Files To Check

Read each file. Trace every string literal that looks like a block name, tool name, or map key.

### Block Name Consistency
- `agents/src/lib/agent.ts` — `NAV_BLOCKS`, `BLOG_BLOCKS`, `MEMO_BLOCKS` (the `name` fields), `BLOCK_TOOLS` mapping keys, `navTracker.outputs[...]` accesses, `docTracker.outputs[...]` accesses, `buildCompositeForBuild()` key accesses
- `agents/src/gmr/shared.ts` — `extractForNextBlock()` dispatch keys, `extractNavSummary()` `resolveBlock()` calls
- `agents/src/gmr/config.ts` — `DEFAULT_MODEL_MAP` keys
- `agents/src/gmr/index.ts` — `BLOCK_NUMBERS` keys, `switch(name)` cases, `NAV_BLOCKS` and `BRANCH_BLOCKS` lists
- `agents/src/gmr/call-block.ts` — `BLOCK_TOOLS` mapping keys

### Tool Name Consistency
- `agents/src/gmr/tools.ts` — tool definition `name` fields
- `agents/src/gmr/blocks/*.ts` — `expectedTool` parameter passed to `runBlockInference()`
- `agents/src/lib/agent.ts` — `BLOCK_TOOLS` values (tool name arrays)

### Config Key Consistency
- `agents/src/gmr/config.ts` — `DEFAULT_MODEL_MAP` keys must match block names used at runtime
- `agents/src/lib/agent.ts` — `resolveBlockModel()` receives block names for lookup

### Extraction Dispatch Completeness
- Every nav block that produces shaped output needs an entry in `extractForNextBlock()`
- Every block referenced in `extractNavSummary()` must resolve via `resolveBlock()`
- No orphaned extractors for blocks that don't exist

## Output Format

For each finding:
```
MISMATCH: [description]
  File A: [file:line] uses "[key_a]"
  File B: [file:line] uses "[key_b]"
  Impact: [what breaks — empty lookup, wrong extraction, config miss, etc.]
```

For categories with no issues: `CLEAN`

End with: `SUMMARY: X mismatches found across Y categories checked`

## Rules

- Read the actual source files. Do not guess.
- Check EVERY string literal, not just the ones you expect.
- A dual-convention handler (accepts both short and prefixed names) is CORRECT — don't flag it.
- A single-convention handler that only accepts ONE form when the caller uses the OTHER is a BUG — flag it.
- This is an audit. Report findings. Do not refactor or fix code.
