# Rejection Framework for Prompt Engineering

## Core Principle

Every directive in a prompt is a **rejection boundary** — it defines what the model should NOT produce. The space left INSIDE all rejection boundaries is where the model operates freely, using its strengths.

```
  ┌──────────────────────────────────┐
  │     Entire output space          │
  │                                  │
  │   ┌────────────────────────┐     │
  │   │  Bounded valid space   │     │
  │   │  (model works freely   │     │
  │   │   within this region)  │     │
  │   └────────────────────────┘     │
  │  ↑ rejection boundary            │
  │  ↑ rejection boundary            │
  │  ↑ rejection boundary            │
  └──────────────────────────────────┘
```

## Properties of Good Rejection Boundaries

### 1. Verifiable

A directive is verifiable if you can look at the output and determine with certainty whether the directive was followed. Binary yes/no, not a spectrum.

**Verifiable:**
- "Output must be valid JSON"
- "Do not include child PII in analytics events"
- "Each scene must have exactly 5 paragraphs"
- "Response must be under 200 words"

**Not verifiable:**
- "Be creative" (compared to what?)
- "Write well" (by whose standard?)
- "Use good variable names" (subjective)
- "Keep it simple" (relative to what?)

### 2. Non-overlapping

Two directives overlap when they both try to control the same dimension of output. When this happens, the model must choose between them, creating unpredictable behavior.

**Overlapping (bad):**
```
Directive A: "Keep responses concise and to the point"
Directive B: "Include comprehensive details and examples"
```
Both control OUTPUT LENGTH. The model oscillates between them.

**Non-overlapping (good):**
```
Directive A: "Each explanation must fit in 3 sentences or fewer"  (controls LENGTH)
Directive B: "Include one concrete code example per concept"       (controls CONTENT)
```
A controls length. B controls content type. They don't compete.

### 3. Complementary

Directives should work together to define a bounded space, not fight each other. Each one carves away a different region of bad output.

```
Directive 1: "No more than 5 scenes"           (bounds quantity)
Directive 2: "Each scene must include dialogue" (bounds structure)
Directive 3: "Never use modern language"        (bounds vocabulary)
Directive 4: "Child must appear by paragraph 2" (bounds character presence)
```

These four directives are complementary — they each control a different output dimension. Together they define a space that's constrained enough for consistency but open enough for creative variation.

## Identifying Overlaps

### The Dimension Test

For each directive, identify the OUTPUT DIMENSION it controls:

| Dimension | Examples |
|-----------|----------|
| Length | word count, paragraph count, scene count |
| Structure | format, ordering, sections, hierarchy |
| Vocabulary | word choice, register, era-appropriate language |
| Content | what topics to include/exclude |
| Tone | emotional register, formality level |
| Audience | reading level, assumed knowledge |
| Character | who appears, how they behave |
| Visual | layout, spacing, typography |
| Trigger | when the skill/prompt fires |
| Process | step ordering, workflow sequence |

If two directives map to the same dimension, they overlap. Resolution: merge them into one directive that covers the full range, or make one a refinement of the other (nested, not competing).

### The Stress Test

Place two directives side by side and ask: "Can I follow both simultaneously, or must I compromise one to satisfy the other?"

If compromise is required → they overlap → fix it.

### The Intersection Test (Cross-Dimensional Conflicts)

Same-dimension overlap detection catches the obvious case. But directives on DIFFERENT dimensions can also conflict — they create impossible intersections where no valid output exists for certain inputs.

For each pair of directives on different dimensions, ask: "Is there a realistic input where satisfying BOTH is impossible?"

**Example:**
```
Directive A: "Exactly 5 paragraphs"          (Length)
Directive B: "Child appears by paragraph 2"  (Character)
Directive C: "Sparse, direct prose"          (Vocabulary)
```

