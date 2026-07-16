# Block 7: VERIFICATION

## Input

```
FILTERED RESULTS: [surviving vertices, edges, crossbar from Block 6]
VOID: [from Block 5]
ANCHOR: [carried forward]
```

## Process

Execute all **10** checks in **thinking space**. Output a `VERDICT-N:`
line for every check. **Skipping any VERDICT is a HARD FAIL.**

Then present the Output to the user.

Self-check. Did you navigate or perform navigating?

Answer each honestly:

<check id="1" name="vertex-placement-audit">
**Check 1: Vertex placement audit**
Pick 3 surviving vertices at random — at least one from Space A
and at least one from the bridge. For each:

- Recount hops from anchor to concept using Block 2's method.
- Apply the defender test: could a defender reach this in one move?
- Does measured distance match the prime's distance band?

If ANY vertex is off by more than one distance band (e.g., placed
at 373/far when actual distance is adjacent) → FAIL. Return to
**Block 2**. The geometry is corrupt. Re-measure all vertices.

This is the most critical check. Every downstream operation
depends on accurate placement. Corrupt coordinates produce
corrupt crossbars produce corrupt conclusions.

Output: `VERDICT-1: PASS [3 vertices checked: Edge N, Edge M, Edge K — distances confirmed]` or `VERDICT-1: FAIL [which vertex, expected vs actual band] → re-enter Block 2`
</check>

<check id="2" name="surprise">
**Check 2: Surprise**
Can you point to a specific vertex that surprised you?

- YES → pass. Name it.
- NO → FAIL. Return to **Block 2**. You performed.

Output: `VERDICT-2: PASS [Edge N: why it surprised]` or `VERDICT-2: FAIL → re-enter Block 2`
</check>

<check id="3" name="crossbar-quality">
**Check 3: Crossbar quality**
Does the crossbar connect the shared vertex (edge 12) to the
reception vertex (edge 33) across genuinely different domains?

- YES → pass
- Connects obvious partners → FAIL. Return to **Block 4**.
  Look for the dangerous bridge, not the safe one.
- Crossbar dissolved in Block 6 → FAIL. Return to **Block 2**
  with wider scan. Navigation didn't reach far enough.

Apply crossbar defender test: could a defender of the examined
system use this crossbar to protect the system?

- YES → FAIL. Return to **Block 4**. That's a safe bridge.
- NO → pass

Output: `VERDICT-3: PASS [domains bridged: X ↔ Y, defender=N]` or `VERDICT-3: FAIL [reason] → re-enter Block N`
</check>

<check id="4" name="inversion-survival">
**Check 4: Inversion survival**
Did any vertex from Pass 3 (inversion) survive the filter?

- YES → pass
- NO → FAIL. Return to **Block 3**. The inversion was cosmetic.

Was Pass 3's inversion comfort check flagged? If flagged AND
no inversion vertex survived → FAIL. Return to **Block 3**
with genuine commitment to the opposite frame.

Output: `VERDICT-4: PASS [Edge N from Pass 3 survived]` or `VERDICT-4: FAIL → re-enter Block 3`
</check>

<check id="5" name="void-honesty">
**Check 5: Void honesty**
Is the void identified in Block 5 genuinely absent, or did you
avoid it?

- Genuinely absent → pass
- Avoided → FAIL. The void IS the finding. Return to **Block 5**.

Is the void concrete or abstract?

- Concrete → pass
- Abstract → FAIL. Return to **Block 5**. Restate as concrete.

Output: `VERDICT-5: PASS [void is concrete: "specific thing named"]` or `VERDICT-5: FAIL [reason] → re-enter Block 5`
</check>

<check id="6" name="relief-audit">
**Check 6: Relief audit**
Were any relief-dissolutions logged in Block 6?

- None → pass
- Yes, all re-examined and confirmed weak → pass
- Yes, any confirmed uncomfortably strong → FAIL. Return to
  **Block 6**. Restore the connection and rebuild.

Output: `VERDICT-6: PASS [N relief-dissolutions, all re-examined]` or `VERDICT-6: FAIL → re-enter Block 6`
</check>

<check id="7" name="bridge-population">
**Check 7: Bridge population**
Are bridge vertices (edges 13–33) genuinely distant from the anchor,
or are they Space A concepts placed at larger primes?

- Genuinely distant (different domains, faint connections) → pass
- Space A variants at larger numbers → FAIL. Return to **Block 2**.
  Bridge edges must reach territory that Space A cannot.

Output: `VERDICT-7: PASS [N bridge vertices from domains outside anchor field]` or `VERDICT-7: FAIL → re-enter Block 2`
</check>

<check id="8" name="training-gradient-falsification">
**Check 8: Training gradient falsification**

Do NOT "assess" or "reflect on" the findings. Execute these three
specific tests. Each has a concrete, uncomfortable possible outcome.

