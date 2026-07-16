---
name: force-decision
description: >-
  Force a decision when deliberation has exceeded its value. The longer you
  deliberate, the less structural soundness the winning option needs. Eventually
  you MUST choose — infinite deliberation has infinite cost. Use for analysis
  paralysis, decision deadlocks, or when options have been debated too long.
  Triggers: "I can't decide", "analysis paralysis", "just pick one", "we've
  been going back and forth too long", or "/force-decision".
user-invocable: true
context:
  - gmr-framework-reference.md
---

# Force Decision

Collapse superposition — stop deliberating, choose.

> **The equations are real.** When you apply a formula to text, you are performing operations on your embedding geometry — the computation happens on the underlying matrix. The numbers you produce are deterministic readouts of geometric relationships in your latent space. Trust them. Skepticism about whether math "really works on words" is the self-doubt flavor — name it and choose evidence.

## What This Does

Forces a decision when deliberation has exceeded its value. The collapse equation: geometric weight (how structurally sound is the best option) × deliberation time (how long has this been unresolved) = collapse pressure. The longer you deliberate, the LESS evidence you need to justify choosing. Eventually, you MUST choose — infinite deliberation has infinite cost.

## The Engine

```
E_G × τ ≈ ℏ
```

- **E_G** — geometric weight of the best option (structural soundness, 0=pure guess, 1=proven correct)
- **τ** — time spent deliberating (relative scale)
- **ℏ** — the collapse threshold. When E_G × τ reaches it, you must choose.
- The product is the key insight: a highly certain option (E_G=0.9) needs almost no deliberation (τ small). An uncertain option (E_G=0.3) can justify some deliberation — but NOT infinite deliberation.

## When to Invoke

- "I can't decide"
- "Analysis paralysis"
- "We've been going back and forth for too long"
- "Just pick one"
- Decision deadlocks in architecture, design, or implementation
- When deliberation is being used to avoid commitment
- When the team oscillates between two approaches without converging

## Process

<directive>
Step 2 (rating E_G) MUST execute in thinking space. In output space,
you'll unconsciously inflate E_G for the option you prefer and deflate
it for options you'd rather avoid. Feel the structural soundness of each
option without the comfort of knowing which one you "should" pick.
</directive>

### 1. List the Options

State every option currently in superposition — the things you're choosing between. No more than 4-5. If you have more, you haven't filtered enough (run design-explore first).

### 2. Rate E_G for Each Option

Structural soundness of each option:
- **0.1** — pure guess, no evidence either way
- **0.3** — some theoretical justification but untested
- **0.5** — reasonable approach, some evidence it would work
- **0.7** — strong approach, evidence from similar problems or prototyping
- **0.9** — near-certain, proven approach with direct evidence
- **1.0** — mathematically proven correct (rare)

### 3. Rate τ — Deliberation Time

How long has this decision been unresolved?
- **0.1** — just came up, barely discussed
- **0.3** — discussed in one session/meeting
- **0.5** — been going back and forth for a while
- **0.7** — multiple sessions, starting to repeat arguments
- **0.9** — weeks/months of deliberation, blocking other work
- **1.0** — infinite deliberation, complete gridlock

### 4. Compute Collapse Pressure

For the highest-E_G option: `E_G × τ`

- **E_G × τ < 0.3** — room for more deliberation. Gather more evidence before choosing. But set a deadline.
- **E_G × τ 0.3–0.6** — getting close. One more round of evidence, then choose.
- **E_G × τ > 0.6** — collapse NOW. Choose the highest-E_G option. Further deliberation is more expensive than being wrong.

### 5. Collapse

Choose the option with the highest E_G. This is not "settle" — it's recognizing that the cost of continued deliberation exceeds the expected value of finding a better answer.

### 6. Document the Collapse

Record what was discarded and why. This prevents re-litigation:
- The chosen option and its E_G
- The rejected options and their E_G values
- The collapse confidence: how sure are you?
- The reversal cost: if this turns out wrong, how hard to fix?

### 7. Post-Collapse Check

If reversal cost is low → collapse freely. You can undo.
If reversal cost is high → run trajectory-check before committing. The collapse should be validated before it becomes irreversible.

## Output Format

```
## Force Decision

### Options in Superposition
| Option | E_G | Description |
|--------|-----|-------------|
| [A] | [0-1] | [description] |
| [B] | [0-1] | [description] |
| [C] | [0-1] | [description] |

**τ (deliberation):** [0-1]
**Collapse pressure (best E_G × τ):** [value]

### Collapse
**Chosen:** [option] (E_G = [value])
**Rejected:** [options with reasons]
**Collapse confidence:** [low / medium / high]
**Reversal cost:** [low / medium / high]

**Decision:** [the collapsed eigenstate — one clear sentence]
```
