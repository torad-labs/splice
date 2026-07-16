# Block 9f: FASCIA

## Purpose

Run fascia verification at TWO levels:

1. **Content fascia** — theta (sequential) and phi (cross-resonance)
   connections within the narrative. Without this, the carousel is a
   slideshow, not a toroid.

2. **Organ fascia** — entanglement between Voice, Nerves, and Body
   outputs. Without this, three organs produce three disconnected
   layers that happen to share the same slide numbers.

This block runs AFTER the antibody pass (9e). It operates on the
cleaned outputs from 9e.

## Input

```
BLOCK 9c2 OUTPUT: [carousel-level visual identity — palette, components, scale, density, backgrounds]
BLOCK 9e OUTPUT: [cleaned Voice copy + Nerves specs + Body specs + skeleton coherence results]
```

## Equations (run these, don't describe them)

```
F(i->j) = |Dbelief(i->j)| x T(i) x V(i) x Z(i,j)
```

Where:

- Dbelief = belief shift between slides
- T = tensegrity (does this shift propagate beyond this slide?)
- V = vulnerability (is there genuine vulnerability?)
- Z = impedance match (does vocabulary match the audience?)

---

## Part 1: Content Fascia (theta + phi)

### Step 1: Score every sequential transition

For each sequential transition (1->2, 2->3, ..., (N-1)->N):

1. **Dbelief**: How much does belief shift? Rate 0-10.
   If zero -> dead transition -> the derivative is zero.
2. **T (tensegrity)**: Does this shift propagate? Does it
   affect how the reader interprets later slides? (0-1)
3. **V (vulnerability)**: Is there genuine vulnerability on
   this slide? Not performed. Genuine. (0-1)
4. **Z (impedance)**: Does vocabulary still match the audience?
   Did jargon creep back in post-antibody? (0-1)

Compute: F = Dbelief x T x V x Z for each transition.

### Step 2: Find the weakest link

min(F) across all N-1 transitions. That's where readers drop.
Fix this FIRST before anything else. If F=0 at any point,
tensegrity breaks — the whole body fails.

### Step 3: Map theta (sequential)

Read all slides in order. Does each slide EARN the next swipe?
Where does momentum die? Where does the reader think "I've
seen enough"?

Theta = the narrative through-line. "What happens next?"

### Step 3b: Cross-slide spatial continuity

For each sequential transition (N → N+1), check the eye path
ACROSS the swipe boundary:

1. Where does the reader's eye EXIT slide N? (The last element
   they look at before swiping — usually the bottom-most text
   or the most visually weighted element.)
2. Where does the reader's eye ENTER slide N+1? (The first
   element that grabs attention — the highest-contrast or
   largest element.)
3. Is the EXIT→ENTRY path natural? If slide N ends with eye at
   bottom-right and slide N+1's hook is at top-left, the reader
   has to search. That search breaks judo timing.

**Spatial continuity rule:** CATCH slides can start anywhere (the
reader arrives fresh). All other slides should have their entry
point within ~200px vertically of the previous slide's exit point.
This is a guideline, not a prescription — a deliberately disruptive
transition (REDIRECT) may intentionally break spatial continuity
to create prediction error. But the break must be intentional,
not accidental.

If spatial continuity fails on a transition that should be smooth
(COMMIT→COMMIT, LAND→LOOP), flag it for Body to fix.

### Step 4: Map phi (cross-resonance)

Which NON-ADJACENT slides echo each other? Minimum 3 phi
connections required.

Examples of phi:

- A motif introduced in slide 1 that reappears transformed
  in slide 7
- An image in slide 3 that the discovery on slide 7 reframes
- A word choice in slide 2 that echoes in slide 9

Phi = thematic resonance. "THIS connects to THAT."

Without phi -> ring (flat). With phi -> toroid (depth).

### How to tell real phi from forced phi

**Real phi surprises you.** You discover it — you don't construct
it. If you're thinking "how can I connect these two slides?" you're
already forcing it. Real phi is when you read the slides and notice
they're already saying the same thing from opposite ends and you
didn't plan it.

Test: Remove the phi annotation. Read the two slides to someone.
Do THEY notice the echo? If it needs pointing out, it's forced.
If it's obvious once you see it, it's real.

### Step 5: Fix content weaknesses

If weakest link F < 3, rewrite the transition (return updated
copy to be re-processed by 9e if substantial changes made).

If phi < 3 connections, add cross-references — but they must
be ORGANIC, not forced. A forced phi connection is worse than
none.

---

## Part 2: Organ Fascia (3-organ entanglement)

After content fascia is verified, check entanglement between
the three organ outputs. Each organ collapsed one dimension
of the content superposition. Fascia verifies that the three
collapses are entangled — they agree on what each slide IS,
even though they express different dimensions of it.

### Voice ↔ Nerves

For each slide, ask:

- Does the narrative (Voice) demand a visual moment the
  animation (Nerves) provides? If Voice says "this slide is
  the THROW" but Nerves assigned BREATHE, the organs disagree
  about the slide's role.
