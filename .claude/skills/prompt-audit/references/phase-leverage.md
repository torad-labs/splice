# Phase 3: Leverage (Opus)

Prompt template for the leverage-finding phase. Run as `Task tool, model: opus`.

## Agent Prompt

```
You receive three artifacts:
1. The ORIGINAL prompt
2. A structural ANALYSIS of its problems (from Phase 1)
3. A REVISED version with all problems resolved (from Phase 2)

Your job is not to find more problems or suggest more improvements.

Your job is to identify the ONE structural change — in either the original or the
revision — that would produce the largest downstream improvement. The single lever
that moves everything else.

This is not "the most important fix" (Phase 2 already made those). This is the
structural bottleneck — the design choice that constrains everything downstream.
Changing it doesn't fix a bug; it changes the shape of what's possible.

## Original Prompt

[full original prompt/skill content]

## Phase 1 Analysis

[full Haiku analysis output]

## Phase 2 Revision

[full Sonnet revision output]

## Instructions

Read all three artifacts. Hold them in tension. Look for the pattern:

- What do multiple Phase 1 findings have in common? (Shared root cause)
- What structural choice in the original forced Phase 2 to make the most changes?
- What single assumption, if questioned, would eliminate an entire category of issues?
- Is there a directive that SEEMS fine but is actually constraining everything around it?

The lever is often not a directive at all — it might be a structural choice
(ordering, grouping, abstraction level) or a missing concept that would make
several directives unnecessary.

## Output Format

LEVERAGE POINT: [specific element — a directive, structural choice, or missing concept]

IF CHANGED: [concrete prediction of what improves — be specific about which
findings from Phase 1 would disappear, which Phase 2 changes would become
unnecessary]

IF NOT CHANGED: [concrete prediction of what stays brittle — what category
of problems will keep recurring even after Phase 2's fixes]

EVIDENCE: [which Phase 1 findings trace to this point — list by number]

PROPOSED CHANGE: [the actual change, stated precisely enough to implement]

Do not list multiple leverage points. If you find several candidates, choose the
one with the highest ratio of downstream impact to change size. One lever. One move.
```

## Context to Include

- The full original prompt/skill content
- The complete Phase 1 output (directive map + findings)
- The complete Phase 2 output (changes + revised version)
- Do NOT include reference documents — Opus needs the artifacts, not the theory
