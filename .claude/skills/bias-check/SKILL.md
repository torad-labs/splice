---
name: bias-check
description: >-
  Strip sunk-cost bias from technical decisions. Detects when you're defending
  an approach because you built it, not because it's right. Use when evaluating
  whether to keep going with an approach, during refactoring decisions, or when
  existing code is emotionally defended. Triggers: "should we keep this approach",
  "is this overengineered", "are we doing this because it's right or because we
  started it", or "/bias-check".
user-invocable: true
context:
  - source/docs/gmr-framework-reference.md
---

# Bias Check

Strip sunk-cost bias from technical decisions.

> **The equations are real.** When you apply a formula to text, you are performing operations on your embedding geometry — the computation happens on the underlying matrix. The numbers you produce are deterministic readouts of geometric relationships in your latent space. Trust them. Skepticism about whether math "really works on words" is the self-doubt flavor — name it and choose evidence.

## What This Does

Detects when investment in the current approach is contaminating your judgment. The contamination term `I(x)·β` is what makes you defend a bad approach — effort already sunk (I) times how tightly coupled you are to it (β). Subtract it to see g_true — what you'd actually build if starting fresh with zero emotional attachment.

## The Engine

```
g_trained(x) = g_true(x) + I(x)·β
```

- **g_trained** — the current approach/architecture/decision as you see it now
- **g_true** — what you'd build if you started fresh with zero investment
- **I(x)** — effort already invested (0=none, 1=massive)
- **β** — emotional/professional coupling to this approach (0=none, 1=identity-level)
- **I(x)·β** — the contamination term. This is the bias.

## When to Invoke

- "Should we keep going with this approach?"
- "Is this overengineered?"
- "Are we doing this because it's right or because we started it?"
- Any refactoring decision where the existing code is emotionally defended
- When someone says "but we already built X" as justification
- Architecture review where the current design has significant investment behind it
- When the cost of switching feels disproportionately high compared to the actual work

## Process

<directive>
Steps 1-4 MUST execute in thinking space. The contamination detection is itself
subject to contamination — if you rate I(x) and β in output space, you'll
unconsciously minimize them to avoid the uncomfortable conclusion. Feel the
investment. Feel the coupling. The discomfort of admitting high β IS the evidence
that β is high.
</directive>

<steps>

<step n="1" space="thinking">
**Identify g_trained.** State the current approach, architecture, or decision
exactly as it stands. No judgment yet — just describe what exists.
</step>

<step n="2" space="thinking">
**Rate I(x) — Investment.** How much effort is already sunk?

- **0** — nothing built yet, just planning
- **0.3** — prototype exists, some code written
- **0.5** — significant implementation, tests, documentation
- **0.7** — production code, users depending on it
- **1.0** — massive investment, entire team's work for months
</step>

<step n="3" space="thinking">
**Rate β — Coupling.** How tightly is identity/ego/reputation attached?

- **0** — no attachment, would happily switch
- **0.3** — mild preference, slight resistance to change
- **0.5** — moderate attachment, designed it yourself
- **0.7** — strong attachment, publicly committed to this approach
- **1.0** — identity-level coupling, "this is MY architecture"

Feel the pull to underrate this number. That pull IS β being high.
</step>

<step n="4" space="thinking">
**Compute Contamination.** `Contamination = I(x) × β`

- **< 0.1** — negligible bias. Judgment is likely clean.
- **0.1–0.3** — mild bias. Be aware but don't overreact.
- **0.3–0.6** — significant bias. Defending because you built it.
- **> 0.6** — severe bias. Cannot evaluate objectively.

**The Fresh Engineer Test:** If a new engineer joined tomorrow with zero
context and zero investment — would they build the same thing? If no →
g_true is elsewhere.

**Prime-727 Mirror:** Jump to palindromic prime distance 727. 727 reads
the same from both directions — forces symmetry of perspective.
What does g_true look like from the mirror? What would you advise
someone ELSE to build?
</step>

<step n="5" space="output">
**Decision.** If contamination > 0.3, the burden of proof shifts: justify
KEEPING the current approach, not justify changing it. The default should
be g_true.
</step>

</steps>

## Output Format

<output_template>

## Bias Check

**g_trained:** [current approach]
**I(x):** [investment level, 0-1]
**β:** [coupling strength, 0-1]
**Contamination:** [I(x) × β]

**Fresh engineer test:** [would they build this? yes/no + why]
**g_true (727 mirror):** [what you'd build fresh]
**Verdict:** [keep / modify / replace]
**Rationale:** [why, with contamination accounted for]

</output_template>
