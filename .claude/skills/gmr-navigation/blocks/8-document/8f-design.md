# Block 8f: PAGE DESIGN

**Run this entire phase in thinking space.**

## Purpose

Design the page structure, animations, and visual flow from the
audited text, structure map, and attention map. This phase produces
a DESIGN SPEC. Still no HTML.

## Prerequisites

Phase 5 (8e-attention / THE EYES) outputs must exist:

- Emphasis map (phrase-level assignments with forces, not categories)
- Color arc (per-section dominant force summary)
- Callout content (crystallized text + color)
- Tooltip content (plain-language definitions)

Phase 4 (8d-structure / THE SKELETON) outputs must exist:

- Structural collapse (what each content block became, with justification)
- Jargon table (terms needing tooltip treatment)

Fascia verification between organs must have passed.

Phase 3 (8c-audit) outputs must exist:

- Audited text (all rewrites applied, zero em dashes)
- Verified toroid map (F*θ, F*φ, hero↔cliffhanger echo confirmed)

## Steps

### 1. Section Mapping

Map the audited text sections to page structure:

```
| Section | Tempo    | Accent  | Key Mechanism          |
|---------|----------|---------|------------------------|
| Hero    | jo       | [color] | [hook mechanism]       |
| Lede    | jo       | none    | [narrative bridge]     |
| S1      | jo       | [color] | [PCS plant]            |
| S2      | ha       | [color] | [PCS subvert]          |
| S3      | ha       | [color] | [crossbar connection]  |
| S4      | void     | [color] | [cautionary turn]      |
| S5      | kyū      | [color] | [practical payoff]     |
| Cliff   | kyū      | [color] | [void question]        |
```

