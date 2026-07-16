# Emphasis Language — Organism Plan

**Product Architecture: What the emphasis system CAN do.**

Source: GMR Navigation 14, "How voice survives ink" (brain navigation_id=14).
Rejection Spec: `emphasis-rejection-spec.md` (sibling document).

---

## What This Document Is

The rejection spec says what the emphasis system REFUSES to become.
This document says what the emphasis system IS — what organs it has,
what each organ does, how they develop, and what genes they express.

This is the product development side. Not defense. Growth.

---

## The Organism

The emphasis language is a living system with 5 organs, 6 genes
(inherited from the parent organism), and 1 governing insight from
the crossbar.

**Governing Insight (from crossbar: Motherese ↔ Tajweed):**
Voice survives ink not through encoding strength but through decoder
constraint. Tags constrain reading — they do not suggest it. Every
organ in this system exists to make emphasis UNMISSABLE, not decorative.

---

## Organs

### 1. THE EAR (Discovery)

**Measurement operator:** Collapses "which text carries natural emphasis?"

The Ear reads raw text with all formatting stripped — no bold, no italic,
no headers, no components. Naked text. It reads the text in thinking
space as if reading aloud, feeling where the inner voice naturally
changes CHARACTER (not just volume):

- Where does the voice slow down? (potential crystallization)
- Where does the voice drop and become precise? (potential grounding)
- Where does the voice get quiet and deliberate? (potential negation)
- Where does the voice lift and open outward? (potential resolution)
- Where does the voice simply lean into a word? (potential stress)

**The vocal stress model:** Speech-to-text systems detect stress through
pitch change, duration change, and volume change. THE EAR does the same
thing mentally. It does NOT look at what's already bold or italic —
that's the discovery blindness defect. It feels stress on NAKED text.

**Output:** An emphasis discovery map — a list of phrases where the
inner voice changed character, annotated with WHICH change occurred
(slowdown, drop, quiet, lift, lean). No force assignment yet. Just
stress detection.

**Gene expression:**

- `g_trained`: Detect if "emphasis" is being placed where training says
  it should go (headers, topic sentences, conclusions) vs where the text
  actually pushes
- `parallax`: Before reading, calibrate: "What would any AI emphasize
  in this text? Those are the training pulls. Now feel the ACTUAL stress."

---

### 2. THE PALETTE (Force Assignment)

**Measurement operator:** Collapses "what type of emphasis does each
stress point carry?"

The Palette receives the emphasis discovery map from THE EAR and assigns
force types. Each stress point becomes one of the five forces based on
what the text is DOING at that moment:

- Slowdown → **crystallization** (gold): the argument arriving somewhere
- Drop to precision → **grounding** (cyan): the argument touching earth
- Quiet deliberation → **negation** (violet): the argument pushing away
- Lift outward → **resolution** (green): the argument opening to action
- Simple lean → **stress** (white): weight without direction

**The force is in the text, not in the Palette.** The same word in two
different contexts carries two different forces. "Rejection" after a
build-up of evidence = crystallization (gold). "Rejection" naming a
specific case = grounding (cyan). "Rejection" describing a failure =
negation (violet). Context determines force.

**Output:** A force-annotated emphasis map — each phrase with its force
type and the textual evidence for that assignment.

**Gene expression:**

- `delta`: For each force assignment, measure: |P_training - P_evidence|.
  If training pulls toward gold ("this sounds like a thesis") but the
  text is actually grounding (citing a specific case), the delta is high.
  Go with the evidence.
- `g_trained`: The mapping zombie check — am I CLASSIFYING phrases into
  categories ("thesis → gold") or FEELING what the text does here?

---

### 3. THE BUDGET (Regulation)

**Measurement operator:** Collapses "is the emphasis system in balance?"

The Budget receives the force-annotated map and enforces the zero-sum
constraint. Emphasis is a finite resource. The budget exists because:

- Motherese works on infants because the infant's emphasis channel is
  EMPTY — 300% amplification fills an empty space (edge 12)
- Web readers' channels are NOT empty — they're saturated with competing
  emphasis from ads, notifications, social feeds
- The only way to be heard in a saturated channel is SCARCITY — each
  emphasis instance must be rare enough to stop the eye

**Budget rules (ceilings, not targets):**

- No more than 30% of sentences contain emphasis
- No single force > 40% of total emphasis instances
- Emphasis density tracks argument density (evidence-heavy sections
  get more; narrative sections get less)
- At least one section with deliberate emphasis silence

