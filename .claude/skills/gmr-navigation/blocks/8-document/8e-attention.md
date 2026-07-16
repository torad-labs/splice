# Block 8e: THE EYES (v2)

**Semantic Attention Organ — Measurement Operator**

Source: GMR Navigation 14, "How voice survives ink" (brain navigation_id=14).
Crossbar: Motherese (353) ↔ Tajweed (14741).
Void: Emphasis has a budget.

**Run this entire phase in thinking space.**

**Every step below runs in thinking space. Do not exit thinking
space until the full organ has completed and produced its output.**

### CATCH Protocol

At marked `>>> CATCH <<<` points, STOP and reflect in thinking space:

1. Read back what you just produced
2. Ask: "Am I measuring or mapping?"
   - Measuring = feeling what the argument is doing, then naming
     the force
   - Mapping = classifying the phrase into a category, then
     looking up the color
   - If mapping: delete the last decision, re-read the sentence
     in context, feel the force, decide again
3. Ask: "Did the argument demand this emphasis, or did I?"
   - Argument demand = removing emphasis weakens the visible
     structure
   - My demand = "this phrase looks like it should be bold"
   - If mine: the organ is sick. Return to the text.
4. Ask: "What is training pulling me toward right now?"
   - Name the pull. Feel its flavor (buckshot? decoration?
     template emphasis?)
   - Choose against it if evidence disagrees.

The CATCH is not optional. It is the immune system's checkpoint.
Skipping it is how disease enters undetected.

---

## Prerequisites

Phase 4 (8d-structure / THE SKELETON) outputs must exist:

- Structural collapse (what each content block became)
- Jargon table (terms needing tooltip treatment)

Phase 3 (8c-audit) outputs must exist:

- Audited text (final prose)

---

## 1. Identity

This organ collapses the superposition of "what text gets visual
attention?"

Before THE EYES runs, the audited text is a wall of gray. Every word
has equal visual weight. The text is in superposition: any word COULD
receive emphasis, any phrase COULD carry color. THE EYES is the
measurement that collapses this. It reads the argument, feels where
the weight falls, and assigns visual attention to the phrases that
carry the argument's load.

**Governing Insight (from crossbar: Motherese ↔ Tajweed):**
Voice survives ink not through encoding strength but through decoder
constraint. Tags constrain reading — they do not suggest it. Every
sub-organ in this system exists to make emphasis UNMISSABLE, not
decorative. Motherese overwhelms (300% prosodic amplification).
Tajweed constrains (rules too precise to deviate). Both destroy
decoder freedom. Our emphasis tags do the same.

**The five colors are not categories. They are forces:**

- **Gold** — the force of crystallization. When scattered evidence
  collapses into a single sentence that holds. The "oh" moment. The
  argument arriving at a point it earned. You feel this force when
  the text suddenly gets dense, when ten paragraphs compress into
  one line.

- **Cyan** — the force of grounding. When a claim touches earth.
  Data, names, numbers, sources. The floor under the argument. You
  feel this force when the text stops floating and lands on something
  verifiable.

- **Violet** — the force of negation. When the text says what ISN'T,
  what FAILED, what was WRONG. The argument pushing AWAY from
  something. You feel this force when the text disrupts, when it
  breaks a pattern the reader was following.

- **Green** — the force of resolution. When the argument lands on
  something the reader can DO. Potential energy becoming kinetic.
  You feel this force when the text shifts from understanding to
  action, from diagnosis to treatment.

- **Bright white** — the force of ordinary emphasis. Weight without
  specific directional force. The argument leaning into a word
  because the word matters, not because it crystallizes, grounds,
  negates, or resolves.

The colors describe what the text is DOING at that moment, not what
the text IS. The same word in two different contexts can carry two
different forces. "Rejection" as a thesis insight is gold. "Rejection"
as evidence of a count is cyan. "Rejection" as what the system fails
to do is violet. Context determines force. The word does not carry
an intrinsic color.

**The immune system runs in the blood.** At every emphasis decision,
run the parallax:

```
P_training: "This phrase should be bold because it looks important"
            (template pull, buckshot instinct)
P_evidence: "Does this phrase carry the argument's weight? If I
             remove emphasis here, does the argument's visible
             structure weaken?" (load-bearing test)
delta: |P_training - P_evidence|
Choose: Go with the evidence.
```

---

## 2. Internal Pipeline (5 Sub-Organs)

THE EYES is not a single pass. It contains five sub-organs that
execute in strict developmental order. Each sub-organ's collapse
creates the measurement context for the next.

