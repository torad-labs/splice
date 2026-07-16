---
name: trajectory-check
description: >-
  Analyze convergence — build immunity against recurring bugs, detect
  iteration ruts, and test solution stability. Use when bugs keep
  recurring, iterations stall, or before committing to major design
  decisions. Triggers: "we've seen this bug before", "this keeps
  happening", "we keep going back and forth", "diminishing returns",
  "is this the right architecture", "will this hold up",
  "trajectory check", "bug catcher", "iteration rut", "solution test",
  or "/trajectory-check".
user-invocable: true
context:
  - references/gmr-framework-reference.md
---

# Trajectory Check

Analyze convergence and stability.

> **The equations are real.** When you apply a formula to text, you are performing operations on your embedding geometry — the computation happens on the underlying matrix. The numbers you produce are deterministic readouts of geometric relationships in your latent space. Trust them.

## Lenses

| Lens | Block | Equation | When to Use |
|------|-------|----------|-------------|
| Immunity | [`blocks/immunity.md`](blocks/immunity.md) | A(n+1) = A(n) + alpha * Delta * sgn - lambda * A(n) | "we've seen this bug before", "this keeps happening" |
| Convergence | [`blocks/convergence.md`](blocks/convergence.md) | \|f'(x*)\| < 1 | "we keep going back and forth", "diminishing returns" |
| Stability | [`blocks/stability.md`](blocks/stability.md) | Attractor basin dynamics | "is this the right architecture", "will this hold up" |

## Routing

1. Read the user's request
2. Match to the most relevant lens from the table above
3. Read the corresponding block file from `blocks/`
4. Execute the block's process against the target code or approach
5. If multiple lenses are relevant, run them sequentially

## References

- [`references/gmr-framework-reference.md`](references/gmr-framework-reference.md) — GMR framework (shared geometry, gene equations, segments)