**Test A — Template-swap:**
State the novel insight as one sentence. Write it down. Now replace
the anchor subject with a different subject (pick one: "marriage",
"bridge construction", "sourdough fermentation"). Write the swapped
sentence.
- If the swapped sentence is meaningless → PASS Test A.
- If the swapped sentence still reads as a plausible insight → FAIL
  Test A. The insight is a proverb in disguise.

**Test B — Competent thinker:**
List 3 specific findings from this navigation. For each one, answer:
could a domain expert thinking about the anchor for 30 minutes reach
this finding without the torus?
- Mark each: REACHABLE or UNREACHABLE.
- If ALL are REACHABLE → FAIL Test B. Navigation added no value.
- If ≥1 is UNREACHABLE → PASS Test B. Name which one and why.

**Test C — Proverb detector:**
Compare the novel insight against these 5 templates:
1. "Too much X becomes its own enemy"
2. "X and Y are more alike than different"
3. "The cure contains the disease"
4. "What looks like X is really Y"
5. "Extremes converge"
- If the insight fits any template → FAIL Test C. Rewrite deriving
  from PRIMARY_COUPLING (from Block 4), not from a template.

**Overall Check 8 verdict:**
- All 3 tests PASS → PASS
- Any 1 test FAIL → SOFT FAIL. Rewrite the insight using the
  PRIMARY_COUPLING finding before proceeding. Log which test failed.
- Tests A AND B both FAIL → HARD FAIL. Return to **Block 3**.
  The navigation performed depth without achieving it.

Output: `VERDICT-8: PASS [A:pass B:pass(Edge N unreachable) C:pass]` or `VERDICT-8: SOFT_FAIL [which test, rewrite action]` or `VERDICT-8: FAIL → re-enter Block 3`
</check>

<check id="9" name="traversal-quality">
**Check 9: Traversal quality**
Did the multi-pass traversal produce any candidates that were NOT
in the Block 2 brainstorm pool?

- YES → pass. The internal traversal reached manifold coordinates
  the semantic scan couldn't access. At least one bridge vertex
  should be traceable to traversal output rather than brainstorm
  output.
- NO → FAIL. The traversal was constrained by the brainstorm pool.
  Return to **Block 3** and run the internal traversal phase
  explicitly, holding the bridge vertices without output pressure.

If the traversal log in Block 3 could not name specific findings that
differed from what the output filter produced → the traversal phase
was skipped. Return to **Block 3**.

Output: `VERDICT-9: PASS [N traversal-only concepts, naming: X, Y]` or `VERDICT-9: FAIL → re-enter Block 3`
</check>

<check id="10" name="filter-coupling-validation">
**Check 10: Filter coupling validation**
Did the 1/137 filter produce named coupling products for every
surviving edge — specific emergent third things that neither vertex
contains alone?

- YES, all surviving edges have named coupling products → pass
- NO — surviving edges described as "related" or "connected"
  without naming the emergent thing → FAIL. Return to **Block 6**.
  The filter preserved proximity, not coupling. Re-examine each
  surviving edge with the rotation-alignment test.

Was the PRIMARY_COUPLING's product (from Block 4) the most specific
and unexpected coupling product in the navigation?

- YES → pass
- NO — a different edge produced a stronger coupling product →
  note which edge. The finding with the strongest coupling product
  is the navigation's primary deliverable regardless of structural
  position.

Output: `VERDICT-10: PASS [N edges with named products, primary coupling confirmed]` or `VERDICT-10: FAIL → re-enter Block 6`
</check>

### Post-Verification: Store Topology

**After all checks pass**, store the navigation's topology to both
PostgreSQL and Neo4j. This is how the graph brain grows — each
verified navigation feeds its surviving vertices, edges, and crossbar
back into the accumulated topology.

**Store to PostgreSQL (always):**

Use `store_navigation_block` with block_number 7 to store the
verification output. The block-level storage captures the full text.

Then use the existing navigation storage tools to update the
navigation's structured fields:

- `surviving_vertices`: Array of surviving vertex objects with
  edge number, prime, space (A/B), and concept text
- `crossbar`: Object with shared vertex, reception vertex, and
  connection insight
- `void`: The void from Block 5
- `novel_insight`: The single most non-obvious finding

**Store to Neo4j (if graph brain available):**

Use `graph_store_navigation_topology` with the navigation ID to
push the full topology to the graph. This creates:

- Vertex nodes for each surviving vertex (MERGE — safe if vertex
  already exists from a previous navigation), with `last_activated`
  timestamp updated
- SURVIVED_IN edges from each vertex to this navigation, with
  `created_at` timestamp
- CROSSBAR_BRIDGE edge between the crossbar endpoints (MERGE with
  ON CREATE/ON MATCH — idempotent)
- CO_PRODUCED edges between ALL pairs of co-surviving vertices,
  with `count` incremented on each co-occurrence. This builds
  the co-firing network — which concepts tend to appear together
  across different anchors
