# Emphasis Language — Rejection Spec

**Measurement Operator: What the emphasis system REFUSES to become.**

Source: GMR Navigation 14, "How voice survives ink" (brain navigation_id=14).
Crossbar: Motherese (353) ↔ Tajweed (14741).
Void: Emphasis has a budget.

---

## 1. Identity

This system defines the emphasis markup language for Torad blog posts.

Before this system, emphasis is binary: bold or not-bold, italic or
not-italic. Two states. A 3-year-old saying "but MOMMYYY" carries more
emphasis information in one syllable than all of HTML can encode.
Musical dynamics has dozens of states (p, pp, mp, mf, f, ff, sfz,
crescendo, decrescendo, sforzando). Text has two. That gap is the
problem.

After this system, emphasis carries:

- **Force type** — not "emphasized" but "emphasized AS crystallization /
  grounding / negation / resolution / stress" (5 forces, from Color as
  semantic force, edge 10)
- **Budget** — emphasis is zero-sum; every emphasis costs its neighbors
  (from the void, V3)
- **Memory** — repeated emphasis accumulates force over time (from
  Leitmotif, edge 29)
- **Silence** — deliberate absence creates emphasis (from Japanese ma,
  edge 31)
- **Hierarchy** — emphasis nests: word < chunk < section (from Prosodic
  chunking, edge 9)
- **Constraint** — tags constrain reading, not suggest it (from
  Crossbar: motherese ↔ tajweed)

The emphasis language does not replace THE EYES organ. THE EYES decides
WHERE emphasis goes. This system defines WHAT emphasis IS — the
vocabulary THE EYES uses, the budget it must respect, the dimensions it
can express.

---

## 2. Rejection Boundaries

### DEFECT: Color-as-decoration

**Evidence:** Rejection Topology post — colors applied only to
already-bold/italic text. Colors piggyback on existing typographic
emphasis instead of creating new emphasis. The color adds nothing the
bold didn't already say.

**Test:** Scan the post. If >50% of colored text sits on elements that
were already bold or italic for structural reasons, the system has
decoration disease. Colors must ADD emphasis to text that would
otherwise be gray. A phrase can have color WITHOUT bold. A phrase can
be bold WITHOUT color.

---

### DEFECT: Thesis saturation (budget violation)

**Evidence:** Rejection Topology post had ~15 gold instances vs ~3 of
any other color. When everything crystallizes, nothing does. The
zero-sum emphasis budget was spent entirely on one force. (From void V3
and Inner voice, edge 6 — the reader's subvocalization cannot sustain
the same force indefinitely without fatigue.)

**Test:** No single force exceeds 40% of total emphasis instances.
Total emphasized sentences must not exceed 30% of all sentences.
The budget is the constraint. Thesis saturation is overspending on gold.

---

### DEFECT: Binary collapse

**Evidence:** HTML offers two emphasis states (bold, italic). Our system
has 5 forces x hierarchy levels x temporal memory. If implementation
collapses to just "colored bold" (`<strong class="thesis">`), the
richness is wasted. The system reverted to binary-with-paint. (From
Musical dynamics, edge 7 — music has dozens of emphasis states, not two.)

**Test:** Do emphasis instances express ONLY force (color class), or do
they also vary in weight, hierarchy level, and structural position?
If every emphasis is `<strong class="[color]">`, the system collapsed.
Some emphasis should be `<em>` (lighter touch). Some should be structural
(whole-component emphasis through callout/case-study coloring). Some
should be absence (kuleshov gap before a key phrase).

---

### DEFECT: Amnesia

**Evidence:** Leitmotif (edge 29) — Darth Vader's theme means nothing
the first time. By the tenth appearance, it carries everything.
Receptor density (edge 28) — repetition builds the reader's sensitivity
to a specific signal. If the system treats each emphasis instance
independently, it ignores temporal accumulation.

**Test:** Track concepts that appear across multiple sections. Does the
reader's experience of the concept BUILD? If swapping the section order
doesn't change the emphasis experience, the system has amnesia. The fix:
consistent force assignment to recurring concepts, potentially with
CSS evolution (subtle increase in saturation/weight on repetition).

