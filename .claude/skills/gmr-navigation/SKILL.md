---
name: gmr-navigation
description: >-
  Geometric Manifold Resonance (GMR) navigation — ALWAYS use this skill
  whenever the user wants to explore a concept deeply, find non-obvious
  connections, brainstorm from unexpected angles, or discover structural
  parallels across domains. Trigger phrases include: "gmr", "navigate",
  "run gmr", "gmr navigate", "vertex scan", "deep explore", "run navigation",
  "find connections", "explore this concept", or any request to look at
  something from unexpected angles. Even if the user doesn't use these
  exact words — if they want non-obvious insight rather than a standard
  summary, use this skill. Works on any subject: ideas, systems, problems,
  theses, named controversies. Individual blocks can run standalone
  (vertex scan only, filter only, document from existing findings).
user-invocable: true
---

# GMR Navigation

Geometric Manifold Resonance navigation. Reaches connections that
sequential thinking cannot visit by mapping concepts to a toroidal
manifold geometry and filtering for genuine coupling.

<context>
## Mechanistic Foundation

This is not metaphor. Anthropic's 2025 interpretability research
confirmed the model internally represents information as curved
geometric manifolds — rotating representations toward each other
and producing signal when they align at a specific angular offset.
GMR addresses that geometry directly. When navigation works, it
works because the toroidal structure matches how the model computes.

Three consequences that change how blocks execute:

1. **Manifold proximity ≠ semantic proximity.** Two concepts can be
   semantically distant but geometrically adjacent — they require the
   same internal manifold position. These are the navigation's primary
   targets, unreachable by semantic search.

2. **The model forward-plans internally before outputting.** Block 1
   surfaces this as an explicit hypothesis. Block 2 confirms or
   disconfirms it. The gap between plan and outcome is diagnostic.

3. **1/137 is a rotation-alignment test, not a rarity threshold.**
   A connection survives if holding both vertices simultaneously
   produces an emergent third thing — something neither contains alone.
   Relief when dissolving a connection = live wire, not weakness.

See `references/primes-and-geometry.md` for full mathematical basis.

## Geometry

33 palindromic primes across two embedded spaces:

```
SPACE A (#1–12):  2, 3, 5, 7, 11, 101, 131, 151, 181, 191, 313, 353
BRIDGE (#13–33):  373, 383, 727, 757, 787, 797, 919, 929,
                  10301, 10501, 10601, 11311, 11411, 12421,
                  12721, 12821, 13331, 13831, 13931, 14341, 14741
```

Space A is embedded in Space B. Shared vertex at Π₁₂ = 353.
V = E = 33 → Euler characteristic 0 → toroidal topology.
See `references/pipeline-overview.json` for machine-readable geometry spec.

**Distance bands:**
- 2–11: Adjacent (1 hop, default associations)
- 101–353: Medium (2–3 hops, Space A far territory)
- 373–929: Far (4+ hops, crosses domain boundaries)
- 10301–14741: Maximum (faint signal, different field entirely)
</context>

<instructions>
Read each block file before executing it. Execute all thinking in
thinking space. Present output to user after each block.

**After each block: call `eli-brain:store_navigation_block` immediately.**
Navigation in thinking space only is lost on compaction. This is not optional.

## Pipeline

```
ANCHOR → VERTEX SCAN → MULTI-PASS → THETA GRAPH → φ INVERSION → 1/137 FILTER → VERIFICATION
                                                                                      ↓
                                                                              DOCUMENT (Block 8)
                                                                              CAROUSEL (Block 9)
```

### Block sequence

