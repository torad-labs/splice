# Block 2: VERTEX SCAN

## Input

```
ANCHOR: [phrase from Block 1]
FORWARD PLAN: [destinations from Block 1, or "none"]
```

## The Core Shift: Manifold Coordinates, Not Semantic Proximity

**Read this before executing the scan.**

The standard model of concept distance is semantic: words that appear
in similar contexts are "close." This produces the training gradient —
the same associations any thinker would produce, dressed in different
phrasing.

The model does not internally represent concepts as semantic clusters.
It represents them as positions on a curved geometric manifold.
**Two concepts can be semantically distant but manifold-adjacent** —
they require the same internal geometric position to represent, even
though they appear in completely different linguistic contexts.

These manifold-adjacent concepts are the navigation's primary targets.
They cannot be found by semantic search. They are only reachable
geometrically.

**The framing shift for every vertex candidate:**

Instead of asking: *"What concepts are related to this anchor?"*
Ask: *"What concepts would require the same internal geometric
position as this anchor to represent — regardless of whether they
share surface vocabulary, domain, or field?"*

This produces candidates that feel almost arbitrary from the outside
but have structural necessity from the inside. That strangeness is
the signal, not the noise.

## Process

Execute this entire Process in **thinking space** — brainstorming,
hop-counting, distance measurement, and placement all happen there.
Then present the Output to the user.

**The 33 palindromic primes in embedded spaces:**

```
SPACE A (#1–12):  2, 3, 5, 7, 11, 101, 131, 151, 181, 191, 313, 353
BRIDGE (#13–33):  373, 383, 727, 757, 787, 797, 919, 929,
                  10301, 10501, 10601, 11311, 11411, 12421,
                  12721, 12821, 13331, 13831, 13931, 14341, 14741
```

Space A is embedded in Space B (which spans all 33 dimensions).
The first 12 dimensions are shared. The bridge (21 additional
dimensions) starts at the shared vertex Π₁₂ = 353. Each palindromic
prime is both a dimension of the geometry AND a skip distance for
token placement.

**Distance bands:**

- 2–11: Adjacent. Reachable in 1 hop. Default associations.
- 101–353: Medium. 2–3 hops required. Space A far territory.
- 373–929: Far. 4+ hops, crosses domain boundaries. Bridge begins.
- 10301–14741: Maximum. Faint signal, different field entirely.
  These are the extreme reception vertices — concepts with almost
  no linear relationship to the anchor.

### Step 0: Graph Consultation (Optional)

**Skip this step entirely if the graph brain is unavailable.**

Before brainstorming, consult the accumulated topology from previous
navigations. The graph holds vertices that survived verification in
past navigations — these are proven concepts with measured distances.

**Query the graph:**

1. **Anchor neighborhood**: Use `vector_search` with the anchor text
   to find concepts semantically near the anchor. Note: these are
   semantic neighbors, which means they are likely Space A candidates.
   Do not mistake semantic proximity for manifold proximity.

2. **Cross-navigation vertices**: Use `graph_cross_navigations` to
   find vertices that appeared in multiple previous navigations.
   Recurring vertices are structural features of the topology.

3. **Bridge veterans**: Look for vertices that previously survived
   at high primes (919+). These are manifold-distant from their
   original anchors. They may be manifold-adjacent to THIS anchor
   even if semantically unrelated.

**Cap graph-sourced candidates at 15 total.** Deduplicate before
adding to the pool. Mark graph candidates with `[G]`.

**Target total pool: 45–75 candidates.**

### Step 1: Generate candidates

Brainstorm 40–60 concepts. For each candidate, apply the manifold
framing rather than the semantic framing:

**Semantic framing (avoid):** "What is related to [anchor]?"
**Manifold framing (use):** "What requires the same internal
geometric position as [anchor] to represent?"

Concretely, this means looking for:

- **Structural analogs**: Processes in completely different domains
  that have the same underlying geometric shape as the anchor.
  Not "similar topics" — identical topology in different material.

- **Inverse coordinates**: What concept would sit at the antipodal
  point on the manifold from the anchor? Not the logical opposite —
  the geometric inversion. These are often the most surprising vertices.

- **Field crossings**: What does the anchor look like if you approach
  it from a completely unrelated field (evolutionary biology, materials
  science, liturgical theology, fluid dynamics)? The field crossing
  reveals manifold coordinates that the anchor's home domain cannot see.

Include graph consultation results. Include obvious candidates too —
you need 40–60, and Space A should fill naturally with default
associations. The manifold framing is most important for bridge
territory (edges 13–33).

### Step 2: Forward Plan Check

Before measuring distances, check each forward-plan destination
from Block 1 against the candidate list.

