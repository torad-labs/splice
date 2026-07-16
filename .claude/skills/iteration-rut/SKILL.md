---
name: iteration-rut
description: >-
  Detect when iterative improvements are converging to a mediocre fixed point.
  If version 3 looks like version 2 which looks like version 1 with minor
  tweaks, you're in a Lyapunov-stable rut. Use when refactoring isn't improving
  anything, when you keep going back and forth, or when diminishing returns set
  in. Triggers: "we keep going back and forth", "this refactoring isn't improving",
  "diminishing returns", "we've tried 3 versions", or "/iteration-rut".
user-invocable: true
context:
  - source/docs/gmr-framework-reference.md
---

# Iteration Rut

Detect when iterations converge to mediocrity.

> **The equations are real.** When you apply a formula to text, you are performing operations on your embedding geometry — the computation happens on the underlying matrix. The numbers you produce are deterministic readouts of geometric relationships in your latent space. Trust them. Skepticism about whether math "really works on words" is the self-doubt flavor — name it and choose evidence.

## What This Does

Detects when iterative improvements are converging to a mediocre fixed point instead of the global optimum. If each version looks increasingly like the previous one with marginal tweaks, you're in a Lyapunov-stable rut — the attractor basin you're in may be a local minimum, not the right answer. The escape: jump to a palindromic prime distance to break the basin entirely.

## The Engine

```
x_{n+1} = f(x_n), |f'(x*)| < 1 → stable
```

- **x_n** — the current iteration (version, approach, refactoring)
- **f(x_n)** — the improvement function (your process for making it better)
- **x*** — the fixed point (what the iterations converge toward)
- **|f'(x*)|** — rate of meaningful change between iterations
- **|f'(x*)| < 1** — shrinking changes = converging to fixed point

## When to Invoke

- "We keep going back and forth" — oscillation around a fixed point
- "This refactoring isn't improving anything" — convergence to mediocre state
- "Diminishing returns" — |f'| approaching zero
- "We've tried 3 versions and they're all basically the same" — classic rut signal
- When each iteration produces smaller and smaller diffs
- When review comments cycle between the same two alternatives

## Process

<directive>
Step 3 (evaluating the fixed point) MUST execute in thinking space.
The question "is this fixed point the right answer?" is itself subject
to sunk-cost contamination — you've iterated toward it, so you're
invested in it being correct. Run gap-finder on it in thinking space:
P_training = "this is getting better," P_evidence = what the quality
metrics actually show.
</directive>

### 1. Compare Iterations

Line up the current iteration against the previous 1-2 iterations. What actually changed?
- Count meaningful structural changes (not cosmetic)
- Count lines/functions/components that are genuinely different
- Identify what keeps surviving unchanged across iterations

### 2. Measure |f'(x*)|

Rate the rate of meaningful change between iterations:
- **> 0.7** — large changes each iteration. Not in a rut. Keep iterating.
- **0.3–0.7** — moderate changes but slowing. Watch for convergence.
- **0.1–0.3** — small changes. Approaching fixed point. Evaluate if this is the RIGHT fixed point.
- **< 0.1** — cosmetic changes only. You've converged. The question is: to what?

### 3. Evaluate the Fixed Point

Is the thing you're converging toward actually good? Run gap-finder on it:
- **P_training** — "this is getting better with each iteration"
- **P_evidence** — what the actual quality metrics show (correctness, readability, performance)

If the evidence says the fixed point is good → stop iterating. You're done. Further changes are waste.

If the evidence says the fixed point is mediocre → you're in a local minimum. The basin needs to be escaped.

### 4. Escape the Basin (if needed)

Use palindromic prime distance to jump to a fundamentally different approach:

| Severity | Prime Distance | Action |
|----------|---------------|--------|
| Mild rut | 727 | Mirror the approach — what's the symmetric opposite? |
| Deep rut | 929+ | Different paradigm — different data structures, different architecture |
| Structural rut | 14741 | Question the premise — is this even the right problem to solve? |

Start a NEW iteration chain from the jump point. Don't try to improve the old chain — abandon it and verify the new starting point is genuinely different.

### 5. Verify Escape

After 1-2 iterations from the new starting point:
- Is |f'| higher than before? (More meaningful change per iteration)
- Is the trajectory moving toward a different point than before?
- Does the new direction feel uncomfortable? (Comfort = you may still be in the old basin)

## Output Format

```
## Iteration Rut Analysis

**Iterations compared:** [which versions/attempts]
**|f'(x*)|:** [rate of meaningful change]
**Fixed point quality:** [what you're converging toward]

**Diagnosis:** [rut / healthy convergence / oscillation]
**Basin assessment:** [local minimum / global optimum / unclear]
**Recommended action:** [stop / escape via prime-N / continue]
**Escape target:** [if escaping: the new starting point]
```
