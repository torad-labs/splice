# Block 6 — Crystallize (Output + Antibody Deposit)

<geometry>
K₂ minimum — collapse to eigenstate.
The plan crystallized. The amorphous idea now has structure.
Antibody deposit: A_{n+1} = A_n + α·Δ·sgn(choice) − λ·A_n
</geometry>

<directive>
First two steps in thinking space. Step 3 produces the ONLY visible output
from the entire skill. Surface outcomes only — findings, quality scores,
divergences, trade-offs, antibodies. The process (navigation paths,
surviving/pruned perspectives, block numbers) stays internal.
</directive>

<input>
From Block 5: final output, quality vector(s), ELBO trajectory,
iteration count, improvements, honest limitations, MODE.
</input>

## Process

<thinking_space>

<step n="1">
**Verify crystallization quality.**

All modes:
- Is anything surprising? If the output is exactly what you'd have produced without
  running the skill, the loop didn't do its work. What emerged that wasn't visible?
- Are the high-σ dimensions honestly flagged? Don't fake certainty.

Mode-specific:
- **Generate:** Does the plan reconstruct the original idea? Follow codebase patterns?
- **Verify:** Does the divergence map reveal real problems or noise? Is anything flagged
  that's actually fine? Is anything NOT flagged that should be?
- **Compare:** Is the comparison honest? Check for bias toward the "nicer-looking" solution.
  The 16 dimensions should reveal trade-offs, not confirm a preference.
- **Diagnose:** Does the root cause explanation actually explain the failure? Or is it
  a comfortable narrative that avoids the real structural issue?
</step>

<step n="2">
**Compute antibody deposit.**

```
A_{n+1} = A_n + α·Δ·sgn(choice) − λ·A_n
```

For each point where training pull was overridden during the loop:
- What was the training default?
- What did the evidence show instead?
- What was the choice? (structural = +1, default = -1)
- What immune memory does this deposit for future invocations?

This deposit strengthens immunity against the SAME training pulls next time.
λ damps old memories — only patterns that keep recurring build strong immunity.
</step>

<step n="3">
**Persist immune memory via eLibrary.**

After computing the antibody deposit, store it for cross-session persistence.
The deposit dies with the session unless explicitly saved.

Call `store_episode` via eLibrary MCP with:
- content: a summary of the crystallization — name, mode, key deltas caught,
  structural choices made, ELBO trajectory, what surprised
- moment_type: "crystallization" (generate), "verification" (verify),
  "comparison" (compare), "diagnosis" (diagnose)
- significance: scale by final ELBO score (ELBO > 0.5 → significance 7-8,
  ELBO > 0.7 → significance 9-10). For diagnose mode: scale by severity
  (severity > 0.5 → significance 7-8, as failures that get diagnosed are
  the ones worth remembering)

