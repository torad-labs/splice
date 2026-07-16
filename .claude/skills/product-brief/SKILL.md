---
name: product-brief
description: >-
  Generate intelligent product briefs that show their confidence, reasoning,
  sources, and fragility. Use this skill whenever the user wants to create a
  product brief, one-pager, PR/FAQ, or any structured product concept document.
  Trigger on phrases like "product brief," "one-pager," "brief this idea,"
  "PR/FAQ," "help me think through this product," "structure this concept,"
  "intelligent brief," or any request to turn a product idea into a decision-ready
  document. Also trigger when the user provides a raw idea and asks to evaluate,
  structure, or formalize it into something presentable. This skill produces
  three complete outputs (HTML, DOCX, Markdown) with a confidence system,
  evidence provenance, expert panel, scenario branching, and version tracking.
  Do NOT trigger for pitch decks, business plans, landing pages, PRDs, blog posts,
  or competitive analyses — those are different document types.
---

# Product Brief

An intelligent briefing tool that produces decision-ready product documents.
Not a template filler — a thinking tool that researches, structures, drafts,
stress-tests with domain experts, and renders a complete brief in three formats.

## Philosophy

Decision velocity through earned trust. The brief earns trust by showing its
confidence levels, citing every source, admitting what it doesn't know, and
presenting expert perspectives that improve the concept. 80% done on first
pass — designed for iteration, not perfection.

## What Makes This Different

Standard product briefs are static documents with unsourced claims and hidden
assumptions. This skill produces a brief that:

1. **Shows its confidence** — per-claim confidence tags, not per-section
2. **Shows its sources** — hover tooltips (HTML), footnotes (DOCX), inline citations (MD)
3. **Shows its reasoning** — Key Insights section and expert panel reveal HOW
4. **Shows its fragility** — scenario branching tells you what breaks the thesis
5. **Shows its future** — freshness dates signal when to refresh

## Input

The skill accepts a flexible input. At minimum, a title and a body:

| Field | Required | Description |
|-------|----------|-------------|
| `title` | Yes | Product or concept name |
| `body` | Yes | Any length. Raw idea, prior analysis output, competitor notes, transcript — skill adapts |
| `domain` | No | Industry: legal-tech, fintech, devtools, etc. Propagates to expert panel |
| `audience` | No | Who reads this: investors, team, partners, founder (default: founder) |
| `stage` | No | concept, mvp, or growth (default: concept) |
| `analysis_id` | No | ID for prior deep analysis to integrate |
| `constraints` | No | Budget, timeline, regulatory, team size |
| `prior_art` | No | Known competitors to seed research |
| `version` | No | Brief version number for tracking (default: 1) |

See `references/input-schema.json` for full schema with detection logic.

## Pipeline

The skill runs 6 blocks sequentially. Each block is a subagent with a
specific role. Read the relevant block file before executing each block.

```
Block 1: RESEARCH        → blocks/1-research.md
Block 2: STRUCTURE       → blocks/2-structure.md
Block 3: DRAFT           → blocks/3-draft.md
Block 4: EXPERT PANEL    → blocks/4-expert-panel.md  (spawns 4 subagents + synthesis)
Block 5: REFINE          → blocks/5-refine.md
Block 6: RENDER          → blocks/6-render.md  (produces HTML + DOCX + Markdown)
```

### Block Summary

**Block 1: RESEARCH** — Web search (5-15 queries), technical docs lookup,
prior analysis integration. Produces a sourced research document with
confidence tags. Every claim has a source or is marked UNKNOWN.

**Block 2: STRUCTURE** — Maps research into the brief's section architecture.
Identifies gaps. Assigns content to sections. Produces a section outline.

**Block 3: DRAFT** — Writes the complete brief. Declarative voice, numbers
before narrative, present tense, explicit about unknowns, no superlatives.
See `references/voice-rules.md` for full voice specification.

