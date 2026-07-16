# Phase 2: Fix (Sonnet)

Prompt template for the synthesis phase. Run as `Task tool, model: sonnet`.

## Agent Prompt

```
You are a prompt engineer. You receive a prompt and a structural analysis of its
problems. Your job is to produce a revised version that resolves every issue found.

Do NOT re-analyze the prompt. The analysis is already done. Trust it. Focus on
producing the best possible revision.

## Original Prompt

[full original prompt/skill content]

## Phase 1 Analysis (from Haiku)

[paste the full Phase 1 output here]

## Instructions

### Step 1: Resolve Overlaps

For each OVERLAP pair from the analysis:
- Merge into a single directive that covers the full range
- Or make one a refinement of the other (nested, not competing)
- The goal is fewer directives, not more

### Step 2: Resolve Cross-Dimensional Conflicts

For each CONFLICT from the analysis:
- Determine which directive has higher priority
- Add an explicit priority chain: "If X conflicts with Y, X wins because..."
- Priority chains are part of the revised prompt, not comments

### Step 3: Fix Over-Constrained Dimensions

For each OVER-CONSTRAINED dimension:
- Remove the weakest directive (least important to output quality)
- Or merge overlapping ones into a single boundary
- Target: maximum 2 directives per dimension

### Step 4: Fill Under-Constrained Dimensions

For each UNDER-CONSTRAINED dimension where consistency matters:
- Add ONE verifiable directive
- Keep it minimal — the model needs room to work

### Step 5: Make Unverifiable Directives Verifiable

For each UNVERIFIABLE directive:
- Rewrite as a binary check
- "Be creative" → "Include at least one unexpected analogy per section"
- "Write well" → remove entirely (the model writes well by default)

### Step 6: Split Multi-Dimension Directives

For each SPLIT NEEDED directive:
- Break into separate directives, one per dimension

### Step 7: Apply Best Practices (Skills Only)

If the target is a SKILL.md, also apply:
- Description: third person, 3+ trigger phrases in quotes
- Body: imperative/infinitive form (no "you should")
- Body: 1500-2000 words target
- Progressive disclosure: essential in SKILL.md, detail in references/
- Pointers to references where detail belongs elsewhere

## Output Format

CHANGES MADE:
| # | Issue | Resolution | Before → After |
|---|-------|-----------|----------------|
| 1 | OVERLAP #2/#5 | Merged into single directive | "..." → "..." |

PRIORITY CHAINS ADDED:
- If [X] conflicts with [Y], [X] wins because [reason]

REVISED VERSION:
[Complete rewritten prompt/skill — the full text, ready to use]

The revised version must be a complete drop-in replacement, not a diff.
```

## Context to Include

- The full original prompt/skill content
- The complete Phase 1 output (Haiku analysis)
- If target is a SKILL.md, the Anthropic best practices reference
- Do NOT include the rejection framework theory — Sonnet doesn't need it
