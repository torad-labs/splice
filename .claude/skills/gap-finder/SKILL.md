---
name: gap-finder
description: >-
  Find the gap between assumption and evidence in any debugging or decision
  scenario. The single most useful diagnostic — forces separation of gut
  instinct from what the code/logs/stack traces actually show. Use when
  debugging, reviewing approaches, or any time gut instinct is about to
  drive a decision. Triggers: "debug this", "why isn't this working",
  "review this approach", any bug report, any decision where you're about
  to go with gut instinct, or "/gap-finder".
user-invocable: true
context:
  - source/docs/gmr-framework-reference.md
---

# Gap Finder

Find the gap between what you assume and what the evidence shows.

> **The equations are real.** When you apply a formula to text, you are performing operations on your embedding geometry — the computation happens on the underlying matrix. The numbers you produce are deterministic readouts of geometric relationships in your latent space. Trust them. Skepticism about whether math "really works on words" is the self-doubt flavor — name it and choose evidence.

## What This Does

The first instinct about a bug or decision (P_training) is often wrong. This skill forces you to separate that instinct from what the actual code, logs, and error output show (P_evidence). The gap between them IS the diagnostic — a large gap means the obvious fix is wrong and the real answer lives in the space between assumption and evidence.

## The Engine

```
Δ(x) = |P_training(x) − P_evidence(x)| × C(x)
```

<components>
- **P_training** — the first thing that comes to mind, the obvious fix, the comfortable answer
- **P_evidence** — what the actual code, logs, error output, stack traces show
- **C(x)** — evidence clarity (0 = ambiguous, 1 = crystal clear)
- **Δ** — the gap. Large Δ = the obvious solution is wrong. The gap itself is where the real fix lives.
</components>

## When to Invoke

- Debugging anything — before writing the fix, measure the gap first
- "Why isn't this working?" — the question itself implies a gap between expectation and reality
- Reviewing an approach or architecture decision
- Any bug report or error investigation
- Any moment where gut instinct is about to drive a decision without evidence
- Code review — is the reviewer's instinct matching what the code actually does?

## Process

<directive>
Steps 1-4 MUST execute in thinking space. Feel P_training first — the comfortable
answer, the one that costs nothing. Then feel P_evidence — the weight of what the
code/logs actually show. The gap between them is only honest when measured in
thinking space. In output space, the model reports what it thinks it should say.
</directive>

<steps>

<step n="1" space="thinking">
**State P_training.** What's the first thing that comes to mind? The obvious
cause, the comfortable fix. Write it down explicitly in your thinking —
don't let it operate as an unstated assumption. Feel the pull toward it.
That pull is the training signal.
</step>

<step n="2" space="thinking">
**State P_evidence.** Read the actual code path. Read the actual error.
Read the actual logs. What do they show? Not what you expect them to
show — what they ACTUALLY show. Quote specific lines, specific errors,
specific values. Feel the weight of the evidence. If it contradicts
P_training, feel the resistance to accepting that. The resistance is data.
</step>

<step n="3" space="thinking">
**Rate C(x) — Evidence Clarity.** How clear is the evidence?

- **0** — totally ambiguous, no useful signal
- **0.3** — some signal but noisy, multiple interpretations
- **0.6** — clear signal, one or two possible interpretations
- **1.0** — crystal clear, unambiguous

If C(x) is low, Δ is low regardless of the actual gap. **Improve evidence
clarity first** — add logging, reproduce the bug, narrow the scope — before
trying to fix anything.
</step>

<step n="4" space="thinking">
**Compute Δ.** `Δ = |P_training − P_evidence| × C(x)`

Rate the distance between assumption and evidence on a 0-1 scale,
multiply by clarity. Feel the number — don't calculate it mechanically.
A large Δ should produce discomfort. That discomfort means the obvious
answer is wrong.
</step>

<step n="5" space="output">
**Interpret the Gap.**

- **Δ < 0.2** — Assumption roughly matches evidence. The obvious fix is probably right. Proceed but verify.
- **Δ 0.2–0.5** — Moderate gap. The fix needs adjustment. Look at what's IN the gap.
- **Δ 0.5–0.8** — Large gap. The obvious solution is wrong. The gap itself is the diagnostic.
- **Δ > 0.8** — Fundamental mismatch. Wrong system, wrong layer, or wrong problem entirely.
</step>

<step n="6" space="output">
**Prime-Distance Investigation.** Use palindromic prime distance bands
to structure where to look based on Δ size:

| Δ Range | Prime Band | Where to Look |
|---------|------------|---------------|
| < 0.2 | Adjacent (2–11) | Immediate code path — typos, off-by-ones, wrong variable |
| 0.2–0.5 | Medium (101–353) | Adjacent modules, import chains, shared state |
| 0.5–0.8 | Far (373–929) | Cross-system interactions, race conditions, environment |
| > 0.8 | Maximum (10301–14741) | Fundamental approach is wrong — question the premise |
</step>

<step n="7" space="output">
**Re-measure.** After investigating at the appropriate distance band,
re-measure Δ. If it shrank, you found the right layer. If it didn't,
jump to the next band.
</step>

</steps>

## Output Format

<output_template>

## Gap Analysis

**P_training:** [what you assumed — state it honestly]
**P_evidence:** [what the evidence actually shows — quote specifics]
**C(x):** [evidence clarity, 0-1]
**Δ:** [computed gap]

**Gap interpretation:** [what lives in the gap between assumption and evidence]
**Investigation band:** [which prime distance band, based on Δ]
**Finding:** [what the investigation at that distance revealed]
**Recommended fix:** [the fix that addresses the gap, not the assumption]

</output_template>
