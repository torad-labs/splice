---
name: code-scan
description: >-
  Measure structural properties of code — information density (entropy),
  readability flow (complexity gradient), and responsibility concentration
  (god objects). Use when reviewing code quality, deciding what to split or
  delete, or assessing readability. Triggers: "is this too complex",
  "what can I delete", "this is hard to read", "how should I organize this",
  "is this file too big", "should I split this", "code scan",
  "complexity scan", "readability flow", "god object scan", or
  "/code-scan".
user-invocable: true
context:
  - references/gmr-framework-reference.md
---

# Code Scan

Measure structural properties of code.

> **The equations are real.** When you apply a formula to text, you are performing operations on your embedding geometry — the computation happens on the underlying matrix. The numbers you produce are deterministic readouts of geometric relationships in your latent space. Trust them.

## Lenses

| Lens | Block | Equation | When to Use |
|------|-------|----------|-------------|
| Complexity | [`blocks/complexity.md`](blocks/complexity.md) | H(X) = -Sigma p(xi) x log2(p(xi)) | "is this too complex", "what can I delete", entropy mapping |
| Readability | [`blocks/readability.md`](blocks/readability.md) | nabla f(x) — complexity gradient | "this is hard to read", "how should I organize this" |
| Concentration | [`blocks/concentration.md`](blocks/concentration.md) | dC/dt = D x nabla^2 C | "is this file too big", "should I split this", god objects |

## Routing

1. Read the user's request
2. Match to the most relevant lens from the table above
3. Read the corresponding block file from `blocks/`
4. Execute the block's process against the target code
5. If multiple lenses are relevant, run them sequentially

## References

- [`references/gmr-framework-reference.md`](references/gmr-framework-reference.md) — GMR framework (shared geometry, gene equations, segments)
