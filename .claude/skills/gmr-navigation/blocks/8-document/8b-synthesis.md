# Block 8b: TEXT-ONLY SYNTHESIS

**Run this entire phase in thinking space.**

## Purpose

Write the narrative as PURE PROSE from the Phase 1 inputs
document. No HTML. No design. No animations. No class names.
Markdown at most. This phase produces a TEXT NARRATIVE and a
FASCIA MAP.

## Prerequisites

Phase 1 (8a-gather) output must exist: the inputs document with
GMR findings, pre-GMR goals, sources, thesis, and audience.

## Steps

### 1. Alignment

Before writing a single word of prose, explicitly align:

- **Pre-GMR goals** (what we wanted to discover)
- **GMR findings** (what we actually found)

Where do they agree? Where did the navigation reveal something
the original goals missed? Write this alignment out. The
synthesis writes from this intersection, not from either alone.

### 2. Build the Fascia Map

This is not optional. This is not "apply fascia." This is a
concrete artifact.

**Map F_θ (sequential tension):**

For each pair of adjacent sections, answer:

- What is the unresolved tension at the end of section N?
- How does section N+1 inherit and address that tension?
- What NEW tension does N+1 create?

Write it as a table:

```
| From → To     | Tension carried forward       | New tension created          |
|---------------|-------------------------------|------------------------------|
| Hero → Lede   | [visual impact → narrator]    | [the premise question]       |
| Lede → S1     | [premise planted]             | [what S1 leaves unresolved]  |
| S1 → S2       | [what S1 hands to S2]         | [what S2 leaves unresolved]  |
| S2 → S3       | ...                           | ...                          |
| S3 → S4       | ...                           | ...                          |
| S4 → S5       | ...                           | ...                          |
| S5 → Cliff    | [what S5 resolves + opens]    | [the void question]          |
```

