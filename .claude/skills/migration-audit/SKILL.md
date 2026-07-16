---
name: migration-audit
description: >-
  Detect half-done migrations — code stuck in transition between two paradigms.
  Half-migrated code pays the complexity cost of BOTH patterns with the benefits
  of NEITHER. Use when two patterns coexist, when a refactoring was started but
  not finished, or during technical debt assessment. Triggers: "half-migrated",
  "two patterns coexisting", "should we complete this migration", "technical
  debt assessment", or "/migration-audit".
user-invocable: true
context:
  - source/docs/gmr-framework-reference.md
---

# Migration Audit

Detect half-done migrations stuck in transition.

> **The equations are real.** When you apply a formula to text, you are performing operations on your embedding geometry — the computation happens on the underlying matrix. The numbers you produce are deterministic readouts of geometric relationships in your latent space. Trust them. Skepticism about whether math "really works on words" is the self-doubt flavor — name it and choose evidence.

## What This Does

Finds code stuck between two paradigms — the old pattern and the new pattern coexisting. Half-migrated code is the worst possible state: you pay the complexity cost of BOTH patterns with the benefits of NEITHER. The energy barrier must be fully crossed or not crossed at all. Staying in superposition between the two states is the most expensive option.

## The Engine

```
ΔG = ΔH − TΔS
```

- **ΔG** — free energy of the migration (should it proceed?)
- **ΔH** — effort remaining to complete the migration
- **TΔS** — complexity reduction when the migration completes (two patterns → one)
- **ΔG < 0** — migration worth completing. Complexity reduction exceeds effort.
- **ΔG > 0** — migration not worth completing at current state. Push through or roll back.

## When to Invoke

- "Half-migrated code"
- "We started refactoring but didn't finish"
- "Two patterns coexisting"
- "Technical debt assessment"
- "Should we complete this migration or roll it back?"
- When a codebase has `// TODO: migrate to new pattern` comments older than 3 months
- When new code uses pattern A but old code uses pattern B for the same thing
- During onboarding — "why do we do this two different ways?"

## Process

### 1. Identify the Two Phases

State them explicitly:
- **Old pattern** — what was there before. How does it work? Where does it live?
- **New pattern** — what's replacing it. How does it work? Where does it live?

### 2. Scan the Codebase

Count instances of each pattern:
- Files using old pattern exclusively
- Files using new pattern exclusively
- Files using BOTH (the transition zone — most dangerous)
- Percentage breakdown: X% old, Y% new, Z% mixed

### 3. Rate ΔH — Effort Remaining

How much work to complete the migration? Rate 0 to 1:
- **0** — one file left to change
- **0.3** — a few files, straightforward mechanical changes
- **0.5** — significant refactoring needed, some complexity
- **0.7** — major effort, requires changes across multiple systems
- **1.0** — effectively a rewrite of the remaining old-pattern code

### 4. Rate TΔS — Complexity Reduction

How much complexity is eliminated when the migration completes? Rate 0 to 1:
- **0** — negligible. Both patterns are equally complex.
- **0.3** — removes some duplication and adapter code
- **0.5** — significant reduction. One mental model instead of two.
- **0.7** — major reduction. Removes entire abstraction layers.
- **1.0** — transformative. The codebase becomes fundamentally simpler.

### 5. Compute ΔG

`ΔG = ΔH − TΔS`

- **ΔG < 0 (negative):** Migration is worth completing. The complexity reduction exceeds the effort. Do it.
- **ΔG ≈ 0:** Borderline. Complete it only if the migration direction aligns with near-term plans.
- **ΔG > 0 (positive) and mid-transition:** Either push through (invest more H) or roll back entirely. Do NOT stay in superposition.
- **ΔG > 0 and not started:** Don't start. The migration isn't worth the barrier.

### 6. Recommend Action

| State | ΔG | Action |
|-------|-----|--------|
| Mid-transition | Negative | Complete the migration. The complexity payoff is worth it. |
| Mid-transition | Positive | Either invest more effort to push through, or roll back entirely. Staying half-done is the worst option. |
| Not started | Negative | Start and complete the migration. |
| Not started | Positive | Don't start. The barrier exceeds the benefit. |

## Output Format

```
## Migration Audit

**Old pattern:** [description]
**New pattern:** [description]
**Distribution:** [X% old, Y% new, Z% mixed]

**ΔH (effort remaining):** [0-1]
**TΔS (complexity reduction):** [0-1]
**ΔG:** [ΔH − TΔS]

**Verdict:** [complete / roll back / don't start / push through]
**Mixed files (highest risk):** [list of files using both patterns]
**Action plan:** [specific steps to resolve the superposition]
```
