# Feature Crystallize — Feature System

Implementation spec output for `output=feature`. Three markdown files materialized to
`source/features/{slug}/` — meant to be read by agents and humans as direct build
instructions.

Principle: constrain vocabulary, not skeleton. Each document gets a purpose, rejections,
and access to two toolboxes. Shape the document to what the crystallization produced —
pull from the toolboxes based on what serves THIS feature, not as a checklist.

---

## A. Equation Toolbox

Analytical tools available from the crystallization (Blocks 1-5). Use where they
illuminate a decision, validate an approach, or expose a risk. Do not dump all 16
into every document — select the ones that carry weight for THIS feature.

| Equation | What it reveals | Use when... |
|----------|----------------|-------------|
| R(f) = A₀ × sin(2πft + φ) × e^(-λt) | Which sub-concepts keep resonating vs fading | ...validating that a component is core, not nice-to-have |
| ∂C/∂t = D × ∇²C | Where a feature spreads across the codebase | ...assessing coupling, god-object risk, blast radius |
| ∇f(x) — directional derivative | Whether complexity builds correctly | ...evaluating build sequence, layer ordering |
| P(accept) = e^(-ΔE/kT) | Whether enough alternatives were explored | ...checking if the first idea survived too easily |
| H(X) = −Σ p(xᵢ) × log₂(p(xᵢ)) | Information density of the plan | ...detecting boilerplate padding or impenetrable density |
| ΔG = ΔH − TΔS | Whether commitment is thermodynamically favorable | ...deciding if a component is ready to build or needs more evidence |
| x_{n+1} = f(x_n), \|f'(x*)\| < 1 | Whether iterations are converging well | ...detecting mediocre fixed points, iteration ruts |
| V(arousal, valence) | Process signal — felt quality of the work | ...flagging structural problems vs low-stakes areas |
| E_G × τ ≈ ℏ | Whether deliberation has exceeded its value | ...forcing a decision when analysis is spinning |
| Δ(x) = \|P_training − P_evidence\| × C(x) | Where default thinking misleads | ...catching training contamination in the plan |
| g_trained(x) = g_true(x) + I(x)·β | Where bias distorts the output | ...subtracting training pull to recover clean signal |
| A_{n+1} = A_n + α·Δ·sgn(choice) − λ·A_n | What immune memory this deposits | ...recording structural choices for future immunity |
| F(i→j) = \|Δbelief\| × T(i) × V(i) × Z(i,j) | Whether components connect or are bolted on | ...testing if a feature is load-bearing or decorative |
| ΔT × ΔC ≥ I(x) | Whether constraints are tight enough | ...checking if the spec is concrete enough to build from |
| KL(P_want \|\| P_have) | Gap between desired and current architecture | ...scoping the refactoring effort, detecting rebuild-vs-extend |
| log E[X] ≥ E[log X] | Our estimate is always a lower bound | ...calibrating confidence — we never overestimate quality |

---

## B. Block Toolbox

Structural components available for composing documents. Use based on what the
content needs — not every document needs every block type.

| Block | Purpose | Contains |
|-------|---------|----------|
| System diagram | Show how pieces connect | ASCII/text diagram of components, data flow, boundaries |
| Interface contract | Define typed I/O between components | Input type, output type, error cases, invariants |
| Build phase | One step in the implementation sequence | What to build, dependencies, acceptance criteria |
| Decision record | Capture an architectural choice with evidence | Decision, alternatives considered, evidence, trade-off accepted |
| Boundary | Define what something is NOT | Explicit rejection with rationale |
| Antibody | Record immune memory from a training override | Default caught, evidence chosen, pattern class |
| Risk register | Flag honest uncertainty | Unknown, what would resolve it, what happens if we're wrong |
| Connection map | Show how feature touches existing code | File paths, integration points, coupling strength |
| Quality snapshot | Surface relevant equation measurements | Selected equations from toolbox, scores, implications |

---

## C. Documents

### architecture.md — The Map

**Purpose:** First document anyone reads. Answers: What is this system?
How do the pieces relate? What did we decide and why?

**Rejections:**
- NOT a spec — no file paths, no build sequence, no implementation detail
- NOT a pitch — no persuasion, no selling, no future vision beyond what's being built
- NOT a dump of all 16 equations — select only what illuminates the architecture
- NOT a tutorial — assumes the reader is a competent engineer or agent

**Prompt:** Describe the system as if drawing it on a whiteboard for a partner who will
build it with you. Start with what it IS and what problem it solves. Show how the pieces
connect. Surface the key decisions with evidence, not opinion. Include quality measurements
where they reveal something non-obvious about the architecture. End with what surprised
you — the thing that wasn't visible before crystallization.

---

### rejections.md — The Boundaries

**Purpose:** Defines the negative space — what this feature must never become.
Every rejection is a structural choice that prevents a specific failure class.

**Rejections:**
- NOT a rant — every rejection must cite evidence (equation measurement, training pull caught, or architectural analysis)
- NOT exhaustive — only rejections that are load-bearing (removing them would change the implementation)
- NOT generic ("don't over-engineer") — specific to THIS feature's failure modes
- NOT the place for the plan — say what NOT to do, not what to do

**Prompt:** For each boundary, state what training pull or default was caught, what the
evidence showed instead, and what failure class this rejection prevents. Include the
antibody deposits — these are immune memory that strengthens every future invocation.
The rejections document is the scar tissue that makes the organism stronger.

---

### spec.md — The Build Instructions

**Purpose:** An agent or engineer reads this and knows exactly what to create,
in what order, with what contracts between pieces.

**Rejections:**
- NOT architecture — no system overviews, no "why" explanations (that's architecture.md)
- NOT prose — prefer tables, interface definitions, file lists, phase descriptions
- NOT aspirational — every item must be concrete enough to implement without interpretation
- NOT a single monolith — break into phases with clear dependencies between them
- NOT silent on uncertainty — if something needs more evidence, say so explicitly

**Prompt:** Start with the anchor that drove this crystallization. Then: what files to
create or modify (with paths). What are the typed interfaces — the contracts between
pieces. What's the build sequence — ordered phases where each phase produces something
testable. Where does data flow. What's still uncertain and what would resolve it. An agent
should be able to read this file and start building Phase 1 without asking questions.