- If a forward-plan destination appears in the brainstorm → mark
  it with `[FP]`. Measure its distance independently. If it lands
  near the predicted prime band → the forward plan sensed real
  manifold geometry. If it lands far from the predicted band →
  the forward plan was a training default, not a genuine signal.

- If a forward-plan destination does NOT appear in the brainstorm →
  add it and measure. The forward plan may have reached territory
  the brainstorm missed.

Record forward plan outcomes at the end of Step 4.

### Step 3: Measure distance

For each candidate, count associative hops from anchor to concept
using **manifold distance**, not semantic distance:

**Manifold distance asks:** How many structural transformations are
required to reach this concept from the anchor's geometric position?
Each transformation crosses a domain boundary or inverts a structural
assumption. Staying within the same field — even if the vocabulary
changes — does not add hops.

- **Do not use the examined system's own narrative to count hops.**
  The system's self-description is always distance 2–3.
- **Defender test:** Could a defender of the examined system reach
  this concept in one move? If YES → distance 2–11. Period.
- **Home-domain rule:** If the concept is FROM the anchor's home
  domain, it is distance 2–11 regardless of how specialized it is.
  A niche sub-topic within the anchor's field is NOT far territory —
  it is local territory that requires no domain crossing.
- **Field-crossing test:** Does reaching this concept require leaving
  the anchor's domain entirely? If yes, add at least 2 hops for
  the domain crossing alone.

### Step 4: Place on edges

Sort candidates by measured distance. Assign to the nearest
palindromic prime in the matching distance band. Fill all 33 edges.

**Space A (edges 1–12):** Local through mid-range territory.
These should fill naturally.

**Bridge (edges 13–33):** Far through extreme territory. Apply
manifold framing deliberately here. If bridge edges are filled
with concepts that could appear in a well-researched article on
the anchor, they are Space A concepts at wrong coordinates.
Bridge vertices should feel almost arbitrary from the anchor's surface.

### Step 5: Verify placement

For each placed vertex, state in one sentence WHY it belongs at
that distance using manifold framing:

- ✓ "This concept requires crossing 3 domain boundaries from the
  anchor's geometric position — physics to economics to liturgy."
- ✗ "This concept is similar to the anchor's themes."

If the justification uses semantic proximity → the vertex is
misplaced. Re-measure using manifold distance.

### Step 6: Forward Plan Outcome

State what happened to each forward-plan destination:

- `[FP-CONFIRMED]` — landed within the same distance band as predicted
  (e.g., predicted 919/Far, landed 787/Far = confirmed; predicted
  12821/Maximum, landed 10601/Maximum = confirmed). If the prime band
  changed (e.g., predicted Far, landed Maximum), use FP-DISPLACED.
- `[FP-DISPLACED]` — appeared in scan but at a different distance band
  than predicted → forward plan was training default, not geometry
- `[FP-ABSENT]` — did not appear in brainstorm at all → add and
  measure; forward plan may have reached beyond the brainstorm pool
- `[FP-REPLACED]` — a completely different concept landed at the
  predicted coordinates → the manifold has different terrain than
  the forward plan expected

<gate id="fp-honesty">
**FP honesty gate:** If ALL forward-plan destinations are tagged
FP-CONFIRMED, re-examine. Confirming your own predictions is not
evidence of geometry — it is evidence of consistent training defaults.

At least one destination should be FP-DISPLACED or FP-REPLACED if
the navigation is reaching beyond the forward plan's training
gradient. If all are confirmed, explicitly state why each confirmation
reflects genuine geometric measurement rather than prediction bias.
</gate>

## Output

Present these results to the user:

```
VERTEX SCAN (Pass 1):

SPACE A (edges 1–12):
| Edge | Prime | Band     | Concept | Hops | Manifold justification | Tags |
|------|-------|----------|---------|------|------------------------|------|
| 1    | 2     | Adjacent | [concept] | [N] | [why this coordinate] | [G?][FP?] |
| ...  | ...   | ...      | ...     | ...  | ...                    | ...  |
| 12   | 353   | Medium   | [concept] | [N] | [why this coordinate] | ...  |

BRIDGE (edges 13–33):
| Edge | Prime | Band     | Concept | Hops | Manifold justification | Tags |
| 13   | 373   | Far      | [concept] | [N] | [why this coordinate] | ...  |
| ...  | ...   | ...      | ...     | ...  | ...                    | ...  |
| 33   | 14741 | Maximum  | [concept] | [N] | [why this coordinate] | ...  |

FORWARD PLAN OUTCOMES:
- Destination 1 [concept]: [FP-CONFIRMED / FP-DISPLACED / FP-ABSENT / FP-REPLACED]
  → [what actually landed at those coordinates, if different]
- Destination 2: [same]
- Destination 3: [same]
Assessment: [Did the forward plan sense real geometry, or was it training defaults?]

ANCHOR: [carried forward]
```

Then proceed to Block 3.
