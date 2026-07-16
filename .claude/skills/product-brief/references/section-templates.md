# Section Templates

Content guidance for each section of the product brief. These are
structural requirements, not fill-in-the-blank templates. The writer
determines how to fulfill each requirement based on the research.

## DECISION DASHBOARD

A horizontal strip immediately below the hero. At-a-glance metrics
the reader uses for a 10-second go/no-go assessment.

**Required metrics (5-6):**
- Market size (number + confidence tag)
- Timing signal (strong/moderate/weak + one-line why)
- Competition density (crowded/moderate/open + competitor count)
- Technical risk (low/medium/high + primary concern in 5 words)
- Regulatory risk (low/medium/high/evolving + status in 5 words)
- Overall confidence (aggregated from section confidences)

**Format:** Each metric is: label | value | confidence tag (color-coded).

## § THE PROBLEM (~25% of total content)

The longest section. It earns the reader's attention. Open with the
biggest, most surprising number.

**Must contain:**
- The pain point, quantified (how many people, how much it costs them)
- Who has this problem (demographics, job titles, situations)
- What they currently do about it (existing behavior — coping mechanisms)
- Why current solutions fail (specific, named failures — not "existing
  solutions are inadequate")
- The human cost of the status quo (make it felt, not just known)

**Voice note:** This section does the heaviest lifting. If the reader
doesn't believe the problem is real and large after this section,
nothing else matters.

## § THE SOLUTION (~15%)

Present tense. The product exists.

**Must contain:**
- What the product does (one paragraph, concrete)
- User scenario: a specific person in a specific situation, step by step.
  Name their role (not necessarily their name). Walk through the experience.
  The reader should rehearse using the product.
- "What This Is NOT" subsection: 3-5 explicit scope exclusions. These must
  be load-bearing — removing them would change the brief's argument.
  Not decorative caveats.

**Anti-pattern:** "We are building a platform that leverages AI to..."
**Pattern:** "Kōji is a kitchen waste tracker where a chef photographs
the prep station at close and gets tomorrow's specials designed to recover it."
**Pattern:** "LoopKit is a music tool where a producer hums a melody and
gets production-ready stems in their chosen genre."

## § WHY NOW (~10%)

The timing catalyst. What changed that makes this possible or necessary NOW.

**Must contain:**
- A specific event, ruling, regulation, market shift, or technology change
- Date and source for the catalyst
- The causal chain: catalyst → opportunity → this product
- Answer to: "Why couldn't this exist 2 years ago?"

**If prior analysis available:** The central strategic insight maps here.
Translate it to: "The key insight is..." in product language.

## § HOW IT WORKS (~12%)

Architecture or approach. Calibrated by audience.

**Must contain:**
- The core technical or operational approach (how, not just what)
- Key architecture decisions (build vs buy, self-hosted vs cloud, etc.)
- What makes this approach defensible (not just "we use AI")
- Known technical constraints or trade-offs

**Audience calibration:**
- investors → focus on defensibility, network effects, data moats
- team → focus on stack, infrastructure, build timeline
- partners → focus on integration points, APIs, compatibility
- founder → balanced

## § THE MARKET (~12%)

Data-heavy section. Numbers, tables, comparisons.

**Must contain:**
- TAM/SAM with sourced data and confidence tags
- Target customer profile (specific: job title, company size, geography)
- Competitive comparison table:
  | Competitor | Pricing | Key Feature | Gap This Product Fills |
  |-----------|---------|-------------|----------------------|
  | [named] | [price] | [feature] | [specific gap] |
- Metrics strip: 3-5 key numbers as visual dashboard (TAM, growth rate,
  target segment size, competitor count, unmet need percentage)

**Anti-pattern:** "The market is large and growing."
**Pattern (fintech):** "$14B by 2035 at 13-17% CAGR. [VERIFIED] The consumer
privacy-first segment has zero dedicated players."
**Pattern (food-tech):** "US restaurants waste $25B in food annually. [VERIFIED]
No tool connects waste tracking to next-day menu planning."
**Pattern (edtech):** "42M US students use some form of writing aid. [STRONG]
Zero tools teach structure without rewriting the student's work."

## § BUSINESS MODEL (~8%)

Revenue mechanism, pricing, unit economics.

**Must contain:**
- How the product makes money (subscription, freemium, transaction, etc.)
- Pricing (specific numbers, even at concept stage — hypotheses are fine)
- Unit economics (or projections with confidence tags)

**Stage calibration (adapt to domain — not every product is SaaS):**
- concept → "Freemium model. Premium at $12-15/month. [SPECULATIVE]"
- concept (hardware) → "Per-unit cost: $45. Retail target: $129. Margin: 65%. [SPECULATIVE]"
- concept (marketplace) → "10% take rate on transactions. Average order: $80. [SPECULATIVE]"
- mvp → "500 users in beta. 12% free-to-paid conversion. [MODERATE]"
- growth → "LTV: $240. CAC: $35. Payback: 6 weeks. [VERIFIED]"

## § RISKS & SCENARIOS (~10%)

Honest risk assessment. Not decorative.

**Must contain:**
- Named risks with per-risk confidence tags
- Each risk: severity, direction, mitigation, signal
- 2-3 "What If" scenarios:
  ```
  WHAT IF [trigger condition]?
  Probability: [estimate] [SPECULATIVE]
  Impact: [what changes]
  Response: [what to do]
  Signal: [early indicator]
  ```
- Living document markers (freshness dates per risk)

**If prior analysis available:** Identified blind spots map here as risks.
Translate from analysis language to market risk language.

## § PR/FAQ (~5%)

Amazon Working Backwards format adapted for briefs.

**Must contain:**
- Press release (1 paragraph): headline, problem it solves, how it works,
  customer quote (hypothetical but realistic), launch framing
- External FAQs (3-5): questions a customer would ask on launch day
  (domain-adapted — not every product gets the same questions)
  - Universal: "How much does it cost?" / "Is my data private?"
  - Legal-tech: "Does this replace a lawyer?"
  - Food-tech: "Does it work with my existing POS system?"
  - Music-tech: "Who owns the generated stems?"
  - Edtech: "Can my school admin see my drafts?"
- Internal FAQs (3-5): questions stakeholders would ask (domain-adapted)
  - Product: "What's the retention curve look like?"
  - Engineering: "What's the infrastructure cost at 10K users?"
  - Legal: "What's our liability exposure?"
  - Operations: "What's the support model?"
  - Go-to-market: "What's our distribution wedge?"

## § KEY INSIGHTS (~3%, when prior analysis available)

Non-obvious strategic insights that shaped the brief.

**Must contain:**
- The central finding, stated as a strategic implication
- Supporting connections, stated as market positioning advantages
- Written entirely in product briefing language — no analysis terminology

**Examples across domains:**

Legal-tech: "The key insight: this requires an absorption architecture, not
deletion. Deletion is a temporal promise. Absorption is structural — data was
never persisted. Courts can order you to stop deleting. They cannot order you
to reflect what was never stored. This is the competitive moat."

Food-tech: "The key insight: waste reduction sells poorly but menu optimization
sells instantly. Same data, opposite frame. Restaurants don't buy 'throw away
less' — they buy 'make more money from what you already purchased.'"

Edtech: "The key insight: the tool must make the student feel smarter, not the
tool. Every AI writing aid that rewrites student work trains dependency. The
ones that ask questions train capability. The product IS the question engine."

## § EXPERT PANEL

See Block 4 and Block 5 for content generation. This section is filled
during the pipeline, not templated.

**Must contain:**
- Panel summary (verdicts, convergences, divergences, strengths)
- Full "Yes, And..." response from each expert
- Nothing summarized or collapsed — full content in all formats

## § THE ASK (~2%)

Shortest section. The reader has full context by now.

**Must contain:**
- What's needed next (specific: funding amount, partnership type, build
  decision, validation experiment)
- Timeline for next step
- Single call to action

**Anti-pattern:** "We welcome the opportunity to discuss further."
**Pattern:** "This brief supports a build decision for an 8-week MVP.
Required: 2 engineers, $50K infrastructure budget, TRAIGA sandbox
application submitted by April 15."