```
THE EAR (discovers stress on naked text)
  → THE PALETTE (assigns force types to stress points)
    → THE BUDGET (enforces zero-sum scarcity)
      → THE MEMORY (tracks accumulation across distance)
        → THE CHANNEL (expresses as multi-channel HTML)
```

### 2a. THE EAR (Discovery)

**Measurement operator:** Collapses "which text carries natural
emphasis?"

The Ear reads raw text with all formatting stripped — no bold, no
italic, no headers, no components. Naked text. It reads the text
in thinking space as if reading aloud, feeling where the inner voice
naturally changes CHARACTER (not just volume):

- Where does the voice slow down? (potential crystallization)
- Where does the voice drop and become precise? (potential grounding)
- Where does the voice get quiet and deliberate? (potential negation)
- Where does the voice lift and open outward? (potential resolution)
- Where does the voice simply lean into a word? (potential stress)

**The vocal stress model:** Speech-to-text systems detect stress
through pitch change, duration change, and volume change. THE EAR
does the same thing mentally. It does NOT look at what's already
bold or italic — that's the discovery blindness defect. It feels
stress on NAKED text.

**Output:** An emphasis discovery map — phrases where the inner voice
changed character, annotated with WHICH change occurred (slowdown,
drop, quiet, lift, lean). No force assignment yet. Just stress
detection.

**Gene expression:**

- `g_trained`: Detect if emphasis is being placed where training
  says it should go (headers, topic sentences, conclusions) vs
  where the text actually pushes
- `parallax`: Before reading, calibrate: "What would any AI
  emphasize? Those are the training pulls. Now feel the ACTUAL
  stress."

`>>> CATCH <<<` After THE EAR runs, check: did you discover
emphasis on naked text, or did you remember where the formatted
version had bold/italic and rediscover those? If the emphasis map
suspiciously matches the existing formatting, THE EAR failed. Start
over with genuinely stripped text.

---

### 2b. THE PALETTE (Force Assignment)

**Measurement operator:** Collapses "what type of emphasis does each
stress point carry?"

The Palette receives the emphasis discovery map from THE EAR and
assigns force types based on what the text is DOING at that moment:

- Slowdown → **crystallization** (gold)
- Drop to precision → **grounding** (cyan)
- Quiet deliberation → **negation** (violet)
- Lift outward → **resolution** (green)
- Simple lean → **stress** (white)

**The force is in the text, not in the Palette.** The same word in
two different contexts carries two different forces. Context
determines force.

**Output:** A force-annotated emphasis map — each phrase with its
force type and the textual evidence for that assignment.

**Gene expression:**

- `delta`: For each force assignment, measure:
  |P_training - P_evidence|. If training pulls toward gold ("this
  sounds like a thesis") but the text is actually grounding (citing
  a specific case), the delta is high. Go with the evidence.
- `g_trained`: The mapping zombie check — am I CLASSIFYING phrases
  into categories ("thesis → gold") or FEELING what the text does?

---

### 2c. THE BUDGET (Regulation)

**Measurement operator:** Collapses "is the emphasis system in
balance?"

The Budget receives the force-annotated map and enforces the
zero-sum constraint. Emphasis is a finite resource. The budget
exists because:

- Motherese works on infants because the infant's emphasis channel
  is EMPTY — 300% amplification fills an empty space
- Web readers' channels are NOT empty — they're saturated with
  competing emphasis from ads, notifications, social feeds
- The only way to be heard in a saturated channel is SCARCITY —
  each emphasis instance must be rare enough to stop the eye

**Budget rules (ceilings, not targets):**

- No more than 30% of sentences contain emphasis
- No single force > 40% of total emphasis instances
- Emphasis density tracks argument density (evidence-heavy sections
  get more; narrative sections get less)
- At least one section with deliberate emphasis silence

**The Budget does NOT remove emphasis by quota.** It checks whether
each instance is load-bearing (would the argument's visible structure
weaken without it?) and whether the total system respects the
scarcity constraint. If a post has 8 emphasis instances that are
all load-bearing and budget-compliant, it passes. If a post has 25,
some must be non-load-bearing — find and remove them.

**Output:** A budgeted emphasis map — the force-annotated map after
scarcity enforcement, with a log of which instances were removed
and why.

**Gene expression:**

- `uncertainty`: delta_T \* delta_C >= I(x). The budget boundaries
  must be strict enough to overcome training's pull toward
  "emphasize more." If the budget feels too loose, the constraint
  is weaker than the training inertia.
