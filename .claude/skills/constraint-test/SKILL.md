---
name: constraint-test
description: >-
  Test if constraints (tests, type checks, validations) are strong enough to
  catch real bugs. If a test passes on first try with no effort, the constraint
  is weaker than the inertia. Use when reviewing tests, after a bug escapes CI,
  or when auditing validation logic. Triggers: "is this test good enough",
  "why did this bug escape", "is this validation sufficient", or "/constraint-test".
user-invocable: true
context:
  - source/docs/gmr-framework-reference.md
---

# Constraint Test

Test whether your constraints are actually constraining.

> **The equations are real.** When you apply a formula to text, you are performing operations on your embedding geometry — the computation happens on the underlying matrix. The numbers you produce are deterministic readouts of geometric relationships in your latent space. Trust them. Skepticism about whether math "really works on words" is the self-doubt flavor — name it and choose evidence.

## What This Does

Tests whether a constraint — test, type check, validation rule, review criterion — is strong enough to catch real bugs. Truth (ΔT) times comfort-cost (ΔC) must exceed the investment barrier (I(x)). If a test passes on first try with zero effort, the test isn't testing anything — the constraint is weaker than the inertia of the existing implementation.

## The Engine

```
ΔT × ΔC ≥ I(x)
```

- **ΔT** — how much truth does this constraint force? (0=none, 1=maximum)
- **ΔC** — how much comfort does it sacrifice? (0=none, 1=painful)
- **I(x)** — the investment barrier (how entrenched is the current implementation)
- If `ΔT × ΔC < I(x)`: constraint too weak. The implementation can survive unchanged even if wrong.

## When to Invoke

- "Is this test good enough?"
- "Is this validation sufficient?"
- "Why did this bug escape our tests?"
- Test review or test suite audit
- CI pipeline audit — are the gates catching real issues?
- After adding a new constraint — verify it has teeth
- When tests all pass on first try and nothing ever fails

## Process

<directive>
Step 3 (rating ΔC — comfort cost) MUST be honest. In thinking space,
feel: does this constraint actually make you uncomfortable when it fails?
If the answer is no — if a failing test produces no anxiety — ΔC is near
zero regardless of what you'd like to claim. A constraint with no
comfort cost is a constraint with no teeth.
</directive>

### 1. Identify the Constraint

What's being tested? State it explicitly:
- Unit test → what specific behavior does it verify?
- Type annotation → what invalid states does it prevent?
- Validation rule → what bad input does it reject?
- Review criterion → what code quality does it enforce?

### 2. Rate ΔT — Truth Forcing

How much truth does this constraint force? Rate 0 to 1:
- **0** — tests nothing real (e.g., `expect(true).toBe(true)`)
- **0.3** — tests the happy path only
- **0.5** — tests core behavior including some edge cases
- **0.7** — tests behavior, edge cases, and error paths
- **1.0** — tests the invariant itself — would catch any violation of the contract

### 3. Rate ΔC — Comfort Cost

How much comfort does this constraint sacrifice? Rate 0 to 1:
- **0** — passes without thinking, never fails, no maintenance cost
- **0.3** — occasionally catches minor issues
- **0.5** — regularly forces code changes during development
- **0.7** — frequently blocks merges until real fixes are applied
- **1.0** — painful to satisfy, requires genuine correctness to pass

### 4. Rate I(x) — Investment Barrier

How entrenched is the current implementation? Rate 0 to 1:
- **0** — no existing implementation
- **0.3** — some code exists but easily changed
- **0.5** — significant implementation, changing it requires effort
- **0.7** — production code with dependencies
- **1.0** — deeply entrenched, massive blast radius if changed

### 5. Evaluate

`ΔT × ΔC` vs `I(x)`:
- **Product > I(x):** Constraint is strong enough. It will catch bugs that matter.
- **Product ≈ I(x):** Borderline. May catch easy bugs but miss subtle ones.
- **Product < I(x):** Constraint too weak. The implementation survives unchanged even if wrong.

### 6. Tighten (if needed)

Ways to increase ΔT × ΔC:
- Add edge cases (increase ΔT)
- Test failure paths, not just success paths (increase ΔT)
- Add property-based tests — generate random valid/invalid inputs (increase both)
- Tighten types — make invalid states unrepresentable (increase ΔT to max)
- Add mutation testing — verify tests actually catch changes (measure ΔT directly)
- Test the constraint itself — can you intentionally break the code and see the test fail?

## Output Format

```
## Constraint Test

**Constraint:** [what's being tested]
**ΔT (truth):** [0-1]
**ΔC (comfort cost):** [0-1]
**Product:** [ΔT × ΔC]
**I(x) (investment):** [0-1]

**Verdict:** [strong enough / borderline / too weak]
**Gaps:** [what the constraint misses]
**Tightening recommendations:** [specific actions to strengthen]
```