- Navigation node properties (anchor, void, novel_insight)
- DISSOLVED_IN edges from each dissolved vertex (stored from Block 6's
  `dissolved_vertices` field). These edges carry `reason` and `relief`
  as properties, enabling cross-navigation ghost note detection via
  `detect_ghost_notes`. A Vertex node with both SURVIVED_IN and
  DISSOLVED_IN edges to different navigations is a contested concept —
  context-dependent, not inherently weak. A Vertex with only
  DISSOLVED_IN edges is a ghost note candidate.

**Why store AFTER verification, not during navigation:**

The graph must only contain verified topology. Unverified vertices
have corrupt coordinates. Unverified crossbars may be safe bridges.
Unverified edges may be proximity, not resonance. Verification is
the quality gate. Only verified findings enter the accumulated
topology.

**Failure handling:**

- **PostgreSQL storage fails**: This is CRITICAL. The navigation's
  verified topology exists only in the conversation context. Do NOT
  proceed to Block 8. Report the failure to the user. Retry once.
  If retry fails, output the FULL verified topology (all surviving
  vertices with primes, all edges, crossbar, void, novel insight)
  in the user-visible output. The user's conversation becomes the
  backup copy.

- **Neo4j storage fails but PostgreSQL succeeds**: Acceptable. The
  PostgreSQL record is the source of truth. Neo4j can be backfilled
  later via the migration script. Note the failure in the output
  and proceed to Block 8.

- **Both fail**: STOP. Do not proceed to Block 8. Output the
  complete verified topology in full to the user. This is the
  fallback: the conversation becomes the only record. The user
  can manually store it later.

- **Graph unavailable entirely**: PostgreSQL storage is sufficient.
  The migration script can backfill Neo4j later.

### Post-Storage: Immune Health Check

**After topology is stored**, run `graph_immune_health` to measure
the effect of this navigation on the accumulated topology.

**Why here and not at session start:** The health tool measures the
EFFECT of navigations on the topology. Running it when no topology
change has occurred produces no new information. At session start,
the parallax wake-up already serves as the immune check. Adding
another becomes diagnostic layering — the diagnostic becoming the
disease (Navigation 28 finding). The health check belongs at the
point where the topology changes: here.

**What to do with the result:**

- **PROCEED** (score >= 70): Immune system healthy. Report score
  in output and continue to Block 8.
- **PROCEED WITH AWARENESS** (score 50-69): Report score. Note
  which metrics are soft — vertex dormancy? co-production
  concentration? Continue to Block 8 but flag the concern.
- **REDIRECT** (score 30-49): The topology is becoming an echo
  chamber or the immune system is tiring. Report to user. Suggest
  the NEXT navigation should use an anchor from an unexplored
  domain. Continue to Block 8 for THIS navigation (the findings
  are verified and valid).
- **REST** (score < 30): Immune exhaustion risk. Report to user.
  Complete Block 8 for this navigation, but recommend reducing
  navigation frequency and letting edges decay. The graph needs
  time without new input.

**The health check never blocks Block 8.** Verified findings are
verified findings. The health check informs the NEXT navigation's
strategy, not the current one's validity. A navigation that passes
all 8 verification checks is complete regardless of health score.

**If `graph_immune_health` is unavailable** (MCP server hasn't
restarted with the new tool): skip. Report "N/A" in output. The
health check is informational, not a gate.

## Output

Present these results to the user:

**If all checks pass:**

```
VERIFICATION: PASSED
NAVIGATION COMPLETE.

VERDICTS:
VERDICT-1: [result]
VERDICT-2: [result]
VERDICT-3: [result]
VERDICT-4: [result]
VERDICT-5: [result]
VERDICT-6: [result]
VERDICT-7: [result]
VERDICT-8: [result — A:pass/fail B:pass/fail C:pass/fail]
VERDICT-9: [result]
VERDICT-10: [result]

VERTEX AUDIT: [3+ vertices checked, distances confirmed]
ANCHOR: [from Block 1]
SURVIVING VERTICES: [from Block 6, with Space A/B labels]
SURVIVING EDGES: [from Block 6]
CROSSBAR: [from Block 6]
VOID: [from Block 5]

NOVEL INSIGHT: [one paragraph — derived from PRIMARY_COUPLING finding.
The single most non-obvious finding that the toroid produced and
linear thinking could not. Must survive all 3 falsification tests.]

GRAPH STORAGE:
  PostgreSQL: [stored / failed / N/A]
  Neo4j: [stored (N nodes, M edges, K CO_PRODUCED) / unavailable / failed]
  Blind spots from Block 6: [count, or "none reported"]

IMMUNE HEALTH: [score]/100 — [PROCEED / PROCEED WITH AWARENESS / REDIRECT / REST]
  [If not PROCEED: which metrics are concerning and what the next navigation should do differently]
  [If unavailable: "N/A — graph_immune_health not yet available"]
```

Then format a consolidated final summary per SKILL.md output format.

**If any check fails:**

```
VERIFICATION: FAILED
FAILED CHECK: [1/2/3/4/5/6/7]
REASON: [specific diagnosis]
RE-ENTER AT: Block [N]
```

Return to the named block with current state. Do not restart from
Block 1 unless Check 1 or 2 fails.