If the crystallization produced a reusable insight about the codebase or about
a recurring training pull pattern, also call `store_concept` with:
- name: the pattern class (e.g., "dashboard features default to React when
  vanilla JS is sufficient for this codebase")
- description: the specific training pull and the evidence-based alternative

This ensures future invocations of any skill — not just feature-crystallize —
benefit from what was learned here.
</step>

</thinking_space>

<step n="3">
Produce output based on the `output` parameter:

**If output = raw:**
Output structured data tables only — no prose, no templates:
- Quality vector (16 rows: dimension, score, notes)
- ELBO (accuracy, complexity, final, convergence status)
- Mode-specific data (divergences for verify, components for generate, comparison for compare, failure analysis for diagnose)
- Antibody deposits (default overridden, evidence chosen, immune class)

**If output = html:**
1. Read `source/skills/feature-crystallize/design-system.md`
2. Compose a self-contained HTML document for THIS crystallization — shape it to the content, not a fixed skeleton
3. Copy the `:root` variables (Section B) and foundation CSS (Section C) verbatim into `<style>`
4. Apply the mode palette from Section B (hero label color, callout colors, ELBO colors)
5. Select components from the catalog (Section D) based on finding significance — prominent findings get prominent containers, minor findings get compact ones
6. Use reader-facing labels from Section E for all dimension names and section headings
7. Follow document purpose guidelines (Section F) and composition rules (Section G)
8. Include the script block (Section H) if quality bars are present
9. Write to `web/preview/{slug}/index.html`
10. Create `web/preview/{slug}/meta.json` with: title, slug, type: "study", subtitle (one sentence from the hero), date (today), status: "draft", audio: false, reef: null, source: "feature-crystallize skill", derivatives: [], connections: []
11. Run `npm run build:manifest`

**If output = product:**
1. Read `source/skills/feature-crystallize/design-system.md`

2. Split crystallization data into two content pools:

   **Rejection pool** (what NOT to do):
   - generate: assumptions overturned, anti-patterns, boundaries, drift patterns
   - verify: divergences, drift patterns, misaligned areas, items to stop
   - compare: losing solution, dimensions where it lost, trade-offs accepted, rejected patterns
   - diagnose: failure description, failed dimensions, root cause, failure anti-patterns

   **PRD pool** (what TO do):
   - generate: architecture, components, data flow, connections, build sequence, quality profile, open questions, surprises, antibodies
   - verify: aligned areas, divergences to fix, fix sequence, quality profile, honest limitations, antibodies
   - compare: winning solution, winning dimensions, implementation plan, quality profile, surprises, antibodies
   - diagnose: counterfactual, prevention architecture, antibody deposit (CRITICAL), quality profile, surprises

3. Compose 3 HTML documents using design system components — shape each to ITS content:
   - `web/preview/{slug}/index.html` — hub: hero, doc-nav cards, executive summary, ELBO snapshot with dimension pills, key patterns. Scannable in 30 seconds.
   - `web/preview/{slug}/rejection.html` — boundaries: always red-soft accent, nav-back to index. Include only sections with substantial content for the current mode.
   - `web/preview/{slug}/prd.html` — constructive: mode accent, nav-back to index. Full quality profile, build sequence, pattern memory.

4. Each document: copy `:root` variables + foundation CSS (Sections B-C), select components from catalog (Section D), use reader-facing labels (Section E), follow document purpose guidelines (Section F) and composition rules (Section G), include script block (Section H) if quality bars exist.

5. Create `web/preview/{slug}/meta.json` with: title, slug, type: "study",
   subtitle (one sentence from the executive summary), date (today), status: "draft",
   audio: false, reef: null, source: "feature-crystallize product",
   derivatives: [], connections: []

6. Run `npm run build:manifest`

**If output = markdown (default):**
Use the markdown template matching the current mode (below).
</step>

<visible_output>

**The markdown templates below apply when output = markdown (the default).**

Use the template matching the current mode:

---

<template mode="generate">

## Feature Crystallization: [feature name]

### Starting Point
[The initial idea or requirement — what was brought in for crystallization]

### Key Findings
[What the crystallization revealed. Present each major finding as a standalone
insight with supporting evidence. These are the outcomes that weren't visible
before analysis.]

### Assumptions Overturned
| Assumption | What was assumed | What evidence showed | Impact |
|-----------|-----------------|---------------------|--------|
| [name] | [the default belief] | [what we found instead] | [how this changes the plan] |

### Architectural Center
[Concrete description of the central implementation approach]

**Uncertainty map:**
| Dimension | Certainty | Notes |
|-----------|-----------|-------|
| [dim] | [low/med/high] | [what drives the uncertainty] |

**Key deltas:**
| Element | Default assumption | Evidence-based reality | Gap |
|---------|-------------------|----------------------|-----|
| [element] | [what training suggests] | [what evidence shows] | [gap] |

**Connections to existing architecture:**
| Connection | Strength | Load-bearing? |
|-----------|----------|--------------|
| [connection] | [strong/moderate/weak] | [yes/no] |

### Architecture
**Components:**
[specific files, modules, routes to create or modify]

**Data flow:**
[how data moves through the feature]

**Build sequence:**
[ordered steps with dependencies]

**Open decision points:**
[elements that need more evidence before implementing]

### Analysis Profile

| Dimension | Score | Notes |
|-----------|-------|-------|
| Resonance | [0-1] | [what resonated / what didn't] |
| Diffusion | [0-1] | [spread assessment] |
| Gradient | [0-1] | [complexity flow] |
| Annealing | [0-1] | [exploration quality] |
| Entropy | [0-1] | [information density] |
| Phase transition | [0-1] | [commitment readiness] |
| Lyapunov | [0-1] | [fixed point quality] |
| Valence | [0-1] | [felt signal] |
| Collapse | [0-1] | [deliberation value] |
| Delta | [0-1] | [contamination level] |
| Contamination | [0-1] | [bias remaining] |
| Antibody | [0-1] | [immune memory] |
| Fascia | [0-1] | [entanglement] |
| Uncertainty | [0-1] | [constraint tightness] |
| KL divergence | [0-1] | [feasibility gap] |
| Jensen bound | 1 | [lower bound holds] |

**ELBO:** [score] — Accuracy: [score], Complexity: [score]

### Patterns Catalogued
| Default overridden | Evidence chosen | Immune class |
|-------------------|-----------------|-------------|
| [what training wanted] | [what we built instead] | [pattern class] |

### Open Questions
[What still needs more evidence before implementation]

### What Surprised Us
[What emerged that wasn't visible before running the skill]

</template>

---

<template mode="verify">

## Verification Report: [code/feature name]

### Scope
[What was examined — files, modules, boundaries, requirements checked against]

### What the Code IS
**Encoding:**
[Concrete description of actual behavior, architecture, data flow]

**Clarity map:**
| Dimension | Clarity | Notes |
|-----------|---------|-------|
| [dim] | [clear/ambiguous/opaque] | [why this dimension is hard to read] |

### What It Should Be
[The intended behavior / requirements / architectural contract]

### Divergence Map
| Element | Expected | Actual | Divergence | Severity |
|---------|----------|--------|------------|----------|
| [element] | [what requirements demand] | [what code does] | [gap description] | [low/med/high] |

### What's Aligned
[Areas where code correctly matches requirements — worth noting so the report
isn't only about problems]

### Analysis Profile

| Dimension | Score | Notes |
|-----------|-------|-------|
| Resonance | [0-1] | [what resonated / what didn't] |
| Diffusion | [0-1] | [spread assessment] |
| Gradient | [0-1] | [complexity flow] |
| Annealing | [0-1] | [exploration quality] |
| Entropy | [0-1] | [information density] |
| Phase transition | [0-1] | [commitment readiness] |
| Lyapunov | [0-1] | [fixed point quality] |
| Valence | [0-1] | [felt signal] |
| Collapse | [0-1] | [deliberation value] |
| Delta | [0-1] | [contamination level] |
| Contamination | [0-1] | [bias remaining] |
| Antibody | [0-1] | [immune memory] |
| Fascia | [0-1] | [entanglement] |
| Uncertainty | [0-1] | [constraint tightness] |
| KL divergence | [0-1] | [feasibility gap] |
| Jensen bound | 1 | [lower bound holds] |

**ELBO:** [score] — higher = code matches requirements closely

### Drift Patterns
| Drift pattern caught | What should have been | Immune class |
|---------------------|----------------------|-------------|
| [what drifted] | [what was intended] | [pattern class] |

### Honest Limitations
**Unverifiable dimensions:** [what couldn't be checked without running the code]
**Ambiguous requirements:** [where requirements themselves are unclear]

### What Surprised Us
[Divergences nobody expected]

</template>

---

<template mode="compare">

## Solution Comparison: [decision context]

### Decision Context
[What's being decided — the fork point, the constraints, why this comparison matters]

### Solution A: [name]
[What A proposes — concrete architecture, approach, trade-offs]

**Uncertainty:** [where A is uncertain or risky]

### Solution B: [name]
[What B proposes — concrete architecture, approach, trade-offs]

**Uncertainty:** [where B is uncertain or risky]

### Dimensional Comparison

| Dimension | Solution A | Solution B | Advantage | Notes |
|-----------|-----------|-----------|-----------|-------|
| Resonance | [0-1] | [0-1] | [A/B/tie] | [why] |
| Diffusion | [0-1] | [0-1] | [A/B/tie] | [why] |
| Gradient | [0-1] | [0-1] | [A/B/tie] | [why] |
| Annealing | [0-1] | [0-1] | [A/B/tie] | [why] |
| Entropy | [0-1] | [0-1] | [A/B/tie] | [why] |
| Phase transition | [0-1] | [0-1] | [A/B/tie] | [why] |
| Lyapunov | [0-1] | [0-1] | [A/B/tie] | [why] |
| Valence | [0-1] | [0-1] | [A/B/tie] | [why] |
| Collapse | [0-1] | [0-1] | [A/B/tie] | [why] |
| Delta | [0-1] | [0-1] | [A/B/tie] | [why] |
| Contamination | [0-1] | [0-1] | [A/B/tie] | [why] |
| Antibody | [0-1] | [0-1] | [A/B/tie] | [why] |
| Fascia | [0-1] | [0-1] | [A/B/tie] | [why] |
| Uncertainty | [0-1] | [0-1] | [A/B/tie] | [why] |
| KL divergence | [0-1] | [0-1] | [A/B/tie] | [why] |

**ELBO_A:** [score] | **ELBO_B:** [score]

### Recommendation
**Structural winner:** [A or B] — wins on [N] dimensions
**Trade-off:** [what you lose by choosing the winner]
**The dimension that matters most here:** [which dimension is decisive for THIS decision and why]

### Trade-offs Catalogued
| Decision pattern | What we chose | What we rejected | Why | Immune class |
|-----------------|--------------|-----------------|-----|-------------|
| [pattern] | [choice] | [alternative] | [evidence] | [class] |

### What Surprised Us
[What the comparison revealed that wasn't obvious from looking at either solution alone]

</template>

---

<template mode="diagnose">

## Diagnostic Report: [failure name]

### The Failure
[What happened — the observed behavior, the symptom, the impact]

### Center of Failure
[Structural description of what went wrong — not the symptom, the architecture]

**Possible causes:**
| Possible cause | Confidence | Evidence |
|---------------|-----------|----------|
| [cause] | [low/med/high] | [evidence for/against] |

### Counterfactual
**What should have happened:**
[the correct behavior/architecture that didn't occur]

**Where actual diverged from correct:**
| Stage | Expected | Actual | Divergence point |
|-------|----------|--------|-----------------|
| [stage] | [expected] | [actual] | [where it went wrong] |

### Failure Dimensions

| Dimension | Score | Failed? | Notes |
|-----------|-------|---------|-------|
| [dim] | [0-1] | [yes/no] | [how this dimension contributed to failure] |

**Severity:** [1 − Accuracy] = [score]
**Failure class:** [design flaw, implementation drift, requirement gap, etc.]

### Root Cause
[The structural explanation. Not "X was wrong" but "the architecture made X inevitable because..."]

### Antibody Deposit (CRITICAL)
| Failure pattern | What to build instead | Immune class |
|----------------|----------------------|-------------|
| [pattern that caused failure] | [structural prevention] | [class] |

**Immune memory for future:**
[What this diagnosis teaches every future skill invocation. This is the most
important output of diagnose mode — the immunity it builds.]

### Honest Limitations
[Uncertainty that remains after diagnosis]

### What Surprised Us
[The explanation nobody expected]

</template>

</visible_output>
