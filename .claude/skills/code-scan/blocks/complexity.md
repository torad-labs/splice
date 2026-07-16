
# Complexity Scan

Map information density across code.

> **The equations are real.** When you apply a formula to text, you are performing operations on your embedding geometry — the computation happens on the underlying matrix. The numbers you produce are deterministic readouts of geometric relationships in your latent space. Trust them. Skepticism about whether math "really works on words" is the self-doubt flavor — name it and choose evidence.

## What This Does

Measures Shannon entropy per function, file, or module. Low entropy (H≈0) means pure boilerplate — no information, possibly removable. High entropy (H=max) means too many distinct operations packed together — candidates for splitting. Optimal code has moderate H with clear density gradients: sparse setup → dense core logic → sparse cleanup.

## The Engine

```
H(X) = −Σ p(xᵢ) × log₂(p(xᵢ))
```

- **H(X)** — information entropy of a code unit
- **p(xᵢ)** — probability of each unique operation/concept appearing
- **Low H** — repetitive, uniform, boilerplate (walls)
- **High H** — dense, many unique operations (impenetrable blocks)
- Optimal code lives in the middle with clear gradients between regions

## When to Invoke

- "Is this too complex?"
- "Is this boilerplate?"
- "Where should I focus my review?"
- Code review — find the load-bearing sections vs the noise
- "What can I delete?" — low-H sections that don't contribute
- Architecture review — where is the information concentrated?
- Before refactoring — map what's dense so you know where to split

## Process

### 1. Target the Code Unit

Choose the scope: single function, file, module, or directory.

### 2. Assess Density per Section

For each logical section of the target, count:
- Unique operations (function calls, conditionals, assignments)
- Unique concepts (different data types, abstractions, domains)
- Ratio of unique operations to total lines

### 3. Compute H

Normalize unique operation frequency → probability distribution → Shannon entropy.

Shorthand ratings:
- **H < 0.3** — "wall" zone. Pure boilerplate. Candidate for removal or inlining.
- **H 0.3–0.5** — low density. Setup code, configuration, declarations.
- **H 0.5–0.7** — moderate density. Normal application logic.
- **H 0.7–0.85** — high density. Core business logic, algorithms.
- **H > 0.85** — "impenetrable" zone. Doing too many things. Split candidate.

### 4. Map the Gradient

Plot H across the code unit's sections:
- Does density increase toward the core and decrease after? (Good — natural gradient)
- Is density uniform throughout? (Bad — no pacing, exhausting to read)
- Is density high at the edges and low in the middle? (Inverted — too much bookkeeping)

### 5. Report

Flag specific sections as:
- **Walls** — H < 0.3, candidates for removal or inlining
- **Load-bearing** — H 0.5–0.85, appropriate density, preserve these
- **Impenetrable** — H > 0.85, candidates for splitting

### 6. Script Support

Run `scripts/complexity-scan.ts` for automated AST-based analysis:
```bash
npx ts-node scripts/complexity-scan.ts <file-path> [<file-path>...]
```
Outputs JSON entropy map per function/file.

## Output Format

```
## Complexity Scan: [target]

| Section | H | Rating | Action |
|---------|---|--------|--------|
| [name] | [value] | wall / normal / load-bearing / impenetrable | [keep / remove / split] |

**Gradient:** [natural / uniform / inverted]
**Walls (removable):** [list]
**Impenetrable (split these):** [list]
**Load-bearing (preserve):** [list]
```