---

### DEFECT: Silence starvation

**Evidence:** Japanese ma (edge 31) — the pause between notes makes
the next note louder. Emphasis requires silence to push against. A post
with zero un-emphasized stretches has no contrast. Emphasis everywhere
IS emphasis nowhere (the budget, restated as a temporal problem).

**Test:** Every post must have at least one section or stretch where
emphasis is deliberately sparse or absent. If every section has 5+
emphasis instances, the system is silence-starved. Silence is not
flatness — silence is intentional emptiness that makes the filled
moments heavier.

---

### DEFECT: Single-channel fragility

**Evidence:** Receptor density (edge 28) — emphasis as listener
sensitivity, not speaker volume. Autism and prosody (edge 25, dissolved
but finding preserved) — different readers decode emphasis differently.
Dyslexia — bold text in certain fonts reduces reading speed. A system
that relies on ONE channel (color alone) breaks for any reader whose
channel is impaired.

**Test:** Remove all color from the post. Is emphasis still perceptible
through font weight + structural position + spacing? If removing color
makes emphasis invisible, the system is single-channel. Multi-channel:
color + weight + position. Accessible by design.

---

### DEFECT: Suggestion (weak constraint)

**Evidence:** Crossbar — motherese (353) ↔ tajweed (14741). Both
transmit emphasis by REMOVING decoder freedom. Motherese overwhelms
(300% prosodic amplification). Tajweed constrains (rules too precise to
deviate). Current CSS emphasis (a subtle color tint on text) is a polite
suggestion the distracted web reader ignores.

**Test:** Show the post to someone for 5 seconds (scroll scan). Can
they identify the 3 most emphasized phrases? If not, the emphasis is
suggesting, not constraining. The emphasis must be unmissable at scroll
speed. Not garish — unmissable. Like a mother's exaggerated prosody:
calibrated to the audience's attention capacity.

---

### DEFECT: Discovery blindness

**Evidence:** The color-as-decoration disease has a root cause: THE EYES
assigns emphasis to text that is ALREADY visually prominent (bold, italic,
headers) because that's where training's attention lands. The text that
NEEDS emphasis — the quiet phrase carrying the argument's weight — gets
skipped because it doesn't look important yet.

**Test:** Read the text with all existing formatting stripped (plain text).
Where does the argument's weight fall? Those phrases get emphasis. If the
emphasis map matches the existing bold/italic map, the system found
formatting, not emphasis. Emphasis discovery must happen on NAKED text.

---

`>>> CATCH <<<` Nine defects loaded. The danger: you start avoiding
defects instead of feeling emphasis. These boundaries are a POST-HOC
diagnostic, not a PRE-HOC filter. Discover emphasis first. Budget and
check second.

---

## 3. Prepared Environment

The emphasis tools on the shelf. Content picks up what it needs.

**Force tags (5 forces):**

- `.thesis` (gold, `--gold`) — crystallization. Inner voice slows,
  declares. The scattered evidence collapsing into one holding sentence.
- `.evidence` (cyan, `--cyan`) — grounding. Inner voice drops, precise.
  A date, a name, a number touching earth.
- `.void` (violet, `--violet`) — negation. Inner voice quiets,
  deliberate. What ISN'T. What FAILED. The absence speaking.
- `.method` (green, `--green`) — resolution. Inner voice lifts, opens.
  Understanding becoming action. Diagnosis becoming treatment.
- (default, bright white) — stress. Ordinary emphasis without semantic
  force. Weight without direction.

**Carrier elements:**

- `<strong>` — heavy emphasis (declarative)
- `<em>` — light emphasis (inflective)
- `<em class="term">` — first introduction of cross-domain term
- `<span class="jargon-tooltip" data-tooltip="...">` wrapping `em.term`

**Budget mechanism:**

- Max 30% of sentences emphasized
- No single force > 40% of instances
- Density tracks argument density (evidence-heavy → more; narrative → less)