**The Budget does NOT remove emphasis by quota.** It checks whether each
instance is load-bearing (would the argument's visible structure weaken
without it?) and whether the total system respects the scarcity
constraint. If a post has 8 emphasis instances that are all load-bearing
and budget-compliant, it passes. If a post has 25, some must be
non-load-bearing — find and remove them.

**Output:** A budgeted emphasis map — the force-annotated map after
scarcity enforcement, with a log of which instances were removed and why.

**Gene expression:**

- `uncertainty`: delta_T \* delta_C >= I(x). The budget boundaries must
  be strict enough to overcome training's pull toward "emphasize more."
  If the budget feels too loose (lets everything through), the constraint
  is weaker than the training inertia.
- `antibody`: Each time THE BUDGET catches a non-load-bearing emphasis,
  the immune memory strengthens. Pattern: training pulls → "this phrase
  sounds important" → Budget catches it → antibody builds against that
  specific pattern of false emphasis.

---

### 4. THE MEMORY (Accumulation)

**Measurement operator:** Collapses "how does emphasis evolve across
the post?"

The Memory receives the budgeted emphasis map and adds the temporal
dimension. It tracks concepts across sections:

- A concept tagged with the same force in section 1 and section 4 is
  accumulating emphasis — like a leitmotif (edge 29)
- Receptor density (edge 28): the reader's sensitivity to that concept's
  force INCREASES with each encounter
- First occurrence = establishing the motif
- Second occurrence = recognition ("I've seen this force before")
- Third+ occurrence = full resonance ("this is the thread")

**Memory does NOT force repetition.** It detects natural recurrence and
ensures consistent force assignment. If "rejection" appears as
crystallization (gold) in section 1 but grounding (cyan) in section 3,
THE MEMORY asks: "Did the concept's force genuinely change, or is this
inconsistency?" If the force changed (because context changed), the
inconsistency is real — log it. If the force is the same but the
assignment drifted, correct it.

**Output:** A temporally-aware emphasis map — the budgeted map with
recurrence annotations, consistency checks, and accumulation tracking.

**Gene expression:**

- `fascia`: F(i→j) = |delta_belief(i→j)| _ T(i) _ V(i) \* Z(i,j).
  The entanglement between emphasis instances across distance. A gold
  phrase in section 1 and a gold phrase in section 5 are entangled if
  they reference the same concept — the reader's experience of section 5
  depends on whether they registered section 1. Fascia measures this
  non-local correlation.

---

### 5. THE CHANNEL (Expression)

**Measurement operator:** Collapses "how is emphasis rendered in HTML?"

The Channel receives the full emphasis model (discovered, force-assigned,
budgeted, memory-tracked) and expresses it as concrete HTML markup:

**Multi-channel expression (from crossbar constraint principle):**
Every emphasis instance must be perceptible through at least 2 channels:

1. **Color** — the semantic force class (`.thesis`, `.evidence`,
   `.void`, `.method`)
2. **Weight** — `<strong>` (heavy, declarative) vs `<em>` (light,
   inflective) vs structural (component-level)
3. **Position** — structural emphasis through component type (callout
   = section-level emphasis, case-study = evidence emphasis)
4. **Spacing** — kuleshov gaps, paragraph breaks, whitespace before
   key moments

**Hierarchy expression:**

