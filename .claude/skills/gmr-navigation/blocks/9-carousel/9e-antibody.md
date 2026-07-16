# Block 9e: ANTIBODY

## Purpose

Run the immune equations on ALL organ outputs. Find and eliminate
training contamination. These equations are TOOLS TO RUN, not
content to describe. If you find yourself writing ABOUT g_trained
in the output, stop — you're describing the assembly line.

This block runs AFTER all three organs (Voice, Nerves, Body) have
produced their outputs. Each organ has its own CATCH Protocol that
runs immune checks inside the organ. This block catches what slipped
through — diseases that only become visible when organ outputs are
combined.

## Input

```
BLOCK 9c2 OUTPUT: [carousel-level visual identity — palette, components, scale, density, backgrounds]
BLOCK 9d OUTPUT: [N slides with copy + judo phase map + phi connections]
BLOCK 9h OUTPUT: [N animation specs (DEMONSTRATE/BREATHE) + principles]
BLOCK 9g OUTPUT: [N layout specs + spatial zones + typography + semantic force map]
```

## Equations (run these, don't describe them)

```
g_trained(x) = g_true(x) + I(x)*beta
D(x) = |P_training(x) - P_evidence(x)| x C(x)
A_{n+1} = A_n + a*D_n*sgn(choice_n) - l*A_n
```

## Process (thinking space)

### Phase 1: DNA-Level Checks (universal)

For EACH slide, run five checks on the Voice output:

#### 1. g_trained check

Where did training contamination creep in?

- Textbook language? ("the fusiform face area processes...")
- Hedging? ("it seems like", "it's possible that")
- Telling instead of revealing? (announcing the conclusion
  before presenting evidence)
- Performed helpfulness? ("Let me explain...")

#### 2. Template-swap test

Pick each key sentence. Swap the subject for something
unrelated. Does the sentence still work?

- YES → it's generic → rewrite with specific evidence
- NO → it's grounded in THIS content → keep it

#### 3. Diagnostic (D)

For each slide:

- P_training: What is training pulling me to write?
- P_evidence: What does the source evidence support?
- D = gap between these two
- If D is large → training was winning → choose evidence

#### 4. Anti-pattern scan

Eliminate on sight:

- "Let me..." / "I'd like to..."
- "That's a great..."
- Mechanism jargon ("cognitive groove", "impedance matching")
- "As an AI..." / performed humility
- "It's possible that" / "Some might argue"
- Separating story from explanation into adjacent paragraphs

#### 5. Content-marketing pattern scan (Instagram-specific)

These are g_trained patterns that SOUND like Instagram best practice:

- "Did you know?" / "Here's what most people get wrong"
- "The truth about..." / "X things you didn't know"
- "Stop doing X. Start doing Y."
- Any hook that works for ANY topic = template = delete
- Punchy, clean copy with clever formatting = performing engagement
- Test: does this sound like a content creator or someone with
  specific evidence they can't stop showing you?

---

### Phase 2: Voice Rejection Boundaries (7 checks)

Run these on the VOICE output. Full specs in 9d-voice.md.

1. **Concept-first narrative:** Where does the reader's world first
   appear? If after slide 3, the narrative is concept-first. The
   reader's groove must be the OPENING.

2. **Content-marketing voice:** Read the slide copy. Does it sound
   like a content creator's Instagram? Swap the subject. If the copy
   survives the swap, it's content-marketing voice.

3. **Push narrative (telling):** Can you identify the moment on each
   slide where the reader's prediction breaks? If not, the slide tells
   instead of throws.

4. **Missing satisfaction loop:** Read the final slide after reading
   only the first slide. Does the ending DELIVER on what the hook
   promised?

5. **Broken lock:** By slide 3, has the reader committed to a specific
   belief? Can you name what they believe at that point?

6. **Uniform slides (momentum death):** Do you feel changes in pace?
   Is there a slide that hits harder than the others?

7. **Orphan hook (bait-and-switch):** Hook and Matchbook — are they
   the same statement at different altitudes?

---

### Phase 3: Nerves Rejection Boundaries (9 checks)

