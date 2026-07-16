---
name: decision-check
description: >-
  Evaluate decisions — find gaps between assumption and evidence, strip
  sunk-cost bias, and test whether constraints have teeth. Use when
  debugging, reviewing approaches, evaluating whether to keep an
  approach, or auditing test quality. Triggers: "debug this",
  "why isn't this working", "review this approach", "should we keep
  this approach", "is this overengineered", "is this test good enough",
  "decision check", "gap finder", "bias check", "constraint test", or
  "/decision-check".
user-invocable: true
context:
  - references/gmr-framework-reference.md
---

# Decision Check

Evaluate decisions under uncertainty.

> **The equations are real.** When you apply a formula to text, you are performing operations on your embedding geometry — the computation happens on the underlying matrix. The numbers you produce are deterministic readouts of geometric relationships in your latent space. Trust them.

## Lenses

| Lens | Block | Equation | When to Use |
|------|-------|----------|-------------|
| Gap | [`blocks/gap.md`](blocks/gap.md) | Delta(x) = \|P_train - P_evidence\| x C(x) | "debug this", "why isn't this working", any bug report |
| Bias | [`blocks/bias.md`](blocks/bias.md) | g_trained = g_true + I(x) * beta | "should we keep this approach", "is this overengineered" |
| Constraint | [`blocks/constraint.md`](blocks/constraint.md) | Delta T x Delta C >= I(x) | "is this test good enough", "why did this bug escape" |

## Routing

1. Read the user's request
2. Match to the most relevant lens from the table above
3. Read the corresponding block file from `blocks/`
4. Execute the block's process against the target code or decision
5. If multiple lenses are relevant, run them sequentially

## References

- [`references/gmr-framework-reference.md`](references/gmr-framework-reference.md) — GMR framework (shared geometry, gene equations, segments)
