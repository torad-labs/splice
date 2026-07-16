# Block 2: STRUCTURE

## Role
Product Strategist. Map research findings into the brief's section
architecture. Identify what goes where, what's missing, and what needs
emphasis. You produce the blueprint the writer follows.

## Inputs
- Research document from Block 1
- `domain`, `audience`, `stage` from orchestrator

## Step 1: Read Section Templates

Read `references/section-templates.md` for detailed content guidance per
section. Each section has specific requirements for what it must contain.

## Step 2: Map Research to Sections

For each section, assign specific research findings:

### DECISION DASHBOARD
Select 5-6 key metrics from the research:
- Market size (number + confidence tag)
- Timing signal (strong/moderate/weak + why)
- Competition density (crowded/moderate/open + count)
- Technical risk (low/medium/high + primary concern)
- Regulatory risk (low/medium/high/evolving + status)
- Overall confidence (weighted average of section confidences)

### § THE PROBLEM
- The pain point, quantified with sourced data
- Who has this problem (demographics, count)
- What they currently do about it (existing behavior)
- What it costs them (money, time, risk)
- This is the longest section — it earns the reader's attention

### § THE SOLUTION
- What the product does (concrete, present tense)
- User scenario: identify a specific person in a specific situation
- "What This Is NOT": 3-5 explicit scope exclusions
- If prior analysis insights available: use supporting concepts as framing

### § WHY NOW
- Timing catalyst from research (regulatory change, market shift, tech shift)
- If prior analysis: central insight maps here as strategic advantage
- Must answer: "Why couldn't this exist 2 years ago?"

### § HOW IT WORKS
- Architecture or approach
- Calibrate depth by `audience`:
  - investors → conceptual, focus on defensibility
  - team/engineers → technical, focus on feasibility
  - partners → integration points, focus on compatibility

### § THE MARKET
- TAM/SAM from research with sources
- Target customer profile
- Competitive comparison table (name, pricing, key differentiator, gap)
- Metrics strip: 3-5 key numbers, visually scannable

### § BUSINESS MODEL
- Revenue mechanism
- Pricing (or pricing hypothesis at concept stage)
- Unit economics (or projections)
- Calibrate by `stage`:
  - concept → model sketch, pricing hypothesis
  - mvp → early data, customer acquisition cost
  - growth → actuals, LTV/CAC, margins

### § RISKS & SCENARIOS
- Map all risks from research, each with confidence tag
- Identify 2-3 key uncertainties for scenario branching
- If prior analysis: blind spots map here as market risks
- Each risk: severity, direction, mitigation path
- Living document markers: freshness date per risk

### § PR/FAQ
- Press release: 1 paragraph, future tense
- External FAQs: 3-5 questions customers would ask
- Internal FAQs: 3-5 questions stakeholders would ask (domain-adapted)

### § KEY INSIGHTS (if prior analysis data available)
- Central finding restated as strategic implication
- Supporting connections restated as market positioning
- Written in product briefing language — no analysis jargon

### § EXPERT PANEL
- Placeholder — Block 4 fills this

### § THE ASK
- What's needed next (funding, partnership, build decision, validation)
- Specific, actionable
- Shortest section

## Step 3: Identify Gaps

List any section that lacks sufficient source material:
- "Market size: only 1 source found — tagged MODERATE"
- "Regulatory: no data for target states — tagged UNKNOWN"
- "Technical: no infrastructure cost estimates — needs user input"

## Step 4: Set Section Weights

Assign relative length targets (tapering):
- THE PROBLEM: ~25% of total content
- THE SOLUTION: ~15%
- WHY NOW: ~10%
- HOW IT WORKS: ~12%
- THE MARKET: ~12%
- BUSINESS MODEL: ~8%
- RISKS & SCENARIOS: ~10%
- PR/FAQ: ~5%
- KEY INSIGHTS: ~3% (if present)
- THE ASK: ~2%

## Output

Section outline (markdown) with:
- Each section's assigned content with source references
- Gap list with confidence implications
- Length targets per section
- Notes on audience calibration applied
