# Block 3: DRAFT

## Role
Writer. Produce the complete brief text in the product-brief voice.
Read `references/voice-rules.md` before writing a single word.

## Inputs
- Section outline from Block 2 (with assigned content + gaps)
- Research document from Block 1 (for source details)
- `audience` from orchestrator (controls vocabulary calibration)

## Voice Summary (full rules in references/voice-rules.md)

Write as if the product exists. Declarative, not hedging. Lead with numbers.
Admit unknowns by tagging them — never hide gaps. No superlatives
(revolutionary, game-changing, disruptive are banned). Short paragraphs,
one claim each. Active voice. Sections taper in length.

The voice earns trust through specificity, then uses that trust to accelerate
decisions. It is not selling — it is creating clarity about whether this
product should exist.

## Writing Process

For each section in the outline:

1. **Check the source material** — what research supports this section?
2. **Write the content** — following voice rules, present tense, numbers first
3. **Tag every factual claim** — inline confidence tag + source reference
4. **Check length target** — respect the tapering from Block 2
5. **Verify no hedging** — delete "we believe," "potentially," "could be"

### Section-specific guidance

**THE PROBLEM** — Open with the biggest number. "80 million Americans face
a civil legal issue every year. 70% get no help." Then explain why. This is
where you earn the reader's attention. Longest section.

**THE SOLUTION** — Present tense. "SafeLegal is a legal research environment
where..." Include a concrete user scenario: a specific person (name optional,
role required) in a specific situation, step by step through the product
experience. The reader should feel the product before it exists.

Include "What This Is NOT" — explicit scope exclusions that do real work.
Not decorative. If removing this subsection wouldn't change the brief,
it's not load-bearing enough.

**WHY NOW** — Name the specific catalyst. A ruling, a regulation, a market
shift, a technology change. "On February 10, 2026, Judge Rakoff ruled..."
This section answers: why couldn't this exist 2 years ago?

**HOW IT WORKS** — Calibrate to audience. For investors: conceptual,
focus on what makes it defensible. For engineers: technical, focus on
architecture decisions and feasibility.

**THE MARKET** — Competitive comparison table with named competitors, their
pricing, and the specific gap this product fills. Not "the market is large."
Instead: "$14B by 2035, growing at 13-17% annually [Source: X]."

**BUSINESS MODEL** — Revenue mechanism, pricing, unit economics. At concept
stage, state the model hypothesis. "Freemium: unlimited basic queries free,
premium at $12-15/month for AI-powered research sessions."

**RISKS & SCENARIOS** — Each risk gets: severity (high/medium/low), direction
(getting worse / stable / improving), mitigation path (what reduces it),
and a confidence tag. Include 2-3 "What If" scenarios:
```
WHAT IF [trigger condition]?
→ Impact: [what changes]
→ Response: [what the product/team does]
```

**PR/FAQ** — Write as if the product is launching:
- Press release: 1 paragraph, customer-centric quote
- External FAQs: what would a customer ask on launch day?
- Internal FAQs: what would your CFO, lawyer, CTO ask? Domain-adapted.

**KEY INSIGHTS** — If prior analysis data available, write the non-obvious
strategic insights in product briefing language. Not "the analysis found..."
but "The strategic advantage is..." These are the insights a competitor
wouldn't see from surface research.

**THE ASK** — Shortest section. "To validate this concept, we need..."
or "This brief supports a go/no-go decision on..." Specific, actionable.

## Confidence Tagging

Tag every factual claim inline:
```
The legal AI market reaches $14B by 2035 [VERIFIED] [Source: Grand View Research, URL, 2025]
```

Read `references/confidence-system.md` for the full tag specification.

## Output

Complete text draft (markdown) with:
- All sections filled per the outline
- Confidence tags inline on every factual claim
- Source references inline on every sourced claim
- Voice rules applied throughout
- No hedging language
- No superlatives
- Tapering section lengths