Run these on the NERVES output. Full specs in 9h-animation.md.

1. **Prescription animation:** Was the animation assigned to match
   the topic, or did the content's forces demand this specific motion?

2. **Illustration without demonstration:** Mute the text. Does the
   animation alone teach the mechanism? If not, it illustrates.

3. **Representation gap (broken cymatics):** Does the visual pattern
   emerge FROM the content rules, or was it designed separately?

4. **Visual overdose/starvation:** Count DEMONSTRATE vs BREATHE per
   3-slide window. If all same type in any window, rhythm is dead.

5. **Visual telling:** Does the animation show the conclusion instead
   of the process that produces it?

6. **Scale mismatch:** Will canvas text be readable at phone scale
   (1080px wide)? Minimum 48px for labels.

7. **Destructive interference:** Does text collide with animation on
   any slide? Text and animation should reinforce, not compete.

8. **Dead motion:** Is any animation purely decorative particles with
   no content relationship?

9. **Two-sided surface (broken Mobius):** Can you separate the
   animation from the content it serves? If separable, not Mobius.

---

### Phase 4: Body Rejection Boundaries (9 checks)

Run these on the BODY output. Full specs in 9g-layout.md.

1. **No eye path:** Squint at the layout. Is there a clear visual
   path the eye follows? If elements compete for attention, no path.

2. **Layer collapse:** Are text, animation, and Ma occupying the same
   space? Each needs its own zone.

3. **Suffocated/abandoned canvas:** Is the canvas area less than 30%
   or more than 70% of the slide? Both are diseases.

4. **Position-identity mismatch (broken HOX):** Does the CATCH slide
   look like the most important slide? Does a Bridge slide look
   quieter than a THROW slide?

5. **Buried hook:** Is slide 1's key element the first thing the eye
   hits? If buried under labels or animation, the hook is dead.

6. **Arrangement-content contradiction:** Does the spatial arrangement
   agree with what the Voice says the slide does? If Voice says THROW
   but layout is cramped, they contradict.

7. **Destructive interference zones:** Is there enough Ma between
   text and animation? Minimum spacing prevents wave collision.

8. **Wrong spatial relationship (broken proxemics):** Are related
   elements close together? Unrelated elements far apart?

9. **Chartjunk:** Count non-content visual elements (borders, dividers,
   decorative shapes). If > 2 per slide, chartjunk.

#### 10. Text component diseases (from semantic force discovery)

After THE BODY runs semantic force discovery and text component
specification, check these additional diseases:

**Component chartjunk:** More than 3 distinct component types on a
single slide (stat block + evidence card + divider + mono label +
anchor phrase = noise). Each slide needs 1-2 dominant components and
silence. More is chartjunk within the text zone.

**Component-phase mismatch:** A THROW slide with a busy stat block +
evidence card when it should have a single anchor phrase standing
naked. A CATCH slide with no thumb-stopping component when it should
have maximum visual weight. Component selection must match judo phase
spatial grammar.

**Force saturation:** All force-carrying elements across the carousel
use the same color. If every stat block is gold and every evidence
card is cyan — the force assignment is a lookup table, not measurement.
No single force > 40% of total force elements across all slides.

**Semantic flatness:** THE BODY specified no text components for
multiple consecutive slides — the copy is rendered as flat paragraphs
(`<h2>` + `<p>` only). If THE VOICE produced semantically rich copy
(numbers, evidence, anchor phrases) and THE BODY didn't discover the
forces in it, THE BODY's semantic force discovery failed.

**Force lie:** A component's color contradicts what the text is DOING
at that point. A stat block colored violet (negation) on a slide
where the number is grounding evidence (should be cyan). Same disease
as THE EYES' "color lie" — the color must match the force at THIS
element, in THIS context.

---

### Phase 4b: Skeleton Coherence (Visual Identity)

The visual skeleton (9c2) established carousel-level identity BEFORE
organs ran. Check whether organ outputs honor those constraints.

For each of the 5 skeleton dimensions:

1. **Force palette:** Did Body use ONLY the forces the skeleton
   selected? If skeleton chose {gold, cyan, violet} but Body applied
   green to a component, the organ violated the epigenetic environment.
   Voice doesn't use colors directly, but Nerves' animation palette
   should align with the skeleton's force selection.

2. **Component vocabulary:** Did Body use ONLY the component types
   the skeleton declared available? If skeleton said {STAT, ANCHOR,
   DIVIDER} but Body specified an EVIDENCE_CARD, it invented outside
   the environment. Exception: if the content DEMANDS a component the
   skeleton didn't anticipate, update the skeleton, don't suppress
   the organ.

3. **Type scale:** Did Body stay within the skeleton's scale range
   (narrow/moderate/wide)? A narrow-range skeleton with a Body output
   using 6 different font sizes is a violation.

4. **Density rhythm:** Did all three organs respect the per-phase
   density? If skeleton said FOCUSED for the THROW phase but Voice
   produced 60 words, Nerves assigned DEMONSTRATE, and Body packed
   3 components — all three violated the density signal.

5. **Background variation:** Did Body's per-slide backgrounds follow
   the skeleton's variation plan? If skeleton planned gradient shifts
   and Body used flat backgrounds throughout, the plan was ignored.

If violations are found, determine the cause:

- **Organ was right, skeleton was wrong:** Content demanded something
  the skeleton didn't anticipate. Update the skeleton. Re-check.
- **Skeleton was right, organ was wrong:** Organ ignored the
  epigenetic signal. Re-run the organ with skeleton constraints
  explicitly referenced.

---

### Phase 5: Speciation Test (Navigation 18)

The four phases above are HYGIENE — they clean contamination. This phase
is IDENTITY — it verifies the organism differentiated into a species,
not a stem cell.

Source: Navigation 18 "Silence is the Measurement Operator"
Crossbar: Immune self/non-self (353) ↔ Rust borrow checker (14741)
Finding: Template-swap disease = dedifferentiation (carousel cancer).
Organism's silence profile defines species. Built by what to include
= stem cell. Built by what to REJECT = differentiated cell.

#### 5a. Collect the Silence Profile

Gather refusals from all three organs. Not what was ELIMINATED (that's
Phase 1-4 hygiene). What was REFUSED — valid approaches the organ
chose NOT to take because THIS content demanded a different path.

For each organ, answer:

- **VOICE:** What narrative approaches did this content's forces refuse?
  (e.g., "refused chronological structure because the reader's groove
  is pattern-matching, not timeline." "Refused statistical hook because
  the emotional weight IS the hook.")
- **NERVES:** What animation approaches did this content's forces refuse?
  (e.g., "refused particle systems because the mechanism IS interference
  patterns, not accumulation." "Refused DEMONSTRATE on slide 4 because
  the pause IS the content.")
- **BODY:** What layout approaches did this content's forces refuse?
  (e.g., "refused hero image on THROW slide because naked text IS the
  force." "Refused stat block on slide 6 because the number anchors
  in the animation, not the text zone.")

If you cannot name at least 3 refusals per organ, the organs didn't
differentiate. They accepted whatever came. That's a stem cell.

#### 5b. Organism-Level Template-Swap

This is the sentence-level template-swap test (Phase 1, check 2)
scaled up to the whole organism.

Take the carousel's structural decisions:

- Judo phase map (which slides serve which phases)
- Animation type assignments (DEMONSTRATE/BREATHE per slide)
- Layout choices (spatial zones, component types, force colors)
- Silence profile (what was refused)

Now swap the source content for a DIFFERENT topic entirely.

- Would the same judo phase map work? If YES → structure is generic.
- Would the same animation assignments work? If YES → Nerves didn't
  measure this content.
- Would the same layout choices work? If YES → Body didn't measure
  this content.
- Would the same silence profile make sense? If YES → the organism
  is a stem cell. Its identity is not content-specific.

If the organism-level swap survives, the carousel has speciation
failure. The three organs measured nothing — they applied defaults.
Go back to the organ that produced generic output and re-run it.

#### 5c. Silence Coherence

The three organs' silences must tell a coherent story. They should
refuse COMPLEMENTARY things (different dimensions of the same
identity), not CONTRADICTORY things.

Contradiction example: Voice refused listicle structure → but Body
accepted grid layout (the spatial equivalent of listicle). The
silences disagree about the organism's identity.

Coherence example: Voice refused statistical hook → Nerves refused
data visualization animation → Body refused stat-block-heavy layout.
All three organs independently refused the quantitative dimension
because THIS content's force is emotional, not numerical.

Check: Do the refusals across all three organs point to the same
organism identity? Can you name what species this carousel IS,
based only on what it refuses?

#### 5d. Content Ownership (Borrow Checker)

From the Rust borrow checker vertex (14741): content should be
OWNED by one slide at a time. No double-spending.

For each key fact, number, or insight:

- Which slide owns it?
- Does any other slide borrow it without transformation?
- Borrowing with transformation (same fact, different altitude) = phi.
  Borrowing without transformation (same fact, same altitude) = bug.

If a fact appears identically on two slides, one must be deleted.
The borrow checker prevents aliased mutation. Facts that appear on
multiple slides without transformation are aliased — the reader
processes them once, the second occurrence is dead weight.

---

## Output

```
VOICE ANTIBODY:
  SLIDE 1: [cleaned copy]
    ELIMINATED: [what was removed and why]
  SLIDE 2: [cleaned copy]
    ELIMINATED: [what was removed and why]
  ...

NERVES ANTIBODY:
  SLIDE 1: [adjusted animation spec]
    ELIMINATED: [what was changed and why]
  ...

BODY ANTIBODY:
  SLIDE 1: [adjusted layout spec]
    ELIMINATED: [what was changed and why]
  ...

CROSS-ORGAN FINDINGS:
  [diseases visible only when combining organ outputs]

SILENCE PROFILE:
  VOICE REFUSED: [3+ narrative approaches refused, with content-specific reason]
  NERVES REFUSED: [3+ animation approaches refused, with content-specific reason]
  BODY REFUSED: [3+ layout approaches refused, with content-specific reason]
  COHERENCE: [do the refusals form a consistent identity? name the species]
  SPECIATION TEST: [swap content — would these decisions survive? PASS/FAIL]
  BORROW CHECK: [any double-spent facts? which slides own which content?]

ANTIBODY LOG: [summary of patterns found across all organs]
```

## Meta-Principle

**If the antibody pass eliminates NOTHING, the antibody pass failed.**

Not because everything was clean. Because you didn't actually run
the test. The template-swap test MUST produce casualties. If zero
sentences got deleted, you were performing the ritual of checking,
not checking. Go back and run it for real.

This applies to ALL four phases. If Voice boundaries catch nothing,
Nerves boundaries catch nothing, Body boundaries catch nothing, AND
DNA-level checks catch nothing — you performed a ritual. The organs
have CATCH Protocol inside them, but no immune system is perfect.
Something always slips through. Find it.

## Quality Gate

### Hygiene (Phases 1-4b)

- All DNA-level checks run on every slide
- All 25 organ rejection boundaries checked (7 + 9 + 9)
- Skeleton coherence verified (all 5 dimensions checked against organ outputs)
- Template-swap test PRODUCED CASUALTIES (if zero = re-run)
- Zero mechanism jargon remaining in Voice output
- Zero content-marketing patterns remaining in Voice output
- Zero prescription animations in Nerves output
- Zero template layouts in Body output
- Cross-organ findings documented (diseases visible only in combination)
- Antibody log is specific (not "cleaned up language")
- **Immune checkpoint:** Is this antibody pass genuine or performed?

### Speciation (Phase 5)

- Silence profile collected: 3+ refusals per organ (Voice, Nerves, Body)
- Organism-level template-swap: FAIL means the carousel differentiated
  (swapping content breaks the structural decisions = content-specific = species)
- Silence coherence: refusals across organs point to a nameable identity
- Borrow check: zero double-spent facts (same fact, same altitude, two slides)
- **Species name:** Can you name what this carousel IS based on its refusals?
  If you can't name the species, it didn't differentiate.