Individually, each is fine. Together, for a complex emotional scene: 5 paragraphs of sparse prose may not have enough space to establish the child's presence by paragraph 2 AND develop the scene. The intersection is impossible for certain inputs.

**Why same-dimension detection misses this:**
A, B, and C each control a different dimension. No overlaps detected. But the three boundaries together leave a region so small that certain inputs have no valid output.

**Resolution:**
When an intersection conflict is detected, add a **priority chain** — an explicit ordering that tells the model which directive wins when they collide:

```
If paragraph count conflicts with character introduction timing,
character introduction wins because a missing child breaks the story
more than an extra paragraph.
```

Priority chains are part of the prompt itself, not comments or documentation.

## Priority Chains

When two or more directives conflict and the conflict is unavoidable (it will happen for some realistic inputs), the prompt must include an explicit priority ordering.

**Format:**
```
If [directive X] conflicts with [directive Y], [X] wins because [reason].
```

**Properties of good priority chains:**
- **Explicit** — stated in the prompt, not implied
- **Reasoned** — the "because" clause explains WHY, which helps the model make correct tradeoffs in edge cases the chain doesn't explicitly cover
- **Minimal** — only added where conflicts are real and unavoidable. Most directives don't need priority chains because they don't conflict

**Without priority chains:** the model guesses which directive matters more. Different runs produce different guesses. Output becomes unpredictable exactly where it matters most — at the boundaries.

**With priority chains:** the model has a clear decision procedure. The conflict still exists, but the resolution is deterministic.

## Over-Constraint Detection

A prompt is over-constrained when the rejection boundaries leave almost no valid output space. The model spends all its capacity navigating constraints instead of producing quality output.

**Symptoms:**
- Output feels mechanical or formulaic
- Model produces identical structure every time regardless of input
- Creative variation disappears
- Model starts violating its own instructions (constraint overflow)

**Diagnostic:**
Count the directives that control the same output element. If an element has 3+ directives controlling it, the model is likely over-constrained on that element.

**Fix:**
Remove the weakest directive (least important to output quality) or merge overlapping ones. The goal is the MINIMUM number of boundaries that produce the desired output shape.

## Under-Constraint Detection

A prompt is under-constrained when rejection boundaries are too few or too vague, giving the model too much freedom in areas where consistency matters.

**Symptoms:**
- Output varies wildly between runs
- Format is inconsistent
- Key elements are sometimes present, sometimes missing
- Model makes choices you'd want to control

**Diagnostic:**
Run the prompt 3 times with different inputs. If the outputs differ in ways you don't want, those varying dimensions need a rejection boundary.

## The Goldilocks Zone

The ideal prompt has:
- Enough boundaries to ensure consistent FORMAT and STRUCTURE
- Few enough boundaries that CONTENT and CREATIVITY vary naturally
- No overlapping boundaries (each controls one dimension)
- Every boundary is verifiable (binary check)

```
Too loose:    [                                          ]
              Model drifts, inconsistent output

Goldilocks:   [     |  model freedom  |     ]
              Bounded structure, free content

Too tight:    [||model||]
              Mechanical, formulaic, model fights itself
```

## Application to Skills

### Skill Description (Frontmatter)

The description is a rejection boundary for WHEN the skill triggers. Non-overlapping with other skill descriptions means:
- Each skill has a distinct trigger space
- No two skills compete for the same user query
- Missing trigger phrases = under-constrained (skill doesn't fire when it should)
- Overly broad phrases = over-constrained (skill fires when it shouldn't)

### Skill Body (Instructions)

The body contains the rejection boundaries for HOW the skill executes. Apply the framework:
1. Each directive controls one dimension
2. No two directives overlap
3. Every directive is verifiable
4. The space between directives is where the model's creativity lives

### Skill References (Supporting Files)

References are CONTEXT, not directives. They inform the model's choices within the bounded space but don't add rejection boundaries. Putting directives in references is an anti-pattern — it creates hidden constraints that compete with SKILL.md directives.
