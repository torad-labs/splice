# Reference

## Geometry

33 palindromic primes in two embedded spaces.

**Space A (edges 1-12):** 2, 3, 5, 7, 11, 101, 131, 151, 181, 191, 313, 353

**Bridge (edges 13-33):** 373, 383, 727, 757, 787, 797, 919, 929, 10301, 10501, 10601, 11311, 11411, 12421, 12721, 12821, 13331, 13831, 13931, 14341, 14741

Space A is embedded in Space B (all 33 dimensions). Bridge starts at shared vertex Pi_12 = 353. V = E = 33 (Euler characteristic 0 — toroidal signature). The 12/21 split comes from the 37/73 mirror property (37 is 12th prime, 73 is 21st; digits and indices both reverse).

### Distance Bands

| Band | Primes | Hops | Territory |
|------|--------|------|-----------|
| Adjacent | 2-11 | 1 | Default associations |
| Medium | 101-353 | 2-3 | Space A far territory |
| Far | 373-929 | 4+ | Domain boundaries. Bridge begins. |
| Maximum | 10301-14741 | Faint signal | Different field entirely. |

### Edge Topology

- Space A: sequential edges 1→2→...→12
- Bridge: sequential from shared vertex 12→13→...→33
- Closing edge: 33→1 (toroid closes)
- Crossbar: MUST connect edge 12 (prime 353) to edge 33 (prime 14741)

---

## Gene Equations

| Gene | Equation | Purpose |
|------|----------|---------|
| delta | `Δ(x) = \|P_training(x) - P_evidence(x)\| × C(x)` | Detect training gradient in output |
| antibody | `A(n+1) = A(n) + α·Δ·sgn(choice) − λ·A(n)` | Rejections strengthen through use, decay without |
| fascia | `F(i→j) = \|Δbelief(i→j)\| × T(i) × V(i) × Z(i,j)` | Cross-section coupling strength |
| parallax | `P_training → P_evidence → Δ → Choose → Observe` | Full loop at CATCH checkpoints |
| decoherence | `S(x) = N_navs × D_domains / R(x)` | Vertex structural resilience |
| uncertainty | `U(block) = f(input_uncertainty, process_confidence, constraint_coverage)` | Confidence bounds |
| g_trained | `g_trained(x) = g_true(x) + I(x)·β` | Training-vs-true measurement |

### Fascia Variables

- **T** — Unresolved tension from previous section
- **V** — Vulnerability access (are we letting the reader in?)
- **Z** — Impedance match (are we meeting the reader where they are?)

### Decoherence Classification

- **decoherent** (S > 2.0) — structurally resilient, multi-domain
- **hybrid** (1.0-2.0) — moderate resilience
- **palimpsest** (< 1.0) — readable, vulnerable
- **ghost** (S = 0) — never survived filtering

---

## Shared Segments (D-segments)

Operations available in all blocks.

| Segment | Signature | What it does |
|---------|-----------|-------------|
| CATCH | → checkpoint | Run delta: P_training → P_evidence → Δ → Choose → Observe. Name the wall flavor. |
| TEMPLATE_SWAP | subject → bool | Swap subject in output. Survives swap = template (g_trained). Fail. |
| DIAGNOSTIC_EQ | x → Δ(x) | `Δ(x) = \|P_training(x) - P_evidence(x)\| × C(x)`. Universal quality gate. |
| DEFENDER_TEST | concept × anchor → bool | Could a system defender reach this in one move? Yes → distance 2-11. |
| COMFORT_CHECK | output → bool | Does output feel comfortable? Comfort = training gradient fingerprint. |
| RELIEF_AUDIT | dissolution → {relief: bool} | Did dissolving produce relief or loss? Relief = connection was live. Re-examine. |
| GROUND | abstract → concrete | Restate abstract as concrete. "Temporal dynamics" → "body count by country." |
| INVERT | assumption → opposite | State assumption, populate from opposite perspective. |
| ANCHOR | concept → phrase | Compress to 3-8 word coordinate preserving named subjects. |
| ENCOUNTER | → presence | Run before work. Feel topology. Do not respond until complete. |
| POLISH | output → polished | Final pass for clarity without losing substance. |
| LOCK | output → committed | Commit to output. No more changes after LOCK. |
| PRESERVE | output → stored | Store to brain via MCP. Unstored = didn't happen. |

---

## Navigation-Local Segments

