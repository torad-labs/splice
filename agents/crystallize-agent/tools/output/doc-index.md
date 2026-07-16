# Index Document — Hub Page

## generate
This is the hub page — the executive summary. The reader who reads ONLY this page
should walk away with the central insight, know what to build, and know what was rejected.

DO NOT include architecture details, build sequence, acceptance criteria, or features here.
Those belong in prd.html. DO NOT include detailed rejections or anti-patterns here.
Those belong in rejection.html. The index is a decision document, not a comprehensive report.

### Required sections (in order):

1. **Doc-nav cards** — Navigation cards linking to PRD (constructive) and Rejection (boundaries)
   so the reader can jump immediately. These come FIRST, before any content.

2. **Core Finding** — The one insight that crystallization revealed. One callout block.
   What wasn't visible before running the analysis. This is the headline.

3. **Key Perspectives** — The surviving vertices as perspective cards with delta scores.
   3-5 cards maximum. Each names a specific approach and why it survived.

4. **Quality Snapshot** — The ELBO card with top 2 and bottom 2 dimensions as pills.
   Not the full 16-dimension grid (that's in prd.html). Just the overall score and
   the dimensions that matter most. Explain what the score means:
   - ELBO > 0.7: strong — accuracy dominates complexity, plan is efficient
   - ELBO 0.4-0.7: adequate — significant complexity cost, review constraints
   - ELBO < 0.4: weak — plan costs more than it delivers, simplify or re-scope

5. **Patterns Catalogued** — 2-4 key antibodies. What assumptions were overturned,
   what training defaults were caught. Brief — the full list is in the PRD.

Structure: Doc-nav → Core finding → Key perspectives → Quality snapshot → Patterns.

## verify
This is the verification summary. Lead with navigation cards (fix plan + divergences).
Then the verdict — does the input match requirements?

### Required sections:
1. **Doc-nav cards** — Links to PRD (fix plan) and Rejection (divergences).
2. **Verdict** — Pass/fail in one sentence with overall alignment score.
3. **Top Divergences** — The 3-5 most severe gaps, one line each with severity badge.
4. **Quality Snapshot** — ELBO + key dimension pills.

Structure: Doc-nav → Verdict → Top divergences → Quality snapshot.

## compare
This is the comparison summary. Lead with navigation cards then the structural winner.

### Required sections:
1. **Doc-nav cards** — Links to PRD (winner's plan) and Rejection (loser's analysis).
2. **Winner** — Which solution wins and the decisive dimension (one sentence).
3. **Dimensional Summary** — Compact table: dimension, A score, B score, advantage.
4. **Trade-off Cost** — What you lose by choosing the winner.
5. **Quality Snapshot** — ELBO + key dimension pills.

Structure: Doc-nav → Winner → Dimensional summary → Trade-off → Quality snapshot.

## diagnose
This is the diagnostic summary. Lead with navigation cards then the root cause.

### Required sections:
1. **Doc-nav cards** — Links to PRD (prevention plan) and Rejection (failure analysis).
2. **Root Cause** — Structural explanation in one callout. Not the symptom.
3. **Failure Mechanism** — How the architecture made the failure inevitable.
4. **Antibody Deposit** — The immune memory this diagnosis produces.
5. **Quality Snapshot** — ELBO + key dimension pills.

Structure: Doc-nav → Root cause → Failure mechanism → Antibody → Quality snapshot.