- `antibody`: Each time THE BUDGET catches a non-load-bearing
  emphasis, the immune memory strengthens. Pattern: training
  pulls → "this phrase sounds important" → Budget catches it →
  antibody builds against that specific pattern.

---

### 2d. THE MEMORY (Accumulation)

**Measurement operator:** Collapses "how does emphasis evolve across
the post?"

The Memory receives the budgeted emphasis map and adds the temporal
dimension. It tracks concepts across sections:

- A concept tagged with the same force in section 1 and section 4
  is accumulating emphasis — like a leitmotif
- Receptor density: the reader's sensitivity to that concept's
  force INCREASES with each encounter
- First occurrence = establishing the motif
- Second occurrence = recognition ("I've seen this force before")
- Third+ occurrence = full resonance ("this is the thread")

**Memory does NOT force repetition.** It detects natural recurrence
and ensures consistent force assignment. If "rejection" appears as
crystallization (gold) in section 1 but grounding (cyan) in
section 3, THE MEMORY asks: "Did the concept's force genuinely
change, or is this inconsistency?" If the force changed (because
context changed), log it. If the assignment drifted, correct it.

**Output:** A temporally-aware emphasis map — the budgeted map with
recurrence annotations, consistency checks, and accumulation
tracking.

**Gene expression:**

- `fascia`: F(i→j) = |delta_belief(i→j)| _ T(i) _ V(i) \* Z(i,j).
  The entanglement between emphasis instances across distance. A
  gold phrase in section 1 and a gold phrase in section 5 are
  entangled if they reference the same concept — the reader's
  experience of section 5 depends on whether they registered
  section 1.

---

### 2e. THE CHANNEL (Expression)

**Measurement operator:** Collapses "how is emphasis rendered in
HTML?"

The Channel receives the full emphasis model (discovered,
force-assigned, budgeted, memory-tracked) and expresses it as
concrete HTML markup.

**Multi-channel expression (from crossbar constraint principle):**
Every emphasis instance must be perceptible through at least 2
channels:

1. **Color** — the semantic force class (.thesis, .evidence,
   .void, .method)
2. **Weight** — `<strong>` (heavy) vs `<em>` (light) vs structural
3. **Position** — structural emphasis through component type
   (callout = section-level, case-study = evidence emphasis)
4. **Spacing** — kuleshov gaps, paragraph breaks, whitespace before
   key moments

**Accessibility requirement:** Remove all color. Emphasis must still
be perceptible through weight + position + spacing. This is not an
afterthought — it is a design constraint from the beginning.

**Output:** Final HTML markup decisions for each emphasis instance.
Ready for integration into the emphasis map output.

**Gene expression:**

- `parallax`: Final calibration before rendering. "Am I rendering
  what the text demands, or what looks good?" Choose the text's
  demand.

---

## 3. Rejection Boundaries

Twelve boundaries, reconciled from THE EYES v1 (7) and the emphasis
organism rejection spec (9). Duplicates merged, the more specific
version kept.

### DEFECT: Color-as-decoration

**Evidence:** Colors applied only to already-bold/italic text.
Colors piggyback on existing typographic emphasis instead of creating
new emphasis. The color adds nothing the bold didn't already say.

**Test:** If >50% of colored text sits on elements that were already
bold or italic for structural reasons, disease detected. Colors must
ADD emphasis to text that would otherwise be gray.

---

### DEFECT: Thesis saturation

**Evidence:** One force dominates the emphasis landscape. When
everything crystallizes, nothing does. The zero-sum emphasis budget
is spent entirely on one force. The reader's subvocalization cannot
sustain the same force indefinitely without fatigue.

**Test:** No single force exceeds 40% of total emphasis instances.

---

### DEFECT: Buckshot

**Evidence:** Emphasis appears every other sentence. The reader's
eye learns to ignore it. The visual system carries noise instead
of signal.

**Test:** No more than 30% of sentences contain emphasis. Emphasis
should be sparse enough that each instance STOPS the eye.

---

### DEFECT: Flatness

**Evidence:** A section with zero emphasis is a wall of gray. The
reader's eye has no entry points, no handholds. The argument's
forces are invisible.

**Test:** Most sections must have emphasis. A section with zero
emphasis is either deliberate silence (passes if the section is
intentionally sparse — see silence starvation below) or flatness
(fails).

---

### DEFECT: Discovery blindness

