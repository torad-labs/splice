# Block 9c2: VISUAL SKELETON

## Purpose

Establish the carousel's visual identity BEFORE organs run.

After 9c creates the narrative architecture (judo phases, content per
slide, belief shifts), the visual skeleton crystallizes the carousel's
OVERALL design identity: which force colors fire, which components
appear, what the type scale range is, where visual density peaks and
valleys live, how backgrounds vary.

This is the EPIGENETIC ENVIRONMENT. It doesn't change the DNA (the 6
genes are the same for every carousel). It determines which genes
express. Different content → different epigenetic environment →
different gene expression → different carousel species.

Without this pass, per-slide organ execution produces locally optimal
but globally incoherent visual decisions — like stem cells
differentiating without positional signals.

**Run this in thinking space.**

## Input

```
BLOCK 9c OUTPUT: [slide architecture with phases, content, belief shifts]
NAVIGATION VERTICES: [surviving vertices from Block 7]
CROSSBAR: [from Block 6]
```

## Process

Read ALL slide content from 9c simultaneously — not one slide at a
time. Feel the content's demands at the CAROUSEL level, not the
slide level. The visual skeleton is about the WHOLE.

Run the parallax on every decision:

```
P_training: "Carousels should use [full palette / all components / wide scale]"
P_evidence: "THIS content demands [specific subset / specific vocabulary / specific range]"
delta: |P_training - P_evidence|
Choose: Go with the evidence.
```

---

### 1. Force Palette

Which of the 5 forces fire for THIS content?

Read all slide content. Which forces are PRESENT in the content?

- **Gold** (crystallization) — are there moments where scattered
  evidence collapses into clarity?
- **Cyan** (grounding) — are there specific numbers, names, sources?
- **Violet** (negation) — does the content push AGAINST something?
- **Green** (resolution) — does the content land on something actionable?
- **White** (stress) — is there weight without directional force?

The palette is a SUBSET. Not every carousel uses all 5 forces. A
carousel about a single breakthrough might be gold-dominant with cyan
grounding. A carousel about a failure might be violet-dominant with
green resolution at the end. Content determines.

**g_trained check:** "Am I including all 5 forces because carousels
should be colorful, or because THIS content genuinely demands them?"
If the content has no negation, violet doesn't fire. Period.

---

### 2. Component Vocabulary

Which text components will appear in THIS carousel?

Available components (from Body spec):

- **Stat Block** — does the content contain numbers that stop thumbs?
- **Evidence Card** — does the content contain grounded claims needing
  visual containment?
- **Divider** — does the content have semantic transitions within slides?
- **Mono Label** — does the content have metadata (dates, categories)?
- **Bordered Container** — does the content have structured data?
- **Anchor Phrase** — does the content have phrases that should echo?
- **Copy** — standard body text (always present)

The vocabulary is a SUBSET. Most carousels use 3-4 component types,
not all 7. A narrative-driven carousel might use only Copy + Anchor
Phrase + Divider. A data-driven carousel might use Stat Block +
Evidence Card + Mono Label + Copy.

**g_trained check:** "Am I including stat blocks because 'carousels
should have data callouts,' or because THIS content has numbers that
demand display-scale rendering?" If there are no thumb-stopping
numbers, stat blocks don't fire.

---

### 3. Type Scale

What's the size range for THIS carousel?

The Phone-First Scale provides FLOORS (36px body, 56px headlines,
28px labels). But the CEILING and DISTRIBUTION are content-driven.

- **Narrow scale** (40px-72px): quiet, contemplative content. Visual
  hierarchy is subtle. Everything close in size.
- **Moderate scale** (36px-96px): most carousels. Clear hierarchy
  without dramatic extremes.
- **Wide scale** (36px-140px): bold, data-driven content. Stats at
  120px+. Labels at 28px. Maximum visual hierarchy.

Read the content. How much visual hierarchy does it DEMAND?

- Single dominant element per slide that needs to TOWER? → Wide
- Continuous narrative where no element dominates? → Narrow
- Mix of data moments and narrative flow? → Moderate with specific peaks

**g_trained check:** "Am I choosing a wide scale because 'carousels
need visual impact,' or because THIS content has elements that
genuinely demand 120px display?"

---

### 4. Density Rhythm

Where are the visual density peaks and valleys across the carousel?

Density = how many visual elements compete for attention on a slide.

- HIGH: stat block + evidence card + animation + multiple text sizes
- MODERATE: copy + one component + animation
- LOW: single anchor phrase + breathe animation + generous Ma
- FOCUSED: one dominant element with maximum impact

The density rhythm tracks judo phases but is content-specific:

- **CATCH** → typically HIGH (thumb-stopper demands visual weight)
- **COMMIT** → typically MODERATE (grounding, not overwhelming)
- **REDIRECT** → VARIES (disruption might be density DROP or SPIKE)
- **THROW** → typically FOCUSED (one element, maximum impact)
- **LAND** → typically LOW (satisfaction through spaciousness)
- **LOOP** → typically MODERATE (echo of CATCH at different altitude)

Content overrides. A carousel whose THROW is a devastating stat might
be HIGH density on the throw slide. A carousel whose CATCH is a quiet
question might be LOW density on the catch slide.

**g_trained check:** "Am I assigning density by phase template, or
by what THIS content demands at each point?"

---

### 5. Background Variation

How will backgrounds differ across slides?

Framework provides: `var(--bg)` and `var(--bg-r)`. Gradients available.

Options (content selects):

- **Phase-driven**: background shifts at phase transitions (e.g.,
  `--bg` for CATCH/COMMIT, `--bg-r` for REDIRECT/THROW, return for
  LAND/LOOP). Creates spatial markers for narrative shifts.
- **Alternating**: `--bg` and `--bg-r` in rhythm. Creates visual
  breathing pattern.
- **Gradient transitions**: subtle gradients at the REDIRECT pivot.
  Marks the judo turn spatially.
- **Minimal**: same background throughout. Only if content demands
  unbroken visual continuity.

**g_trained check:** "Am I alternating backgrounds because 'carousels
need visual variety,' or because THIS content's phase transitions
demand spatial markers?"

---

`>>> CATCH <<<` After establishing all 5 dimensions, check: could this
visual skeleton be swapped to a different carousel on a different
topic? If the palette, components, scale, rhythm, and background plan
would work unchanged for a different carousel, the skeleton is a
template. Every dimension must trace to THIS content's specific
demands. Run the template-swap test now.

---

## Output

```
VISUAL SKELETON:
  FORCE PALETTE:
    ACTIVE: [list of forces with approximate distribution %]
    INACTIVE: [forces that don't fire for this content]
    RATIONALE: [why THIS content demands these forces]

  COMPONENT VOCABULARY:
    ACTIVE: [list of components this carousel will use]
    INACTIVE: [components not needed for this content]
    RATIONALE: [why THIS content demands these components]

  TYPE SCALE:
    RANGE: [min-max in px]
    CHARACTER: [narrow / moderate / wide]
    RATIONALE: [why THIS range for THIS content]

  DENSITY RHYTHM:
    CATCH: [HIGH/MODERATE/LOW/FOCUSED] — [content rationale]
    COMMIT: [level] — [content rationale]
    REDIRECT: [level] — [content rationale]
    THROW: [level] — [content rationale]
    LAND: [level] — [content rationale]
    LOOP: [level] — [content rationale]

  BACKGROUND PLAN:
    STRATEGY: [phase-driven / alternating / gradient / minimal]
    RATIONALE: [why THIS strategy for THIS content]

  CAROUSEL IDENTITY:
    [1-2 sentences: what does this carousel LOOK LIKE as a whole?
     Not what it says — what it looks like. The body plan.]
```

## Relationship to Organs

The visual skeleton is INPUT to all three organs:

**THE VOICE (9d):** Uses density rhythm to calibrate word counts. HIGH
density slides need shorter copy (making room for visual elements).
LOW density slides can breathe longer. Voice doesn't change its judo
throw — it adjusts SCALE within the visual environment.

**THE NERVES (9h):** Uses density rhythm to calibrate animation
intensity. HIGH density slides favor BREATHE (not competing with
visual elements). FOCUSED slides with one dominant element can support
DEMONSTRATE. Uses force palette to ensure animation colors don't
clash with text force colors.

**THE BODY (9g):** Uses ALL five dimensions. Force palette limits
what colors appear. Component vocabulary limits what components are
available. Type scale sets the size range. Density rhythm sets the
per-phase complexity budget. Background plan sets the variation
strategy. Body's per-slide gene expression operates WITHIN the
skeleton's constraints.

The skeleton CONSTRAINS the organs. It does not DETERMINE their
outputs. Within the skeleton's constraints, each organ's gene
expression still runs — making per-slide decisions that are
content-driven. The skeleton prevents global incoherence. The genes
prevent per-slide templateness.

## Quality Gate

- All 5 dimensions declared with content rationale
- Force palette is a genuine subset (not all 5 by default)
- Component vocabulary is a genuine subset (not all 7 by default)
- Type scale range matches content demands (not "wide because impact")
- Density rhythm is content-driven, not phase-templated
- Background plan has rationale beyond "variety"
- Template-swap test: swap the content → does the skeleton change?
  If it stays the same, it's a template
- Carousel identity statement distinguishes this carousel from others