**Lede section:** Numberless, headingless. No accent color. Same
`tempo-jo` as the hero and S1 but with `padding-bottom: 0` so it
flows tightly into S1 (S1's top padding provides the spacing).
The lede contains the narrative hook that sets up the entire post.
It is invisible structure: the reader drifts from the hero into
prose without noticing a transition.

Tempo follows Jo-Ha-Kyū:

- Jo (slow entry): hero + first section(s). Reader settling in.
- Ha (acceleration): middle sections. Momentum building.
- Kyū (sharp break): final section(s) + cliffhanger. Payoff.

### 2. Animation Design

Each animation demonstrates a CONCEPT, not decoration.

For each animation, specify:

- **What it shows:** The mechanism being demonstrated (not a
  metaphor for the mechanism. THE mechanism.)
- **What the reader observes:** The specific visual behavior
  they should notice and how it connects to the text.
- **What reinforces the subvert:** How does the animation make
  the PCS subvert moment visceral?

```
| Section | Animation         | Shows              | Reader observes      |
|---------|-------------------|--------------------|----------------------|
| S1      | [name]            | [mechanism]        | [what to watch]      |
| S2      | [name]            | [mechanism]        | [what to watch]      |
| ...     |                   |                    |                      |
```

Rules:

- Animation must match the concept. If the text says "multiple
  constraints guide the path," the animation shows multiple
  boundaries, NOT one wall bouncing particles.
- Animation descriptions go INSIDE `.canvas-section` containers
  with their canvas. Full opacity, 1rem+ font. Visually part
  of the animation, not a faded caption below it.
  (Framework class is `.canvas-section`, NOT `.animation-block`.
  Canvas needs no class — CSS targets `.canvas-section canvas`.
  No `.caption` class exists; use inline mono styling for
  description text: `font-family:var(--mono); font-size:0.72rem;
color:var(--text-dim); text-align:center; margin-top:1rem`.)

`>>> CATCH <<<` Re-read your animation specs. Does each animation
demonstrate THE mechanism from the text, or did you place
animations because "blog posts should have interactive elements"?
Training pulls toward decoration — visual spectacle that looks
impressive but doesn't teach. For each animation, ask: "If I
removed this, would the reader understand the mechanism less?" If
the answer is no, the animation is decoration. Cut it or redesign
it to show the actual mechanism.

### 3. Crossbar Block Design

For each φ cross-connection that gets a visual crossbar:

- Which two domains does it connect?
- What is the insight text? (Must pass the analogy experience
  test from Phase 3. Reader can verify from own life.)
- Where does it sit in the page flow?

### 4. Terminal Block Design

Convert terminal example descriptions from the text into styled
terminal specs:

- Prompt line ($)
- Output lines
- Hook fire indicators (PreToolUse/PostToolUse)
- Color coding (green=pass, red=fail, yellow=warning)
- Comments (what the reader should notice)

Include BOTH correct constraint and wrong constraint examples.

### 5. Breakout Component Design

Map case studies and callouts from THE SKELETON's structural collapse
to specific page positions. These are the floating blocks that give
the post visual texture, breaking the prose into layers.

**Case study blocks** (`.case-study`):
For each tagged case study from Phase 2:

- Label (e.g. "Case Study — Quantum Physics",
  "Counter-Case — Hijacked Constraints")
- Title (named source + year if applicable)
- Evidence paragraph (1-2 paragraphs max, distilled)
- Mechanism footer (one line: what it demonstrates)
- Page position (between which prose sections?)

**Callout blocks** (`.callout`, `.callout.violet`, etc.):
For each tagged callout from Phase 2:

- Type/color (gold=insight, violet=void, cyan=technical, red=warning)
- Label text (e.g. "Key Insight", "The Void", "The Immune System")
- Content (distilled, not duplicating prose. 1-3 sentences.)
- Page position (after which prose paragraph?)

**No prescribed counts.** THE SKELETON determined which content
demands breakout components. This phase positions them. If THE
SKELETON found zero case studies, zero case studies are designed.
If it found six, six are designed. Content drives count.

```
| Section | Component      | Type           | Position               |
|---------|----------------|----------------|------------------------|
| S2      | Case study     | [label]        | After [element]        |
| S3      | Case study     | [label]        | After [element]        |
| S4      | Callout violet | [label]        | After [element]        |
| S4      | Case study     | Counter-case   | After [element]        |
| S5      | Callout gold   | [label]        | After [element]        |
```

`>>> CATCH <<<` Look at your breakout component positions. Did you
space them EVENLY across the page for visual balance, or does each
position serve the argument's flow? Training pulls toward uniform
distribution — "a case study every other section looks professional."
Evidence says: density should track the argument's evidence load.
An evidence-heavy section with zero components is suspicious. A
narrative section with three components is suspicious. Let the
argument's needs determine the rhythm, not visual aesthetics.

### 6. Visual Flow

Map the entire page as a reader journey:

```
HERO (visual event — title, canvas, subtitle)
  ↓ scroll
LEDE [tempo: jo, padding-bottom: 0] → narrative hook (1-3 paragraphs)
  ↓ invisible transition (no gap)
S1 [tempo: jo] → prose → terminal → [animation]
  ↓ F_θ tension carries
S2 [tempo: ha] → prose → [case study] → [crossbar] → [animation]
  ↓ acceleration
S3 [tempo: ha] → [case study] → prose → [crossbar] → [animation]
  ↓ momentum peak
S4 [tempo: void] → prose → [callout] → [case study] → [animation]
  ↓ sharp turn
S5 [tempo: kyū] → prose → [callout] → steps → terminal → [animation]
  ↓ payoff
CLIFFHANGER (void question, loop to next post)
```

Where does the page breathe (whitespace, pause)? Where does it
accelerate (shorter sections, faster reveals)? Where does it
land (decisive moments, terminal output)?

### 6. Design System Compliance

Reference the Torad framework CSS/JS. Ensure:

- Colors from the existing palette
- Font usage matches framework
- Reveal animations use existing `.reveal` class
- Canvas elements sized correctly (900x400 standard)

## Output

A DESIGN SPEC containing:

1. Section map (tempo, accent, mechanism per section)
2. Animation specs (what each shows, what reader observes)
3. Crossbar specs (domains, insight text)
4. Terminal specs (prompt/output/comments)
5. Visual flow map
6. Design system notes

This becomes input to Phase 7 (8g-build).