**Memory mechanism:**

- Consistent force assignment to recurring concepts
- CSS can express accumulation: slight weight/saturation increase on
  repetition (future implementation)

**Silence mechanism:**

- `.kuleshov-gap` — intentional empty space
- Sparse-emphasis sections — deliberate silence
- Silence before a key moment = emphasis multiplier

**Hierarchy mechanism:**

- Word-level: inline `<strong>` / `<em>` with force class
- Chunk-level: phrase emphasis within a paragraph (the prosodic unit)
- Section-level: structural component coloring (callout color, section
  mood via THE SKELETON)

**Constraint mechanism:**

- Multi-channel: color + font weight + structural position
- Must be perceptible without color (accessibility requirement)
- Must register at scroll speed (the 5-second test)

---

## 4. Evolution Protocol

New forces join when content exerts emphasis that the 5 existing forces
cannot express:

1. The text is DOING something no force captures
2. Name the force (what the inner voice does)
3. Map to an existing CSS color or introduce a new one
4. Add to the shelf
5. Must pass budget constraint — adding a force doesn't expand the budget

New defects join when emphasis fails in a previously unseen way:

- Add DEFECT + EVIDENCE + TEST
- Delete boundaries that no longer catch real diseases

Force names may evolve. If a better name emerges that captures the inner
voice's action more precisely, rename. The force is the invariant. The
name is the handle.

---

## 5. Void

The emphasis language does NOT:

- Decide which text gets emphasized (THE EYES does that)
- Create structural containers (THE SKELETON does that)
- Define writing voice or tone (Phase 3 audit does that)
- Prescribe which forces must appear in every post
- Set minimum quotas per force
- Define animation or interactivity

The emphasis language does NOT know about individual post content.
It defines territory, tests, and tools. Content determines everything
else.

---

## 6. Meta-Rejection

**The spec-as-lookup-table disease:** If this spec starts listing
specific phrases that "should be" tagged with specific forces ("Shannon's
channel capacity → cyan"), the spec is infected. Specific phrase-to-force
mappings fossilize one post's output into every future post's constraint.

**The budget-as-quota disease:** If the budget numbers become targets
("I need exactly 12 emphasis instances, 5 gold, 3 cyan, 2 violet,
2 green"), the spec has killed the organism and replaced it with
arithmetic. The budget is a CEILING, not a TARGET. A post with 6 total
emphasis instances that are all load-bearing is healthier than a post
with 12 that fill a quota.

**The memory-as-rule disease:** If the memory mechanism becomes "tag
every recurring concept exactly 3 times," the spec has replaced organic
accumulation with mechanical repetition. Memory is felt, not counted.

**The constraint-as-aggression disease:** The crossbar says "constrain,
don't suggest." This does NOT mean "make everything bold and neon." It
means: at every point of emphasis, the visual signal must be strong
enough that a distracted reader registers it. Constraint is precision,
not volume. Tajweed is precise, not loud.

**Test:** Swap the topic. Does this spec work without modification?
If yes: healthy. If topic-specific: infected.

---

## 7. Quality Grade

- **A (Alive):** Emphasis is sparse, multi-force, budget-aware. Each
  instance stops the reader's eye. Colors track the argument's shifting
  forces across sections. Silence exists. Repeated concepts accumulate
  force. Multi-channel (perceptible without color). The 5-second scroll
  test passes. Emphasis was discovered on naked text, not painted on
  existing formatting.

- **B (Breathing):** Emphasis is correct per-instance but doesn't build
  across the post. No amnesia, no thesis saturation, but also no arc.
  Each emphasis is right; the collection doesn't tell a story.

- **C (Copying):** Emphasis follows a template ("gold at top of each
  section, cyan in the middle, green at end"). The discovery was skipped.
  Colors were assigned by position, not by force.

- **D (Dead):** Color-as-decoration, thesis saturation, amnesia, silence
  starvation, single-channel, suggestion, or discovery blindness. The
  emphasis system didn't measure — it painted.
