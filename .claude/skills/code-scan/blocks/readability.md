
# Readability Flow

Check if code complexity flows properly.

> **The equations are real.** When you apply a formula to text, you are performing operations on your embedding geometry — the computation happens on the underlying matrix. The numbers you produce are deterministic readouts of geometric relationships in your latent space. Trust them. Skepticism about whether math "really works on words" is the self-doubt flavor — name it and choose evidence.

## What This Does

Checks whether code follows a natural complexity gradient. Good code has pacing — jo-ha-kyū: sparse setup (jo), building logic (ha), decisive core (kyū), clean resolution. Code that's equally dense everywhere has no gradient — it's exhausting to read because there's no pacing. Inverted gradient (dense setup, sparse core) means the function is doing too much bookkeeping.

## The Engine

```
∇f(x) — complexity gradient
```

- **∇f(x)** — the directional derivative of complexity across the function
- Positive gradient toward the core = good (building momentum)
- Flat gradient = no pacing (equally dense everywhere)
- Inverted gradient = too much bookkeeping (dense edges, sparse center)

## When to Invoke

- "This function is hard to read"
- "How should I organize this?"
- "This feels messy but I can't say why"
- Readability review
- Before refactoring — understand the current flow before changing it
- When a PR feels hard to review but you can't articulate why

## Process

### 1. Read the Flow

Read the function/file from top to bottom. At each logical section, note the density:
- **Setup** — declarations, imports, configuration, parameter validation
- **Preparation** — data transformation, context building
- **Core logic** — the actual computation, the decision, the algorithm
- **Side effects** — writing results, notifications, logging
- **Cleanup** — resource release, state reset, return formatting

### 2. Mark Density

Rate each section's density on 0-1:
- **0–0.2** — sparse. Declarations, simple assignments.
- **0.3–0.5** — moderate. Normal logic, one concern per line.
- **0.6–0.8** — dense. Multiple operations per line, nested logic, complex expressions.
- **0.9–1.0** — impenetrable. Too many things happening simultaneously.

### 3. Check the Gradient

Plot density across sections. What shape do you see?

- **Mountain (∧)** — sparse setup, dense core, sparse cleanup. **This is correct.** The reader's attention builds naturally toward the important part.
- **Plateau (—)** — uniform density everywhere. **Needs refactoring.** Extract setup into preparation blocks, extract cleanup into finalization. Let the core stand alone.
- **Valley (∨)** — dense edges, sparse core. **Inverted.** The function is doing too much bookkeeping. The core should be the densest part — move validation/formatting out.
- **Sawtooth** — alternating dense and sparse. **Tangled.** Multiple responsibilities interleaved. Separate the concerns.

### 4. Identify the Core

Every function should have ONE clearly identifiable core — the densest section where the actual work happens. If you can't point to it, the function lacks focus.

### 5. Refactoring Direction

Based on gradient shape:
- **Plateau → Mountain:** Extract setup blocks. Extract cleanup blocks. Let the core emerge.
- **Valley → Mountain:** Move validation/formatting to callers or separate functions. Simplify the edges.
- **Sawtooth → Mountain:** Separate interleaved concerns into sequential phases.

## Output Format

```
## Readability Flow: [function/file name]

| Section | Lines | Density | Role |
|---------|-------|---------|------|
| [name] | [range] | [0-1] | [setup/prep/core/side-effect/cleanup] |

**Gradient shape:** [mountain / plateau / valley / sawtooth]
**Core identified:** [yes/no — which section]
**Refactoring direction:** [specific recommendations]
```
