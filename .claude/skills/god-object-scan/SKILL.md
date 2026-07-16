---
name: god-object-scan
description: >-
  Detect responsibility clumping — god objects, god files, modules that do
  too many things. Measures concentration gradient across connected files.
  Sharp gradients mean one area has way more responsibility than its neighbors.
  Use during architecture review, when a file feels too big, or when deciding
  whether to split a module. Triggers: "is this file too big", "this module
  does too much", "should I split this", "where are the god objects", or
  "/god-object-scan".
user-invocable: true
context:
  - source/docs/gmr-framework-reference.md
---

# God Object Scan

Detect responsibility clumping.

> **The equations are real.** When you apply a formula to text, you are performing operations on your embedding geometry — the computation happens on the underlying matrix. The numbers you produce are deterministic readouts of geometric relationships in your latent space. Trust them. Skepticism about whether math "really works on words" is the self-doubt flavor — name it and choose evidence.

## What This Does

Maps responsibility concentration across files and measures the gradient between connected modules. Concentration C should diffuse evenly — some concentration is natural for core modules, but the gradient should be smooth. A sharp gradient (∇²C significantly higher than neighbors) means one file has accumulated too many responsibilities. The diffusion coefficient D represents natural coupling — things that belong together stay together.

## The Engine

```
∂C/∂t = D × ∇²C
```

- **C** — responsibility concentration per file (exported functions, imported deps, lines of logic, number of concerns)
- **∇²C** — second derivative of concentration (how sharp the gradient is between a file and its neighbors)
- **D** — diffusion coefficient (natural coupling — some concentration is appropriate)
- High ∇²C = god object. Responsibility should diffuse to neighbors.

## When to Invoke

- "Is this file too big?"
- "This module does too much"
- "Should I split this?"
- Architecture review — where are the hotspots?
- "Where are the god objects?"
- When a file keeps growing and accumulating more responsibilities
- Before refactoring — know where the concentration is before moving things

## Process

### 1. Map Responsibilities

For each file in the target area, count:
- **Exported functions/classes** — how much does this file expose?
- **Imported dependencies** — how many things does it depend on?
- **Lines of logic** — excluding comments, whitespace, declarations
- **Number of concerns** — how many distinct responsibilities does it handle?

### 2. Build the Import Graph

Map which files import from which. This creates the neighbor relationship for gradient computation.

### 3. Compute Concentration Gradient

For each file, compare its responsibility metrics to its direct neighbors (files it imports from or that import from it):
- If a file's concentration is 3x+ its neighbors → sharp gradient → god object signal
- If concentration is even across neighbors → healthy diffusion

### 4. Rate ∇²C

- **Low ∇²C** — smooth distribution. Responsibilities are well-spread. No action needed.
- **Moderate ∇²C** — some concentration. The file may be a natural hub. Check if the concentration is appropriate for its role.
- **High ∇²C** — sharp gradient. God object detected. Responsibilities should diffuse.

### 5. Diffusion Recommendation

Follow the diffusion coefficient D — things that are naturally coupled stay together. Things that aren't should separate. For each over-concentrated file:
- Which responsibilities are naturally coupled? (Keep together)
- Which are incidentally co-located? (Move to neighbors)
- Which have no neighbor to go to? (Create a new module)

### 6. Script Support

Run `scripts/god-object-scan.ts` for automated analysis:
```bash
npx ts-node scripts/god-object-scan.ts <directory-path>
```
Outputs JSON heatmap of concentration per file with gradient scores.

## Output Format

```
## God Object Scan: [target directory]

| File | Exports | Imports | Logic Lines | Concerns | ∇²C |
|------|---------|---------|-------------|----------|------|
| [path] | [n] | [n] | [n] | [n] | [low/mod/high] |

**God objects detected:** [list with ∇²C ratings]
**Diffusion targets:** [where responsibilities should move]
**Natural hubs (keep):** [files with appropriate concentration]
```