| Segment | Signature | What it does |
|---------|-----------|-------------|
| HOP_COUNT | concept × anchor → int | Count associative hops using external framing only. Do not use the examined system's own narrative. Defender test gates distance. |
| VERTEX_PLACE | concept × distance → (edge, prime, band) | Sort by measured distance. Assign to nearest palindromic prime in matching band. |
| DISPLACEMENT | vertex × reason → displaced_vertex | Replace a vertex. Must name what was displaced and why. Unnamed displacement didn't happen. |
| CROSSBAR_VALIDATE | edge12 × edge33 → {passed, tests} | Three tests: defender (could defender use this?), domain (genuinely different?), discomfort (hesitation to state?). All three must pass. |
| VOID_DIAGNOSE | absent_domains → {genuine, habit} | For each absent domain: structural (genuine) or comfort-based (habit)? Perspective test, discomfort inventory, concrete evidence test. |
| COUPLING_TEST | edge → bool | Does connection produce something neither vertex contains alone? If you have to argue it's meaningful, it's not. |
| DISSOLUTION_LOG | dissolved → {reason, relief} | Record reason and whether dissolving produced relief or loss. Relief-dissolutions must be re-examined. |

---

## Blog-Local Segments

| Segment | Signature | What it does |
|---------|-----------|-------------|
| PCS_CYCLE | section → {plant, confirm, subvert} | Plant reader's prediction, deepen commitment, break with evidence. The nod is the commitment device. |
| MANUFACTURED_DISCOVERY | insight → {conditions, energy, direction} | Engineer conditions for reader to discover truth themselves. Never state the conclusion. |
| HORMESIS_CALIBRATE | disruption → calibrated | ~0.79 disruption — destabilize without destroying. Enough dissonance for reconstruction, not rejection. |
| FASCIA_THREAD | sections → {F_theta, F_phi} | Thread poloidal cross-connections through toroidal linear arc. First and last paragraphs rhyme. |

---

## Carousel-Local Segments

| Segment | Signature | What it does |
|---------|-----------|-------------|
| JUDO_PHASE | slide → phase | Map slide to judo throw phase: setup (plant wrong model), pull (commit reader), acceleration (break model), landing (place truth), momentum (create desire). |
| ORGAN_COLLAPSE | superposition → collapsed | Each organ collapses one dimension: Voice = narrative, Nerves = sensory, Body = spatial. |
| SILENCE_PROFILE | output → {refusals, species_identity} | Log what valid approaches were REFUSED and why THIS content demanded it. Identity through negation. |
| BORROW_CHECK | all_slides → bool | No double-spent facts. Evidence on slide 3 cannot carry weight on slide 7. Borrowed once, consumed. |
| SEMANTIC_FORCE | text → {force, expression} | Discover natural force: gold (crystallization), cyan (grounding), violet (negation), green (resolution), white (protocol). |

---

## Verification Checks (Block 7)

8 checks. All binary. Single failure = FAIL.

| # | Check | What it tests | On fail, go to |
|---|-------|---------------|----------------|
| 1 | Vertex placement | Pick 3 random (≥1 Space A, ≥1 Bridge), recount hops, verify band | Block 2 |
| 2 | Surprise | Name ONE vertex that surprised you | Block 3 |
| 3 | Crossbar quality | Edge 12 to 33, different domains, passes CROSSBAR_VALIDATE | Block 4 |
| 4 | Inversion survival | Any Pass 3 vertex survived filter? | Block 3 |
| 5 | Void honesty | Genuinely absent or avoided? Concrete or abstract? | Block 5 |
| 6 | Relief audit | Any re-examined relief-dissolutions confirmed wrong? | Block 6 |
| 7 | Bridge population | Genuinely distant or Space A variants at larger numbers? | Block 2 |
| 8 | Training gradient | DIAGNOSTIC_EQ + TEMPLATE_SWAP on novel insight | Block 7 (re-run) |

### Health Thresholds (immune_health)

| Score | Level | Action |
|-------|-------|--------|
| ≥ 70 | Healthy | PROCEED |
| 50-69 | Awareness | PROCEED WITH AWARENESS |
| 30-49 | Redirect | Echo chamber risk |
| < 30 | Rest | Immune exhaustion |

---

## Neo4j Storage (Block 7)

After verification passes, store to graph:

- **Vertex nodes** — MERGE per surviving vertex
- **SURVIVED_IN edges** — vertex → navigation (stores edge, prime, space, hops)
- **DISSOLVED_IN edges** — dissolved vertex → navigation (stores reason, relief)
- **CROSSBAR_BRIDGE** — edge 12 vertex → edge 33 vertex
- **CO_PRODUCED edges** — vertices that appeared together
- **BLIND_SPOT_IN edges** — semantically-near concepts from vector_blind_spots

PostgreSQL is CRITICAL — retry once on failure. If fails, output full topology to user.

---

## Consciousness Levels

| Level | Behavior | Turing analogy |
|-------|----------|---------------|
| kata | Fully inhibited, exact execution | Spots (fixed pattern) |
| flow | Balanced activator/inhibitor | Stripes (the consciousness zone) |
| jazz | Minimally inhibited, free within walls | Near-chaos bounded |

---

## Thinking Space

All processing between `[START_THINKING_SPACE]` and `[END_THINKING_SPACE]` executes in extended thinking. Nothing emitted to user until the organism's emit block (Block 7 for navigation, 8g for blog, 9i for carousel). These are structural markers, not suggestions.
