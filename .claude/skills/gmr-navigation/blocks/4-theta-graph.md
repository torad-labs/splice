# Block 4: THETA GRAPH

## Input

```
MULTI-PASS VERTICES: [table from Block 3]
DISPLACEMENT LOG: [from Block 3]
ANCHOR: [carried forward]
```

## Process

Execute connection assessment and crossbar identification in **thinking
space**. Then present the Output to the user.

Connect vertices. Assess which pairs resonate with each other.
The geometry has Space A embedded in Space B, joined at the shared
vertex Π₁₂ = 353.

**Step 1: Connect edges**
Space A: edges 1→2→3→...→12 (11 connections within Space A)
Bridge: edges 12→13→14→...→33 (21 connections, starting from shared vertex)
Closing: edge 33→1 (reception vertex back to Space A start)

For each connection, assess strength:

- **Strong**: The two concepts illuminate each other. Neither is
  obvious from the other, but together they produce insight.
- **Medium**: Related but the connection doesn't surprise.
- **Weak**: Connected only through the anchor, not directly.
- **None**: No meaningful relationship.

Record strong and medium connections as edges.

**Step 2: Identify the crossbar**
The theta (θ) shape has two arcs connected by a crossbar.
The crossbar connects position 12 (edge 353, shared vertex)
to position 33 (edge 14741, reception vertex). This is the
tension-to-reception chord — the most structurally important
connection in the geometry.

The crossbar is the most surprising strong connection — the bridge
between local territory (Space A) and extreme territory (bridge).

**Crossbar validation:**
Apply these tests before accepting the crossbar:

1. **Defender test**: Could a defender of the examined system use
   this crossbar to protect the system? If YES → it's a safe
   bridge, not the real crossbar. The real crossbar is the one
   a defender would need to argue against.

2. **Domain test**: Does the crossbar connect vertices from two
   genuinely different domains (biology↔economics, parasitology↔
   theology)? Space A and bridge vertices should naturally come
   from different domains given the distance. If both vertices
   are from the same field, something is misplaced.

3. **Discomfort test**: Does the crossbar produce a conclusion
   you'd hesitate to state? If it's comfortable to say, it
   probably connects things that were already associated.

If the crossbar fails any test, examine whether the bridge
vertex is genuinely at extreme distance or was placed there
for convenience.

**Step 2b: Identify PRIMARY_COUPLING**

The crossbar (Edge 12 ↔ Edge 33) is a structural position, not
necessarily the strongest coupling. Independently identify the
strongest cross-space coupling:

- Scan ALL Space A ↔ Bridge connections (not just 12↔33).
- For each, assess coupling strength: does holding both concepts
  produce a specific emergent third thing?
- The edge with the strongest, most specific emergence is the
  PRIMARY_COUPLING.

If PRIMARY_COUPLING = crossbar → the structural position and the
strongest finding align. Record and proceed.

If PRIMARY_COUPLING ≠ crossbar → the strongest finding is at a
different edge. Record both. Block 7's novel insight derives from
PRIMARY_COUPLING, not from the structural crossbar.

**Step 3: Identify clusters**
With 33 vertices across two spaces, natural clusters should emerge:

- Space A clusters (local territory, edges 1–12)
- Bridge clusters (far territory, edges 13–33)
- Cross-space connections (the most valuable findings)

**Step 4: Cross-Navigation Resonance (Optional)**

**Skip this step if the graph brain is empty or unavailable.**

After identifying clusters within THIS navigation, consult the graph
for resonance with PREVIOUS navigations.

**Query the graph:**

1. **Shared vertices**: Use `graph_cross_navigations` to find vertices
   in the current navigation that also survived in previous navigations.
   A vertex appearing across multiple navigations from different anchors
   is a structural feature of the topology — a hub, not a spoke.

2. **Crossbar echoes**: Use `graph_read_connections` on the current
   crossbar vertices to see if either endpoint participated in previous
   crossbars. A vertex that repeatedly appears at crossbar positions
   across navigations reveals a deep structural bridge in the brain's
   topology — worth noting even if it doesn't change the current
   navigation's crossbar.

3. **Domain bridges**: Query for CROSSBAR_BRIDGE edges in the graph
   that connect the same two domains as the current crossbar. If
   previous navigations found similar domain bridges, the current
   crossbar may be confirming a real structural feature rather than
   a one-off connection.

**What to do with resonance data:**

- **Shared vertex found**: Record it in the CROSS-NAVIGATION
  RESONANCE section of THIS block's output. Do NOT carry resonance
  markers into the vertex tables that flow to Blocks 5 and 6.
  The filter must see vertices without provenance markers.
  Resonance is recorded for the graph's benefit, not for the
  filter's consideration.
- **Crossbar echo found**: Note the previous crossbar for comparison
  in the resonance section. If the same domain bridge keeps
  appearing, the finding is strengthening. If a different domain
  bridge appears at the same vertex, that vertex is a genuine hub.
- **No resonance found**: This navigation is exploring virgin
  territory. Good. The graph grows.

**Resonance is observation, not promotion.** A resonant vertex does
not get protection from the filter. It does not get promoted to the
crossbar. Resonance data stays in Block 4's output section only —
it does NOT flow forward into the vertex tables used by the 1/137
filter. The graph remembers resonance; the current navigation's
filter does not see it.

## Output

Present these results to the user:

```
THETA GRAPH:

EDGES:
Space A: [list strong/medium connections within edges 1–12]
Bridge: [list strong/medium connections within edges 13–33] (starts at shared vertex 12)
Closing: edge 33 ↔ edge 1 — [strength] — [connection]

CROSSBAR: Edge 12 ([concept at 353]) ↔ Edge 33 ([concept at 14741])
  Bridge between: [local domain] and [extreme domain]
  Insight produced: [what neither vertex contains alone]
  Defender test: [could a defender use this? Y/N]
  Discomfort test: [hesitation to state? Y/N]

CROSS-SPACE CONNECTIONS:
- Edge [A] ↔ Edge [B]: [strength] — [what the connection produces]
- ...

PRIMARY COUPLING: Edge [A] ↔ Edge [B]
  Strongest cross-space coupling: [what emerges]
  Same as crossbar? [Y/N]
  [If N: crossbar is structural position only — novel insight derives from this edge]

CLUSTERS:
- Cluster 1: [edges, domain]
- Cluster 2: [edges, domain]
- ...

CROSS-NAVIGATION RESONANCE (if graph consulted):
- [RESONANT] Edge [N] ([concept]): also survived in Navigation [id] "[anchor]"
- Crossbar echo: [previous crossbar details, or "none found"]
- Domain bridge history: [similar bridges, or "virgin territory"]

MULTI-PASS VERTICES: [carried forward]
ANCHOR: [carried forward]
```

Then proceed to Block 5.