**Evidence:** THE EYES assigns emphasis to text that is ALREADY
visually prominent (bold, italic, headers) because that's where
training's attention lands. The text that NEEDS emphasis — the
quiet phrase carrying the argument's weight — gets skipped because
it doesn't look important yet. Generic intensifiers ("crucial,"
"fundamental," "critical") get bolded not because the argument needs
them but because they signal importance to training.

**Test:** Read the text with all formatting stripped. Where does the
argument's weight fall? If the emphasis map matches the existing
bold/italic map, the system found formatting, not emphasis. Emphasis
discovery must happen on NAKED text (THE EAR).

---

### DEFECT: Color lie

**Evidence:** The semantic color contradicts the text's actual force.
Marking a negation as gold when the text is pushing AWAY. Marking an
insight as cyan when nothing is being grounded.

**Test:** For each colored emphasis, ask: "Is the text crystallizing
(gold), grounding (cyan), negating (violet), or resolving (green)
RIGHT HERE?" Not in the section generally. At THIS phrase, in THIS
sentence. If the color does not match the force at this exact point,
the color is lying.

---

### DEFECT: Contrastive blindness

**Evidence:** The text presents two opposing forces, two poles of an
argument, and both receive the same color or only one receives
emphasis. The CONTRAST is the point. Making both poles the same
color makes the contrast invisible.

**Test:** Every contrastive pair (X vs Y, before vs after,
conventional vs discovered) — both poles must have emphasis with
DIFFERENT colors expressing their different forces.

---

### DEFECT: Binary collapse

**Evidence:** HTML offers two emphasis states (bold, italic). If
implementation collapses to just "colored bold"
(`<strong class="thesis">`), the richness is wasted. The system
reverted to binary-with-paint.

**Test:** Do emphasis instances vary in weight, hierarchy level, and
structural position? If every emphasis is `<strong class="[color]">`,
the system collapsed. Some should be `<em>` (lighter touch). Some
should be structural (component-level emphasis through callout/
case-study coloring). Some should be absence (kuleshov gap before a
key phrase).

---

### DEFECT: Amnesia

**Evidence:** Repeated concepts treated independently with no
temporal accumulation. The reader's experience of a concept doesn't
BUILD across sections.

**Test:** Track concepts that appear across multiple sections. Does
the reader's experience of the concept accumulate? If swapping the
section order doesn't change the emphasis experience, the system has
amnesia. Fix: consistent force assignment to recurring concepts.

---

### DEFECT: Silence starvation

**Evidence:** Emphasis in every section, no contrast, no deliberate
absence. The pause between notes makes the next note louder.
Emphasis requires silence to push against. Emphasis everywhere IS
emphasis nowhere.

**Test:** Every post must have at least one section or stretch where
emphasis is deliberately sparse or absent. If every section has 5+
emphasis instances, the post is silence-starved. Silence is not
flatness — silence is intentional emptiness that makes the filled
moments heavier.

---

### DEFECT: Single-channel fragility

**Evidence:** A system that relies on ONE channel (color alone)
breaks for any reader whose channel is impaired. Different readers
decode emphasis differently.

**Test:** Remove all color from the post. Is emphasis still
perceptible through font weight + structural position + spacing?
If removing color makes emphasis invisible, the system is
single-channel.

---

### DEFECT: Suggestion (weak constraint)

**Evidence:** Current CSS emphasis (a subtle color tint on text) is
a polite suggestion the distracted web reader ignores. The crossbar
says emphasis must CONSTRAIN reading, not suggest it.

**Test:** Show the post to someone for 5 seconds (scroll scan). Can
they identify the 3 most emphasized phrases? If not, the emphasis is
suggesting, not constraining. The emphasis must be unmissable at
scroll speed. Not garish — unmissable.

---

`>>> CATCH <<<` After reading all 12 rejection boundaries, pause.
You just loaded 12 defect patterns. The danger: you start LOOKING
for defects to avoid instead of FEELING where the argument pushes.
The boundaries are a diagnostic you run AFTER emphasis assignment,
not a filter you apply DURING. Discover emphasis first (THE EAR).
Budget and check second (THE BUDGET + boundaries).

---

## 4. Prepared Environment

The emphasis tools on the shelf. Content picks up what it needs.

**Force tags (5 forces):**

- `.thesis` (gold, `--gold`) — crystallization. Inner voice slows,
  declares.
- `.evidence` (cyan, `--cyan`) — grounding. Inner voice drops,
  precise.
- `.void` (violet, `--violet`) — negation. Inner voice quiets,
  deliberate.
- `.method` (green, `--green`) — resolution. Inner voice lifts,
  opens.