**The Lede:** The lede is a numberless, headingless section between
the hero and Section 1. It contains the narrative hook: 1-3
paragraphs that set up the entire post's premise. The hero is a
visual event (title, canvas, subtitle). The lede is the narrator's
voice entering the room. Section 1 is where structured analysis
begins. The lede bridges these two modes invisibly. The reader
drifts from the hero into the lede without noticing a transition.
Do NOT fold the lede into S1 (it sets up the whole post, not just
S1). Do NOT label it "Intro" or "Preface" (labels make the reader
conscious of structure before they're ready).

If any row has empty cells, that section is disconnected. Fix it
before writing prose.

**Map F_φ (cross-connections):**

For each NON-ADJACENT pair of sections, check for thematic
resonance:

- Does section 1 plant a seed that section 4 harvests?
- Does the hero hook echo in the cliffhanger?
- Does the beginning connect to the end to form a LOOP?

Write it as a list:

```
φ connections:
- Hero ↔ Cliffhanger: [how they echo / loop]
- S1 ↔ S4: [thematic resonance]
- S2 ↔ S5: [thematic resonance]
- [any others]
```

**The toroidal requirement:**
The post is a toroid. The cliffhanger must open a question that
connects to the NEXT post's beginning. And the hero of THIS post
must close (or transform) a loop opened by the previous post's
cliffhanger. If this is the first post, the hero opens the loop
and the cliffhanger begins the series arc.

```
TOROID MAP:
Previous post cliffhanger → THIS post's hero: [connection]
THIS post's hero → THIS post's cliffhanger: [the loop]
THIS post's cliffhanger → NEXT post's hero: [the opening]
```

**Tensegrity check:**
F_total = min(F_fascia(i)) for all i. The weakest section-to-
section connection IS the post's breaking point. Identify it.
If any F ≈ 0, fix the map before writing prose.

`>>> CATCH <<<` Re-read your fascia map. For each F_θ row, ask:
"Is this tension REAL — does section N genuinely leave something
unresolved that section N+1 must address? Or did I fill this cell
because the table has a row for it?" Training pulls toward
complete tables. Evidence says: if the tension is manufactured,
the prose will feel forced. Empty cells are diagnostic signals,
not failures to fill.

### 3. Write the Narrative

Now write. Section by section. Pure prose.

**For each section, before writing, answer:**

1. What prediction is the reader running? (Cognitive groove)
2. How do I confirm it to deepen commitment? (The Lock)
3. What evidence breaks it? (Subvert: specific, directional)
4. What does the reader discover on their own? (Manufactured
   discovery)
5. What φ thread does this plant or harvest? (From fascia map)

**Voice rules (apply WHILE writing, not after):**

- _First person._ "I wrote," "I discovered," "I watched." The
  persona was there. The reader walks alongside.
- _Story-arc titles._ H2s read in sequence tell the story.
  Claims/experiences/turns, not categories.
- _No staccato._ Ban "Not X. Y." patterns. Sentences breathe.
  Jo-Ha-Kyū: slow entry, acceleration, sharp break.
- _No em dashes._ Zero. Use periods, commas, colons, semicolons,
  or split the sentence.
- _Experience-verifiable analogies only._ If you have to explain
  the source domain, cut the analogy.
- _Impedance matching._ Engineering language for engineers. Every
  abstraction grounded in reader experience.
- _Terminal examples._ Show what the reader SEES, not source code.
  Show wrong constraints too.

**Layered accessibility:** Every layer independently complete.

- Surface: the story (compelling, satisfying alone)
- Middle: the mechanism (WHY it works, verifiable analogies)
- Deep: the architecture (structural patterns, actionable)

**Ending structure:**

1. Step-by-step actions (concrete: create this file, run this)
2. Prompts the reader can paste (if applicable)
3. Progression path (first step → mastery)
4. Cliffhanger (the void question connecting to next post)

`>>> CATCH <<<` Stop. Pick one section you just wrote. Read it
aloud in your head. Did the reader DISCOVER the insight, or were
they TOLD it? Training pulls toward clear delivery: "The key
insight is..." Evidence says: if you can identify the sentence
where the reader's prediction breaks, the section is alive. If
the insight is stated rather than discovered, rewrite. The reader's
cognition does the restructuring. You engineer the conditions.

### 4. PCS Map

After writing, document the PCS cycle for each section:

```
| Section | Plant (prediction)    | Confirm (lock)        | Subvert (evidence)     |
|---------|-----------------------|-----------------------|------------------------|
| S1      | [reader believes...]  | [evidence confirms...]| [but actually...]      |
| S2      | ...                   | ...                   | ...                    |
| ...     |                       |                       |                        |
```

### 5. Breakout Component Map

After writing the prose, identify evidence that should become
visually distinct **breakout elements** in the final HTML. These
are the floating blocks that give the post visual texture: case
studies, callouts, and equations. Without them, the post reads
as a wall of flat prose regardless of content quality.

**Tag case studies:**
Any named, sourced example with a specific mechanism qualifies.
Mark them in the narrative with `[CASE STUDY: label]`:

- Named scientist/researcher + year + finding
- Named methodology/protocol + source + mechanism
- Named counter-case (where the mechanism fails or is hijacked)

Tag as many as the content demands. No prescribed minimum. Phase 4
(THE SKELETON) will determine which tagged case studies genuinely
need breakout containers.

```
| Section | Case Study            | Source                | Mechanism              |
|---------|-----------------------|-----------------------|------------------------|
| S2      | [name]                | [citation/source]     | [what it demonstrates] |
| S3      | [name]                | [citation/source]     | [what it demonstrates] |
| S4      | [counter-case name]   | [citation/source]     | [what it warns about]  |
| ...     |                       |                       |                        |
```

**Tag callout moments:**
Key insights or uncomfortable truths that should be visually
pulled from the prose into their own blocks. Mark with
`[CALLOUT: type]` where type is:

- `gold` — key insight (crystallized thesis)
- `violet` — void/uncomfortable truth
- `cyan` — technical detail
- `red` — warning/break

Tag as many as the content demands. No prescribed minimum. Phase 5
(THE EYES) will crystallize callout content and assign colors based
on the forces the content exerts.

## Output

Three artifacts:

1. **TEXT NARRATIVE** (markdown, full prose, section by section,
   with titles). No HTML tags. Case studies and callouts tagged
   inline with `[CASE STUDY: ...]` and `[CALLOUT: ...]` markers.
2. **FASCIA MAP** (F*θ table + F*φ list + toroid map +
   tensegrity score + PCS map)
3. **BREAKOUT MAP** (case study table + callout list)

All become inputs to Phase 3 (8c-audit).