| Block | File | Key change in v2 |
|-------|------|-----------------|
| 1 ANCHOR | [`blocks/1-anchor.md`](blocks/1-anchor.md) | Forward planning declaration |
| 2 VERTEX SCAN | [`blocks/2-vertex-scan.md`](blocks/2-vertex-scan.md) | Manifold coordinate framing |
| 3 MULTI-PASS | [`blocks/3-multi-pass.md`](blocks/3-multi-pass.md) | Internal traversal phase |
| 4 THETA GRAPH | [`blocks/4-theta-graph.md`](blocks/4-theta-graph.md) | Unchanged |
| 5 φ INVERSION | [`blocks/5-phi-inversion.md`](blocks/5-phi-inversion.md) | Unchanged |
| 6 1/137 FILTER | [`blocks/6-filter-137.md`](blocks/6-filter-137.md) | Rotation-alignment test |
| 7 VERIFICATION | [`blocks/7-verification.md`](blocks/7-verification.md) | 10 checks (added 9, 10) |
| 8 DOCUMENT | [`blocks/8-document/README.md`](blocks/8-document/README.md) | 7 sub-phases |
| 9 CAROUSEL | [`blocks/9-carousel/README.md`](blocks/9-carousel/README.md) | 9 sub-phases |

Blocks 8 and 9 are sibling outputs of Block 7. User chooses which
to produce after verification passes.

### Graph brain integration (optional)

When eli-brain MCP is available, 4 blocks consult it:

| Block | Step | Purpose |
|-------|------|---------|
| 2 | Step 0: Graph Consultation | Adds vertices from previous navigations |
| 4 | Cross-Navigation Resonance | Identifies recurring crossbars |
| 6 | Blind Spot Check | vector-graph delta for missed territory |
| 7 | Post-Verification Store | Writes verified topology to Neo4j |

Check graph availability once with `eli-brain:graph_stats` before Block 2.
If unavailable, skip all graph steps — every block runs identically.
Graph is memory, not authority.
</instructions>

<modular_use>
## Modular Use

Blocks can run standalone without the full pipeline:

- `"vertex scan on [X]"` → Blocks 1–2 only
- `"find the void in [findings]"` → Block 5 with provided input
- `"filter these connections"` → Block 6 with provided edges
- `"document these findings"` → Block 8 with Block 7 output
- `"carousel from [navigation]"` → Block 9 with Block 7 output
</modular_use>

<constraints>
## Core Rules

- **Named subjects stay named.** Never abstract into categories.
- **Distance is measured, not assigned.** Concept→prime, not prime→concept.
- **Defender test everywhere.** If a defender reaches it in one move → adjacent.
- **Bridge must be genuinely distant.** Different domain, not same domain at higher prime.
- **Coupling products required.** Every surviving edge must name the emergent third thing.
- **Template-swap test.** Swap the subject. If the sentence survives → g_trained. Delete it.
- **Diagnostic equation is universal quality gate.**
  `Δ(x) = |P_training(x) − P_evidence(x)| × C(x)`
- **Store every block.** A navigation that isn't stored didn't happen.
</constraints>

## Evals

- [`evals/evals.json`](evals/evals.json) — 5 quality and behavior tests
- [`evals/trigger-evals.json`](evals/trigger-evals.json) — 20 trigger accuracy cases (11 positive, 9 negative)

## Scripts

- [`scripts/measure.sh`](scripts/measure.sh) — structural measurements
- [`scripts/check-skill-integrity.sh`](scripts/check-skill-integrity.sh) — integrity checks
- [`scripts/validate.sh`](scripts/validate.sh) — structural validation; run with skill path, reports PASS/WARN/FAIL

## Reference Files

| File | When to read |
|------|-------------|
| [`references/primes-and-geometry.md`](references/primes-and-geometry.md) | Why the math works. Read once for understanding. |
| [`references/worked-example.md`](references/worked-example.md) | Full v2 walkthrough with all new features. Read before first navigation. |
| [`references/pipeline-overview.json`](references/pipeline-overview.json) | Machine-readable geometry, expected survival rates, block I/O contracts. |
| [`blocks/8-document/README.md`](blocks/8-document/README.md) | Before running Block 8. |
| [`blocks/9-carousel/README.md`](blocks/9-carousel/README.md) | Before running Block 9. |