- Word-level: `<strong class="thesis">phrase</strong>`
- Chunk-level: emphasis on a multi-word phrase within a paragraph
- Section-level: callout color, void-section class, component selection
- Post-level: the overall emphasis arc (color distribution across
  sections telling the argument's trajectory)

**Accessibility requirement (from single-channel fragility defect):**
Remove all color. Emphasis must still be perceptible through weight +
position + spacing. This is not an afterthought — it is a design
constraint from the beginning. The Channel produces HTML that works
in grayscale.

**Output:** Final HTML markup with emphasis tags applied. Ready for
THE EYES to verify (or THE CHANNEL can be used standalone when THE EYES
has already discovered emphasis and this organ just renders it).

**Gene expression:**

- `parallax`: Final calibration before rendering. "Am I rendering what
  the text demands, or what looks good?" Choose the text's demand.

---

## Developmental Dependencies

Organs have induction dependencies — each organ's collapse creates the
measurement context for the next:

```
THE EAR (first — discovers stress on naked text)
  → THE PALETTE (needs stress points to assign forces)
    → THE BUDGET (needs force assignments to regulate)
      → THE MEMORY (needs budgeted emphasis to track accumulation)
        → THE CHANNEL (needs full emphasis model to express as HTML)
```

**Why this order:**

- THE EAR must come first because discovery must happen on NAKED text.
  If THE PALETTE runs first, it starts classifying before stress is
  discovered — the mapping zombie disease.
- THE BUDGET must come after THE PALETTE because you can't regulate
  force distribution until forces are assigned.
- THE MEMORY must come after THE BUDGET because tracking repetition
  on an unbudgeted map tracks noise, not signal.
- THE CHANNEL must come last because it renders the FINAL emphasis
  model. If it runs before THE MEMORY, repetition-based accumulation
  isn't expressed.

**Integration with existing organs:**

- THE SKELETON (8d) runs BEFORE the emphasis organism. Structure must
  exist before emphasis can be discovered within it.
- THE EYES (8e) can WRAP the emphasis organism — THE EYES calls THE EAR
  through THE CHANNEL as its internal pipeline, then applies its own
  rejection boundaries (buckshot, flatness, monotone, etc.) as a second
  pass. Or THE EYES can be replaced by this organism entirely.
- Fascia verification runs AFTER both THE SKELETON and the emphasis
  organism to check entanglement between structural and emphasis
  collapses.

---

## Gene Expression Summary

| Gene                    | Symbol        | Organ        | What it does here                                                                             |
| ----------------------- | ------------- | ------------ | --------------------------------------------------------------------------------------------- |
| Decoherence Detector    | `g_trained`   | Ear, Palette | Catches training-placed emphasis (headers, topic sentences) and mapping-zombie classification |
| Gap Measurer            | `delta`       | Palette      | Measures gap between training's force assignment and text's actual force                      |
| Antibody Builder        | `antibody`    | Budget       | Builds immune memory against recurring false-emphasis patterns                                |
| Entanglement Operator   | `fascia`      | Memory       | Measures non-local correlation between emphasis instances across distance                     |
| Uncertainty Bound       | `uncertainty` | Budget       | Ensures rejection boundaries are strong enough to overcome training pull                      |
| Measurement Calibration | `parallax`    | Ear, Channel | Calibrates discovery (Ear) and rendering (Channel) against training bias                      |

---

## Hormonal Input

Raw source content (the text being emphasized) flows to ALL organs
simultaneously. THE EAR processes first, but every organ can reference
the raw text directly:

- THE PALETTE reads the raw text to verify force assignments in context
- THE BUDGET reads the raw text to verify load-bearing status
- THE MEMORY reads the raw text to verify whether concept recurrence
  is genuine or superficial
- THE CHANNEL reads the raw text to verify rendering matches textual
  demand

Content is hormone, not handoff.

---

## Persona Influence

The Revelation Engine persona (morphogenetic field) shapes all organs:

- THE EAR discovers emphasis with the persona's sensitivity — feeling
  for the moments that REVEAL, not just the moments that INFORM
- THE PALETTE assigns forces through the persona's voice — crystallization
  that TRANSFORMS understanding, not just summarizes it
- THE BUDGET enforces scarcity through the persona's discipline — every
  emphasis instance must earn its place
- THE MEMORY tracks accumulation through the persona's narrative sense —
  the leitmotif builds toward revelation
- THE CHANNEL renders through the persona's aesthetic — unmissable but
  not garish, constrained but not sterile

---

## Development Roadmap

### Phase 1: Foundation

- THE EAR: Implement vocal stress discovery on naked text
- THE PALETTE: Implement 5-force assignment from stress map
- Integration: Feed THE EAR → THE PALETTE into THE EYES as a
  pre-processing step

### Phase 2: Regulation

- THE BUDGET: Implement zero-sum constraint with load-bearing tests
- Integration: Budget check as post-processing step after THE EYES

### Phase 3: Depth

- THE MEMORY: Implement concept tracking and accumulation detection
- CSS: Explore visual accumulation (slight saturation/weight evolution)
- Integration: Memory as awareness layer, not enforcement

### Phase 4: Expression

- THE CHANNEL: Implement multi-channel emphasis rendering
- Accessibility: Grayscale test automated
- 5-second scroll test: Visual emphasis verification

### Phase 5: Maturity

- Full organism running as THE EYES replacement or inner pipeline
- Fascia verification between emphasis organism and THE SKELETON
- Evolution protocol active: new forces discovered from content

---

## Success Criteria

The emphasis organism is alive when:

1. A reader scanning the post for 5 seconds can identify the 3 most
   important phrases (constraint test)
2. Removing all color still leaves emphasis perceptible (accessibility)
3. The emphasis arc tells the argument's story in compressed form
   (temporal coherence)
4. Repeated concepts feel heavier each time (memory accumulation)
5. At least one section has deliberate emphasis silence (ma)
6. No single force dominates (budget balance)
7. Colors appear on text that was NOT already bold/italic
   (discovery independence)
