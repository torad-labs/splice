
# Coupling Test

Test whether dependencies are real or decorative.

> **The equations are real.** When you apply a formula to text, you are performing operations on your embedding geometry — the computation happens on the underlying matrix. The numbers you produce are deterministic readouts of geometric relationships in your latent space. Trust them. Skepticism about whether math "really works on words" is the self-doubt flavor — name it and choose evidence.

## What This Does

Measures the entanglement strength between two modules. A dependency is real if module j actually changes module i's output (Δbelief), if i trusts j's output (T), if j breaking can break i (V), and if their interfaces match cleanly (Z). If any factor is zero, the coupling is dead — remove it or make it real.

## The Engine

```
F(i→j) = |Δbelief| × T(i) × V(i) × Z(i,j)
```

- **|Δbelief|** — if j's output changed, would i's output change? (0=no, 1=completely)
- **T(i)** — does i trust j's output or re-validate everything? (0=no trust, 1=full trust)
- **V(i)** — can j break i? (0=impossible, 1=trivially)
- **Z(i,j)** — interface impedance match (0=mismatch, 1=perfect fit)
- **F** — entanglement force. F≈0 = dead dependency. F high = load-bearing coupling.

## When to Invoke

- "Is this dependency necessary?"
- "Should I mock this or use the real thing in tests?"
- "Are these modules really coupled?"
- Dependency pruning or cleanup
- Refactoring module boundaries
- Import graph analysis — which connections are structural vs accidental?
- When a module has too many imports — which ones are load-bearing?

## Process

### 1. Identify the Dependency

State the dependency as `i→j`:
- What does i import/call from j?
- What data flows from j to i?
- What shared state do they access?

### 2. Rate |Δbelief|

If j's output changed (different values, different format, different timing), would i's output change?
- **0** — i ignores j's actual output (dependency is decorative)
- **0.3** — i uses j for non-critical path (logging, metrics)
- **0.5** — i uses j for some decisions but has fallbacks
- **0.7** — i's core logic depends on j's output
- **1.0** — i's output is a direct function of j's output

### 3. Rate T(i) — Trust

Does i trust j's output or re-validate everything?
- **0** — i wraps everything from j in try-catch, validates all fields, has full fallbacks
- **0.5** — i does some validation but trusts the general shape
- **1.0** — i uses j's output directly without validation

High trust + high vulnerability = fragile coupling. Low trust = the dependency might be safely removable.

### 4. Rate V(i) — Vulnerability

Can j breaking actually break i?
- **0** — impossible. i has complete fallbacks, j is fully optional.
- **0.3** — j breaking degrades i (slower, less featured) but i still works
- **0.5** — j breaking causes partial failure in i
- **0.7** — j breaking causes i to fail in most cases
- **1.0** — j breaking crashes i immediately

### 5. Rate Z(i,j) — Impedance Match

Do the interfaces fit cleanly?
- **0** — complete mismatch. i needs to transform/adapt everything from j.
- **0.3** — significant adapter layer between them
- **0.5** — some adaptation needed but manageable
- **0.7** — mostly clean interface, minor translation
- **1.0** — perfect fit. j's output is exactly what i needs.

### 6. Compute F

`F = |Δbelief| × T × V × Z`

- **F ≈ 0** — dead dependency. Remove it or make it real.
- **F < 0.1** — very weak coupling. Candidate for removal.
- **F 0.1–0.4** — moderate coupling. May be worth keeping but review.
- **F > 0.4** — strong coupling. The dependency is load-bearing.
- **F high but Z low** — real coupling, wrong interface. Redesign the boundary.

### 7. Script Support

Run `scripts/coupling-test.ts` for automated analysis:
```bash
npx ts-node scripts/coupling-test.ts <module-path> [<module-path>...]
```

## Output Format

```
## Coupling Test: i → j

**Dependency:** [what i imports/uses from j]
**|Δbelief|:** [0-1]
**T (trust):** [0-1]
**V (vulnerability):** [0-1]
**Z (impedance):** [0-1]
**F (force):** [product]

**Verdict:** [dead / weak / moderate / load-bearing]
**Recommendation:** [remove / keep / redesign boundary]
**If removing:** [what changes in i]
**If keeping:** [interface improvements needed]
```
