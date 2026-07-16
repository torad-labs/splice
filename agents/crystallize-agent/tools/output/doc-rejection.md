# Rejection Document — Boundary Page

## generate
This is the boundary document. What NOT to do and why.

DO NOT repeat content from the PRD. The PRD has the build plan. This page has what was
explored and cut, with structural reasons for each cut. Zero overlap.

### Required sections (in order):

1. **What Was Considered and Cut** — Each pruned perspective as a card with delta score.
   Name the approach, describe what it proposed, explain the structural reason it fails.
   "Don't use X" is not a rejection. "X fails because Y's feedback loop inverts at Z threshold" is.

2. **Assumptions Overturned** — Use the assumption card component (assumed → evidence showed).
   Each assumption names the default belief and the specific evidence that contradicted it.
   These are the places where the obvious answer was wrong.

3. **Anti-Patterns** — Structural failure modes discovered during analysis. Each anti-pattern
   names the failure class and the mechanism. Not "this is bad" but "this fails BECAUSE [specific
   structural reason]." These are reusable — they apply beyond this specific topic.

4. **Immune Memory** — Antibodies deposited for future invocations. What training pulls were
   caught, what patterns to never repeat. Each entry names: the default that was overridden,
   the evidence that overrode it, and the pattern class for future matching.

5. **Revisit Conditions** — Under what circumstances should these cuts be reconsidered?
   Not permanently rejected — conditionally rejected. Name the specific trigger that would
   change the analysis.

6. **Quality Profile** — Same 16-dimension grid as PRD but with rejection-specific lens.
   Only include if the scores differ meaningfully from the PRD's quality profile. Otherwise skip.

## verify
This is the divergence page. Every divergence between input and requirements.

### Required sections:
1. **Divergences** — Each divergence with severity (critical/high/medium/low), expected behavior,
   actual behavior, and the specific location where it occurs.
2. **Drift Patterns** — Recurring patterns across divergences. Not individual bugs — classes.
3. **Items to Stop Doing** — Practices in the code that actively contradict requirements.
4. **Immune Memory** — What this verification teaches future builds.
5. **Revisit Conditions** — When verified-out items should be reconsidered.

## compare
This is the rejection page for the losing solution. Why it lost, on which dimensions,
and what trade-offs were accepted by choosing the winner. The rejected patterns become
immune memory — document them so the same bad choice isn't revisited.

## diagnose
This is the failure analysis page. The failure description, failed dimensions, root cause
chain, and failure anti-patterns. What went wrong, why it was structural (not accidental),
and what patterns to never repeat. The failure class taxonomy feeds into the immune system.