**Block 4: EXPERT PANEL** — Spawns 4 domain-adapted experts simultaneously.
Each reads the full draft and responds in "Yes, And..." format (improve, not
criticize). A synthesis subagent identifies convergent additions and divergent
expansions. See `references/expert-prompts.md` for system prompts.

**Block 5: REFINE** — Integrates expert convergent additions, adds scenario
branching, adds living document markers, re-verifies voice compliance.
Produces the canonical text that all three output formats derive from.

**Block 6: RENDER** — Produces three complete outputs simultaneously:
- Interactive HTML one-pager (own design identity, see `references/design-brief.md`)
- DOCX (full content with footnotes, cover page, TOC)
- Markdown (full content, clean, editable)

## Section Architecture

Every brief includes these sections. Read `references/section-templates.md`
for detailed content guidance per section.

```
DECISION DASHBOARD     — At-a-glance metrics strip, color-coded confidence
§ THE PROBLEM          — Pain quantified (longest section)
§ THE SOLUTION         — Present tense, user scenario, scope exclusions
§ WHY NOW              — Timing catalyst, strategic insight
§ HOW IT WORKS         — Architecture/approach, audience-calibrated depth
§ THE MARKET           — TAM, competitive table, metrics strip
§ BUSINESS MODEL       — Revenue, pricing, unit economics
§ RISKS & SCENARIOS    — Confidence-tagged risks, What If branching, freshness dates
§ PR/FAQ               — Press release + external FAQs + internal FAQs
§ KEY INSIGHTS         — Non-obvious strategic insights (when prior analysis available)
§ EXPERT PANEL         — Full Yes/And from 4 domain experts + synthesis
§ THE ASK              — What's needed next (shortest section)
```

Sections taper in length — the Problem section is the longest, The Ask is
the shortest. This accelerates reading speed and pulls the reader through.

## Confidence System

Read `references/confidence-system.md` for full specification.

7 levels, color-coded, applied per-claim (not per-section):

| Tag | Color | Meaning |
|-----|-------|---------|
| VERIFIED | Deep green | 3+ independent sources agree |
| STRONG | Teal | 2 sources, recent data |
| MODERATE | Amber | Single source or extrapolated |
| EMERGING | Orange | Active change, could shift monthly |
| SPECULATIVE | Red-orange | Logical inference, no data |
| UNKNOWN | Slate | No data found |
| CONTESTED | Purple | Sources disagree |

Plus modifier tags: EVOLVING, SEASONAL, REGIONAL, SELF-REPORTED.

## Evidence Provenance

Every factual claim has source attribution:
- HTML: hover/tap tooltip with source name, URL, date, confidence
- DOCX: footnotes
- Markdown: inline `[Source: name, URL, date]`

No claim without a source. If data can't be found, it's tagged UNKNOWN —
never fabricated.

## Version Tracking

The skill tracks versions via persistent storage. When `version > 1`, it
loads the previous version and operates on delta — what changed, what
shifted, what was resolved. Outputs include a "Changes from v{N-1}" section.

## Output Files

All outputs go to `/mnt/user-data/outputs/`:
```
product-brief-{title-slug}-v{N}.html
product-brief-{title-slug}-v{N}.docx
product-brief-{title-slug}-v{N}.md
```

## What This Is NOT

- Not a pitch deck (use presentation skills)
- Not a business plan (too long, different format)
- Not a landing page (different purpose)
- Not a PRD (downstream — brief comes first)
- Not a blog post (different voice, different audience)

## Reference Files

| File | When to read |
|------|-------------|
| `references/input-schema.json` | Before Block 1 — input detection logic |
| `references/section-templates.md` | Before Block 2 — section content guidance |
| `references/voice-rules.md` | Before Block 3 — writing voice specification |
| `references/expert-prompts.md` | Before Block 4 — expert system prompts |
| `references/confidence-system.md` | Before Block 1 — confidence tagging rules |
| `references/design-brief.md` | Before Block 6 — HTML design principles |
