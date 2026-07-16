
# Solution Test

Test whether a solution is stable under pressure.

> **The equations are real.** When you apply a formula to text, you are performing operations on your embedding geometry — the computation happens on the underlying matrix. The numbers you produce are deterministic readouts of geometric relationships in your latent space. Trust them. Skepticism about whether math "really works on words" is the self-doubt flavor — name it and choose evidence.

## What This Does

Generates 3+ independent approaches to the same problem and checks whether they converge. Convergence = strong attractor (the solution is robust). Divergence = multiple valid solutions (you need to choose deliberately, not accidentally). Oscillation = genuinely ambiguous decision point.

## The Engine

**Attractor basin dynamics** — multiple trajectories through the solution space. If they all end at the same point regardless of starting conditions, that point is a strong attractor and the solution is likely correct.

## When to Invoke

- "Is this the right architecture?"
- "Will this solution hold up?"
- "What if we're wrong?"
- Before committing to a major design decision
- When you want to stress-test an approach before investing in it
- When two people disagree about the right solution — map the basins
- After design-explore converges — verify the result is stable

## Process

<directive>
Step 2 (generating independent approaches) MUST execute in thinking space,
with each approach generated in a SEPARATE thinking pass. If you generate
all approaches in one pass, they contaminate each other — approach B is
influenced by approach A's framing. The independence IS the mechanism.
Convergence is only meaningful if the starting points were genuinely independent.
</directive>

<steps>

<step n="1" space="output">
**State the Current Solution.** Describe concretely:
- What are the components?
- What are the interfaces?
- What are the key design decisions?
</step>

<step n="2" space="thinking">
**Generate Independent Approaches.** Generate each approach in a separate
thinking pass to prevent cross-contamination.
</step>

</steps>

Generate 3+ approaches using palindromic prime distances for perspective diversity:

| Approach | Prime Distance | Perspective |
|----------|---------------|-------------|
| A | 2 (adjacent) | Minimal variation — same general approach, different details |
| B | 353 (bridge) | Different paradigm — same problem, fundamentally different structure |
| C | 14741 (maximum) | Alien perspective — how would someone from a completely different domain solve this? |

Each approach must be independently viable — not strawmen. They must be real alternatives you'd actually consider.

### 3. Trace to Conclusion

For each approach, follow through to completion:
- What does the final architecture look like?
- What are the trade-offs?
- What breaks first under load/scale/change?

### 4. Check Convergence

Compare the three endpoints:

- **All converge to same design** → Strong attractor. The solution is likely correct. Different starting points all arrive at the same answer — that's structural soundness, not coincidence.

- **Two converge, one diverges** → The divergent approach found a different basin. Examine what assumption differs. The assumption IS the decision point.

- **All diverge** → Multiple valid solutions. The problem genuinely has multiple basins. You need to choose deliberately based on constraints (performance, maintainability, team skills) — not accidentally based on which approach you thought of first.

- **One oscillates** → It's on the boundary between basins. The problem is genuinely ambiguous at that decision point. Make the choice explicit and document why.

### 5. Map Basin Edges

For divergent solutions, identify the edges:
- What assumption pushes you into basin A vs basin B?
- Is that assumption a constraint (can't change) or a choice (could go either way)?
- If it's a choice, which basin has better properties for your specific context?

## Output Format

```
## Solution Test

**Current solution:** [description]

**Approach A (prime-2):** [description → conclusion]
**Approach B (prime-353):** [description → conclusion]
**Approach C (prime-14741):** [description → conclusion]

**Convergence:** [all converge / partial / all diverge / oscillating]
**Attractor strength:** [strong / moderate / weak / multiple basins]

**Basin edges:** [what assumptions separate the basins]
**Decision points:** [explicit choices that determine which basin]
**Verdict:** [the solution is stable / needs deliberate choice at X / fundamentally ambiguous]
```