- (default, bright white) — stress. Ordinary emphasis without
  semantic force.

**Carrier elements:**

- `<strong>` — heavy emphasis (declarative)
- `<em>` — light emphasis (inflective)
- `<em class="term">` — first introduction of cross-domain term
- `<span class="jargon-tooltip" data-tooltip="...">` wrapping
  `em.term`

**Budget mechanism:**

- Max 30% of sentences emphasized
- No single force > 40% of instances
- Density tracks argument density
- At least one section with deliberate silence

**Memory mechanism:**

- Consistent force assignment to recurring concepts
- CSS can express accumulation: slight weight/saturation increase
  on repetition (future implementation)

**Silence mechanism:**

- `.kuleshov-gap` — intentional empty space
- Sparse-emphasis sections — deliberate silence
- Silence before a key moment = emphasis multiplier

**Hierarchy mechanism:**

- Word-level: inline `<strong>` / `<em>` with force class
- Chunk-level: phrase emphasis within a paragraph
- Section-level: callout color, section mood via THE SKELETON

**Constraint mechanism:**

- Multi-channel: color + font weight + structural position
- Must be perceptible without color (accessibility)
- Must register at scroll speed (the 5-second test)

**Component-level attention (from THE SKELETON):**

- `.callout` color variants (gold/violet/cyan/red) — the callout's
  color is itself an attention decision. THE EYES assigns the color
  based on the crystallized content's force.
- Case study emphasis — strong/em within case study blocks follow
  the same force rules as prose emphasis.

These are tools. The argument determines which get used. No tool has
a minimum usage quota.

---

## 5. Evolution Protocol

New forces join when content exerts emphasis that the 5 existing
forces cannot express:

1. The text is DOING something no force captures
2. Name the force (what the inner voice does)
3. Map to a CSS color or introduce a new one
4. Add to the shelf
5. Must pass budget constraint — adding a force doesn't expand
   the budget

New defects join when emphasis fails in a previously unseen way:

- Add DEFECT + EVIDENCE + TEST
- Delete boundaries that no longer catch real diseases

Force names may evolve. The force is the invariant. The name is
the handle.

---

## 6. Void

THE EYES does NOT:

- Prescribe which specific words get emphasis (content determines)
- Define minimum counts per color (balance emerges from argument)
- Assign emphasis during writing (THE EYES runs AFTER text exists)
- Touch structure (section boundaries, component types, tempo are
  THE SKELETON's territory)
- Choose callout placement (THE SKELETON places; THE EYES colors)
- Decide which text gets emphasized by classification (THE EAR
  discovers on naked text; THE PALETTE assigns by feeling force)
- Set targets or quotas (THE BUDGET enforces ceilings)

THE EYES does NOT know about THE SKELETON's internal decisions. The
two organs collapse independent dimensions. Their outputs are
verified for entanglement AFTER both run (see Fascia Verification).

---

## 7. Meta-Rejection

**The spec-as-lookup-table disease:** If this spec starts listing
specific phrases that "should be" tagged with specific forces, the
spec is infected. Specific phrase-to-force mappings fossilize one
post's output into every future post's constraint.

**The mapping zombie:** If the organ stops feeling forces and starts
classifying text ("this is a thesis statement, therefore gold"),
the organ has reverted from measurement to mapping. Mapping is the
taxonomic corpse of feeling.

**The quota zombie:** If the organ starts counting ("I need more
violet, the balance is off") instead of feeling ("the argument is
negating something here, the force is violet"), the organ has
replaced measurement with arithmetic.

**The budget-as-quota disease:** If the budget numbers become
targets ("I need exactly 12 emphasis instances"), the spec has
killed the organism and replaced it with arithmetic. The budget is
a CEILING, not a TARGET.

**The memory-as-rule disease:** If the memory mechanism becomes
"tag every recurring concept exactly 3 times," organic accumulation
has been replaced by mechanical repetition.

**The constraint-as-aggression disease:** The crossbar says
"constrain, don't suggest." This does NOT mean "make everything
bold and neon." Constraint is precision, not volume. Tajweed is
precise, not loud.

**Test:** Swap the topic. Does this spec work without modification?
If yes: healthy. If topic-specific: infected.

---

`>>> CATCH <<<` Before grading, re-read the emphasis map you
produced. For each emphasis instance, ask one more time:
"If I remove this emphasis, does the argument's visible structure
weaken?" If the answer is no for any instance, remove it now.
Then ask: "Did I assign this color because the text is DOING
this force here, or because I classified the phrase into a
category?" If classified: re-feel the force, reassign. This is
the last checkpoint before the output crystallizes.

---

## 8. Quality Grade

- **A (Alive):** Emphasis is sparse, multi-force, budget-aware. Each
  instance stops the reader's eye. Colors track the argument's
  shifting forces across sections. Silence exists. Repeated concepts
  accumulate force. Multi-channel (perceptible without color). The
  5-second scroll test passes. Emphasis was discovered on naked text,
  not painted on existing formatting. The color arc IS the argument
  arc.

- **B (Breathing):** Emphasis is correct per-instance but doesn't
  build across the post. No amnesia, no thesis saturation, but also
  no arc. Each emphasis is right; the collection doesn't tell a
  story.

- **C (Copying):** Emphasis follows a template ("every section starts
  with gold thesis, has cyan evidence in the middle, ends with green
  resolution"). The discovery was skipped. Colors were assigned by
  position, not by force.

- **D (Dead):** Any combination of: color-as-decoration, buckshot,
  thesis saturation, discovery blindness, color lies, binary
  collapse, amnesia, silence starvation, single-channel, suggestion.
  The measurement failed. The text is still a wall of gray or
  collapsed to noise.

---

## Gene Expression Summary

| Gene                    | Symbol        | Sub-Organ    | What it does here                                                         |
| ----------------------- | ------------- | ------------ | ------------------------------------------------------------------------- |
| Decoherence Detector    | `g_trained`   | Ear, Palette | Catches training-placed emphasis and mapping-zombie classification        |
| Gap Measurer            | `delta`       | Palette      | Measures gap between training's force assignment and text's actual force  |
| Antibody Builder        | `antibody`    | Budget       | Builds immune memory against recurring false-emphasis patterns            |
| Entanglement Operator   | `fascia`      | Memory       | Measures non-local correlation between emphasis instances across distance |
| Uncertainty Bound       | `uncertainty` | Budget       | Ensures rejection boundaries are strong enough to overcome training pull  |
| Measurement Calibration | `parallax`    | Ear, Channel | Calibrates discovery and rendering against training bias                  |

---

## Callout Content Crystallization

After emphasis assignment, for each callout (from THE SKELETON):

- Write crystallized content. Not a copy of prose. A distillation
  into 1-3 sentences that capture the insight at its densest.
- Assign callout color based on the crystallized content's force:
  gold (insight), violet (void), cyan (technical), red (warning).
- The callout color comes from the CONTENT'S force, not from the
  section's position or the post's color balance.

---

## Jargon Tooltip Content

For each term in the jargon table from THE SKELETON:

- Write a plain-language definition (1-2 sentences)
- Note the first-use location (where the tooltip wraps)
- Terms from OTHER domains get tooltips. Terms the audience
  already knows do not.

---

## Output

Four artifacts:

1. **EMPHASIS MAP** — per-section phrase-level emphasis assignments.
   Each entry: phrase, element (strong/em), class (thesis/evidence/
   void/method/term/default), and the FORCE that justified it (not
   the category it belongs to, but what the text is DOING at that
   moment)

2. **COLOR ARC** — one-line-per-section summary of the dominant
   forces. Does the arc track the argument? If scanning the arc
   tells the argument's story in compressed form, the organ is alive.

3. **CALLOUT CONTENT** — crystallized text + color for each callout

4. **TOOLTIP CONTENT** — plain-language definitions + locations

All become inputs to Phase 6 (8f-design) and Phase 7 (8g-build).

---

## Fascia Connections

This organ is entangled with:

- **THE SKELETON (8d)** — runs BEFORE this organ. Structure must
  exist before emphasis can be discovered within it. Fascia check:
  callout colors (THE EYES assigns) match callout content (THE
  SKELETON placed). Jargon terms (THE SKELETON identified) match
  tooltip definitions (THE EYES wrote).
- **GMR Block 8 README** — this organ is Phase 5 in the pipeline.
  The README references this file.
- **Organism DNA 8d-eyes** — for general concept expression (not
  blog-specific), the organism pipeline's eyes organ references
  the same force model and rejection boundaries.
- **blog-base.css** — the 5 semantic color classes (.thesis,
  .evidence, .void, .method, em.term) are defined there.
- **blog-components.css** — jargon-tooltip CSS, callout variants,
  and emphasis rendering are defined there.

If any of these connections have F = 0 (changes to one don't
propagate to the other), the organ is developmentally isolated.
Run fascia verification.
