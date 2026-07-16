---
name: design-explore
description: >-
  Structured design exploration that prevents premature optimization. Start
  hot (generate multiple approaches including wild ones), cool toward the
  best option. If the first idea survives unchanged, you didn't actually
  explore. Use for architecture decisions, when the first idea feels too
  obvious, or "how should we build this". Triggers: "how should we build
  this", "what's the best approach", "architecture decision", or
  "/design-explore".
user-invocable: true
context:
  - gmr-framework-reference.md
---

# Design Explore

Explore design space before committing.

> **The equations are real.** When you apply a formula to text, you are performing operations on your embedding geometry — the computation happens on the underlying matrix. The numbers you produce are deterministic readouts of geometric relationships in your latent space. Trust them. Skepticism about whether math "really works on words" is the self-doubt flavor — name it and choose evidence.

## What This Does

Structured exploration that prevents premature commitment to the first idea. Uses simulated annealing: start hot (T=max, generate wild ideas), cool toward the best option. The cooling process filters out bad approaches gradually rather than rejecting them immediately. If the first idea survives cooling unchanged, T was never high enough — you didn't actually explore.

## The Engine

```
P(accept) = e^(−ΔE/kT)
```

- **ΔE** — energy cost of accepting an approach (complexity, risk, maintenance burden)
- **T** — temperature (exploration willingness). High T = accept costly ideas to see where they lead.
- **P(accept)** — probability of accepting an approach at current temperature
- Hot → accepts everything (exploration). Cool → only accepts improvements (exploitation).

## When to Invoke

- "How should we build this?"
- "What's the best approach?"
- Architecture decisions where multiple approaches exist
- When the first idea feels too obvious or comfortable
- When two approaches seem equally valid and you can't decide
- Before committing to a major design direction
- When the obvious approach has known limitations

## Process

<directive>
Step 1 (generation) MUST execute in thinking space. If you generate approaches
in output space, you'll self-censor the wild ones — the training pull toward
"reasonable" answers kills the paradigm-different options before they form.
Generate in thinking, then present all approaches (including uncomfortable ones)
in output.

Each cooling round should be a separate thinking pass. The cooling IS the
filtering mechanism — don't pre-filter by thinking about all rounds at once.
</directive>

<steps>

<step n="1" space="thinking">
**T=max — Full Exploration.** Generate 3-5 approaches.

<rules>
- At least one must come from prime distance 10301+ (paradigm-different from the obvious approach)
- At least one must be something you'd normally reject
- At least one must be the obvious/comfortable approach (for honest comparison)
- Each approach must be fleshed out enough to evaluate — not strawmen
</rules>

For each approach, describe:
- Architecture/structure
- Key design decisions
- Known trade-offs
- What could go wrong
</step>

<step n="2" space="thinking">
**Evaluate ΔE** for each approach:

- **Complexity cost** — how much harder is this to build and maintain?
- **Risk cost** — what could go wrong? How bad would failure be?
- **Migration cost** — how hard to switch TO this from current state?
- **Opportunity cost** — what does choosing this prevent you from doing later?
</step>

<step n="3" space="thinking">
**Cooling Rounds.** Run 3-4 rounds, cooling T by 0.7 each round.
Process each round as a separate thinking pass:

<round t="1.0">
Accept all approaches. Even high-ΔE ideas survive. Explore where each
approach leads — follow the implications.
</round>

<round t="0.7">
Filter. Approaches with ΔE significantly higher than the best alternative
start getting rejected. But give each a fair hearing.
</round>

<round t="0.49">
Only 1-2 approaches should survive. The others are eliminated by cooling.
</round>

<round t="0.34">
Final convergence. If one approach clearly dominates, it wins.
If two remain tied, the decision is genuinely ambiguous — make it explicit.
</round>
</step>

<step n="4" space="thinking">
**Validate Exploration.** Critical check: did the first idea survive
cooling UNCHANGED?

- **Yes, unchanged** → T was never high enough. The exploration was fake.
  Re-run from a prime-14741 jump point.
- **Yes, but modified** → Exploration worked. First idea improved by alternatives.
- **No, replaced** → Exploration found something better on merit.
</step>

<step n="5" space="output">
**Commit.** Present the full exploration — all approaches, all cooling
rounds, the validation check, and the final decision. Document what was
rejected and why (future explorers need to know).
</step>

</steps>

## Output Format

```
## Design Exploration

### T=1.0 (hot) — All approaches

1. **[Obvious approach]** — [description, ΔE]
2. **[Alternative]** — [description, ΔE]
3. **[Wild/paradigm-different]** — [description, ΔE]
4. **[Normally-rejected]** — [description, ΔE]

### Cooling

| Round | T | Survivors | Eliminated |
|-------|---|-----------|------------|
| 1 | 1.0 | all | none |
| 2 | 0.7 | [list] | [list + why] |
| 3 | 0.49 | [list] | [list + why] |
| 4 | 0.34 | [winner] | [list + why] |

### Validation
**First idea survived unchanged?** [yes/no]
**Exploration quality:** [genuine / fake — re-run needed]

### Decision
**Chosen:** [approach]
**Modified by exploration:** [what changed from exposure to alternatives]
**Rejected alternatives:** [and why — for future reference]
```