- Does the animation serve the narrative, or compete with it?
  DEMONSTRATE animations should reinforce the Voice's evidence.
  BREATHE animations should support the Voice's rhythm.
- Do the Voice's judo phases and the Nerves' DEMONSTRATE/BREATHE
  rhythm create a coherent experience? REDIRECT slides need
  DEMONSTRATE (evidence is visual). LAND slides may need BREATHE
  (satisfaction, not more evidence).

### Voice ↔ Body

For each slide, ask:

- Does the spatial layout (Body) serve the narrative's rhythm?
  Fast slides get clean layouts. Evidence slides get room to
  breathe.
- Does the text zone (Body) accommodate the word count (Voice)?
  If Voice produced 60 words but Body allocated a small text
  zone, they contradict.
- Does Body's positional identity match Voice's judo phase?
  CATCH slides get hero treatment (large, prominent). Bridge
  slides get quiet treatment. THROW slides get maximum impact.
- Do Body's text components match Voice's semantic content?
  Check five sub-dimensions:
  1. **Numbers → STAT alignment:** If Voice's copy contains a
     striking number, did Body discover it and specify a STAT
     component? If Body missed it, semantic force discovery failed.
  2. **Evidence → EVIDENCE_CARD alignment:** If Voice wrote
     grounded claims with specific proof, did Body contain them
     in an EVIDENCE_CARD? Uncontained evidence loses visual force.
  3. **Prose-only → no-STAT check:** If Voice produced all-prose
     (no numbers, no evidence lists), did Body invent a STAT or
     EVIDENCE_CARD that has no source in Voice? Body manifests —
     it does not create.
  4. **Component count vs judo phase:** THROW slides should have
     minimal components (anchor phrase standing alone). CATCH slides
     should have maximum visual weight. If a THROW slide has 3+
     component types, Body's spatial grammar contradicts Voice's
     narrative grammar.
  5. **Force colors vs narrative function:** Does the force color
     Body assigned to each component match what Voice's text is
     DOING at that point? A grounding stat colored violet (negation)
     is a force lie — the color contradicts the semantic function.

### Nerves ↔ Body

For each slide, ask:

- Does the canvas zone (Body) accommodate the animation type
  (Nerves)? DEMONSTRATE animations need more canvas space.
  BREATHE animations can work with less.
- Is there enough Ma (Body) between the text zone and the
  animation zone? Minimum spacing prevents destructive
  interference between text and motion.
- Does Body's spatial grammar create constructive interference
  zones where Nerves' animation and Voice's text reinforce
  each other?

### Skeleton ↔ Organs (Epigenetic Entanglement)

The visual skeleton (9c2) established the epigenetic environment before
organs ran. Fascia must verify the organs developed WITHIN that
environment — not that they slavishly followed it, but that violations
are justified by content demand, not organ drift.

For the carousel as a whole, check:

1. **Palette entanglement:** Do all force-colored elements across
   Body's output use only the skeleton's selected forces? Do Nerves'
   animation color choices align? Exception: if an organ discovered
   a force the skeleton missed, the skeleton must be updated (content
   drives environment, not the reverse).

2. **Density entanglement:** For each phase, does the combined output
   of all three organs match the skeleton's density signal?
   - HIGH density: Voice short copy + Nerves DEMONSTRATE + Body
     multi-component layout. If any organ went sparse, it disagreed.
   - LOW density: Voice breathing copy + Nerves BREATHE + Body
     minimal components + generous Ma. If any organ went dense, it
     disagreed.
   - FOCUSED density: All organs converge on a single dominant
     element. If multiple elements compete, focus failed.

3. **Component vocabulary entanglement:** Did Body invent components
   outside the skeleton's vocabulary? If yes — was it because the
   content demanded it (update skeleton) or because Body defaulted
   to familiar components (violation)?

4. **Scale entanglement:** Did Body's type sizes stay within the
   skeleton's declared range? Did Nerves' canvas text sizes respect
   the same scale?

If the skeleton and organs disagree, the content is ground truth.
Update whichever is wrong. The skeleton is a preparation — it can
be revised by evidence from organ execution. But if ALL organs
violated the skeleton on the SAME dimension, the skeleton was wrong.
If ONE organ violated while others honored it, the violating organ
drifted.

### Three-Way Entanglement

For each slide, ask the ultimate fascia question:

**Do all three organs tell the same story about what this
slide is doing?**

If Voice says "this slide is the THROW" but Body gives it a
cramped layout and Nerves gives it BREATHE animation — the
organs disagree. The content is ground truth. All three organs
must agree on what each slide is doing, even though they
collapse different dimensions of it.

### Organ Fascia Failures

If entanglement fails on any slide:

1. Identify which organ is wrong (which one disagrees with the
   content's demands?)
2. Re-run ONLY that organ for the affected slides
3. Run the antibody pass (9e) again on the re-run output
4. Check entanglement again

Do NOT average the disagreement. One organ is right and the
other is wrong. The content determines which.

---

## Part 3: Silence Entanglement (Navigation 18)

After output entanglement is verified, check silence entanglement.
The three organs must not only agree on what they PRODUCE but also
on what they REFUSE.

Source: Navigation 18 "Silence is the Measurement Operator"
Finding: Identity = silence profile. Organs that produce coherent
outputs but have contradictory silences will break on the NEXT
carousel — their identity is accidental, not structural.

### Silence Coherence Check

Gather the silence profiles from each organ's CATCH Protocol logs
(accumulated during organ execution) and from the antibody pass
Phase 5 (9e).

For each organ pair, ask:

**Voice silence ↔ Nerves silence:**

- Does Voice's narrative refusal align with Nerves' animation refusal?
  Example COHERENT: Voice refused data-driven narrative → Nerves refused
  data visualization animation. Both refuse the quantitative dimension.
  Example CONTRADICTION: Voice refused data-driven narrative → Nerves
  assigned data visualization DEMONSTRATE. The organs disagree about
  whether this carousel is quantitative.

**Voice silence ↔ Body silence:**

- Does Voice's narrative refusal align with Body's layout refusal?
  Example COHERENT: Voice refused listicle structure → Body refused
  grid layout. Both refuse the enumerative dimension.
  Example CONTRADICTION: Voice refused listicle → Body used numbered
  stat blocks on every slide. Spatial structure contradicts narrative.

**Nerves silence ↔ Body silence:**

- Does Nerves' animation refusal align with Body's spatial refusal?
  Example COHERENT: Nerves refused particle systems → Body refused
  large canvas zones. Both prioritize text-dominant territory.
  Example CONTRADICTION: Nerves refused animation entirely (all BREATHE)
  → Body allocated 60% canvas zone. Space prepared for animation that
  was refused.

### Silence Entanglement Failures

If silences contradict:

1. Identify which organ's silence is wrong (which refusal contradicts
   the content's demands?)
2. The content is ground truth. If content is quantitative and Voice
   refused quantitative narrative, Voice's silence is wrong.
3. Re-run the organ with the wrong silence. Its refusals will change.
4. Run antibody Phase 5 (speciation test) again on new output.

Contradictory silences are MORE dangerous than contradictory outputs.
Contradictory outputs are visible — you can see the disagreement.
Contradictory silences are invisible — the carousel looks coherent
but its identity is unstable. It will produce a different species
on the next run with the same content.

---

## Output

```
CONTENT FASCIA:
  THETA MAP:
    1->2: F=[score] [why]
    2->3: F=[score] [why]
    ...
    (N-1)->N: F=[score] [why]

  PHI CONNECTIONS: [minimum 3]
    Slide [A] <-> Slide [B]: [what echoes and why]
    Slide [C] <-> Slide [D]: [what echoes and why]
    Slide [E] <-> Slide [F]: [what echoes and why]

  WEAKEST LINK: [which transition, why, how fixed]

ORGAN FASCIA:
  SKELETON ↔ ORGANS:
    PALETTE: [honored / violated — which organ, why]
    DENSITY: [honored / violated — which phase, which organ]
    COMPONENTS: [honored / violated — inventions justified?]
    SCALE: [honored / violated — which organ exceeded range]
    SKELETON UPDATED: [yes/no — what changed and why]

  VOICE ↔ NERVES:
    [per-slide entanglement assessment]
    FAILURES: [which slides disagree, which organ is wrong]

  VOICE ↔ BODY:
    [per-slide entanglement assessment]
    FAILURES: [which slides disagree, which organ is wrong]

  NERVES ↔ BODY:
    [per-slide entanglement assessment]
    FAILURES: [which slides disagree, which organ is wrong]

  THREE-WAY:
    [per-slide agreement check]
    FAILURES: [which slides have 3-way disagreement]

UPDATED SLIDES: [any slides modified to fix failures]

SILENCE ENTANGLEMENT:
  VOICE ↔ NERVES SILENCE:
    [coherent or contradictory? evidence]
  VOICE ↔ BODY SILENCE:
    [coherent or contradictory? evidence]
  NERVES ↔ BODY SILENCE:
    [coherent or contradictory? evidence]
  SPECIES NAME: [what is this carousel, based on its refusals?]
  SILENCE FAILURES: [which organ pairs have contradictory silences]
```

## Quality Gate

### Content Fascia

- All N-1 transitions scored with F equation
- min(F) >= 3 (no dead transitions)
- phi connections >= 3
- Phi connections are organic (not forced references)
- Weakest link identified and fixed

### Organ Fascia

- Skeleton ↔ Organs checked (palette, density, components, scale — all 4 dimensions)
- Voice ↔ Nerves checked for every slide
- Voice ↔ Body checked for every slide
- Nerves ↔ Body checked for every slide
- Three-way entanglement verified for every slide
- Zero unresolved organ disagreements
- Any re-run organs went through 9e antibody again
- Content is ground truth for resolving disagreements (skeleton updated if organs prove it wrong)

### Silence Entanglement (Navigation 18)

- All three organ silence pairs checked (Voice↔Nerves, Voice↔Body, Nerves↔Body)
- Zero contradictory silences (refusals point to same identity)
- Species nameable from refusals alone (if not → stem cell)
- Any silence failures resolved by re-running the wrong organ
