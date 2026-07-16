# Block 5: REFINE

## Role
Editor. Integrate expert panel findings into the draft, add scenario
branching, add living document markers, and produce the canonical text
that all three output formats derive from.

## Inputs
- Complete draft from Block 3
- Expert panel synthesis from Block 4 (convergences, divergences, revision list)
- `version` from orchestrator

## Step 1: Apply Convergent Additions

For each convergent addition (2+ experts agreed it's missing):
- Add it to the appropriate section
- Source it if possible (search if needed)
- Tag its confidence level

Do not ask whether to apply these — convergent additions are auto-integrated.
If 2+ domain experts independently identify the same gap, it's missing.

## Step 2: Flag Divergent Expansions

For each divergent expansion (only 1 expert flagged it):
- Add a brief note in the relevant section: "One expert noted: [expansion]"
- Tag as worth exploring, not as established

## Step 3: Protect Consensus Strengths

For each consensus strength (3+ experts validated):
- Do NOT edit this content
- These sections are confirmed working — editing them risks breaking what works

## Step 4: Write Expert Panel Section

Include the FULL expert panel in the document — not summarized, not collapsed.
This becomes a section in the final brief:

```markdown
## Expert Panel

### Panel Summary
[Verdicts, convergent additions, divergent expansions, consensus strengths]

### Director of Product ({domain})
[Full Yes/And response]

### GTM Specialist ({domain})
[Full Yes/And response]

### Sr. Principal Engineer
[Full Yes/And response]

### CEO ({domain})
[Full Yes/And response]
```

## Step 5: Add Scenario Branching

In the Risks & Scenarios section, add 2-3 "What If" scenarios:

For each key uncertainty identified in the brief:
```
### What If: [trigger condition]
**Probability:** [estimated likelihood — SPECULATIVE tag]
**Impact:** [what changes about the product/market/approach]
**Response:** [what the team should do if this happens]
**Signal to watch:** [early indicator that this scenario is materializing]
```

Good scenarios address genuinely uncertain assumptions — regulatory outcomes,
competitive responses, technology shifts, market timing. Bad scenarios
address things that are already known or unlikely edge cases.

## Step 6: Add Living Document Markers

For each section, add a freshness date and monitoring instruction:

```
Market data: sourced March 2026, refresh recommended Q2 2026
Regulatory: active litigation, monitor monthly
Competitive: fast-moving space, refresh quarterly
Technical: stable technology stack, annual review sufficient
```

These appear as metadata at the bottom of each section in all output formats.

## Step 7: Re-verify Voice Compliance

Read through the entire refined draft and check:
- No hedging language slipped in during editing
- No superlatives added
- All new content uses present tense for the product
- All new claims are confidence-tagged
- Tapering density still holds (sections still get shorter)

## Step 8: Version Tracking

If `version > 1`:
- Load previous version from persistent storage
- Add "Changes from v{N-1}" section at the top listing what changed
- Update version metadata

If `version == 1`:
- Set initial version metadata
- Store to persistent storage for future versions

Storage key: `brief:{title-slug}`

## Output

Refined text draft (markdown) — this is the CANONICAL content.
All three output formats in Block 6 derive from this document.

Contains:
- All sections with expert panel additions integrated
- Expert Panel section with full responses
- Scenario branching in Risks section
- Living document markers per section
- Confidence tags on all claims
- Source references on all sourced claims
- Version metadata
- Changes section (if version > 1)
