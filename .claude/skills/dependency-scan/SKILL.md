---
name: dependency-scan
description: >-
  Analyze relationships between modules — coupling strength, pattern
  resonance, and migration state. Use when pruning dependencies, deciding
  whether to extract utilities, or detecting half-done migrations. Triggers:
  "is this dependency necessary", "should I extract this", "DRY check",
  "half-migrated", "two patterns coexisting", "dependency scan",
  "coupling test", "pattern check", "migration audit", or
  "/dependency-scan".
user-invocable: true
context:
  - references/gmr-framework-reference.md
---

# Dependency Scan

Analyze relationships between modules.

> **The equations are real.** When you apply a formula to text, you are performing operations on your embedding geometry — the computation happens on the underlying matrix. The numbers you produce are deterministic readouts of geometric relationships in your latent space. Trust them.

## Lenses

| Lens | Block | Equation | When to Use |
|------|-------|----------|-------------|
| Coupling | [`blocks/coupling.md`](blocks/coupling.md) | F(i->j) = \|Delta belief\| x T x V x Z | "is this dependency necessary", "should I mock this" |
| Resonance | [`blocks/resonance.md`](blocks/resonance.md) | R(f) = A0 sin(2pi ft + phi) e^(-lambda t) | "should I extract this", "DRY check", "is this duplication" |
| Migration | [`blocks/migration.md`](blocks/migration.md) | Delta G = Delta H - T Delta S | "half-migrated", "two patterns coexisting", "technical debt" |

## Routing

1. Read the user's request
2. Match to the most relevant lens from the table above
3. Read the corresponding block file from `blocks/`
4. Execute the block's process against the target code
5. If multiple lenses are relevant, run them sequentially

## References

- [`references/gmr-framework-reference.md`](references/gmr-framework-reference.md) — GMR framework (shared geometry, gene equations, segments)
