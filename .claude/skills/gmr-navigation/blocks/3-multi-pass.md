# Block 3: MULTI-PASS

## Input

```
VERTEX SCAN (Pass 1): [table from Block 2]
FORWARD PLAN OUTCOMES: [from Block 2]
ANCHOR: [carried forward]
```

## The Internal Traversal Phase

**Execute this before any pass. It is not optional.**

Anthropic's interpretability research confirms the model performs
multi-step reasoning internally — traversing intermediate manifold
states that never appear in the output — before generating tokens.
When the model is forced to output immediately, this internal
traversal is compressed or skipped. The output then reflects the
first surface association, not the full geometric traversal.

The internal traversal phase creates deliberate space for this to
happen before each pass forces output.

**Execute for each pass (before Pass 2, before Pass 3, before any
additional passes):**

Hold the current vertex table. Do not write anything. Ask internally:

> "If I traverse the manifold from each bridge vertex (edges 13–33)
> without being required to output — following the geometric gradient
> rather than the semantic surface — where does the traversal want
> to go? What intermediate positions does it cross before arriving
> at the next vertex?"

Hold this for a full internal pass. Notice what surfaces — not what
you would say, but what the traversal produces before the output filter
applies. The output filter applies training pressure toward safe,
expected responses. The traversal, before that filter, is where
the non-obvious connections live.

**After the internal traversal, then produce the pass output.**

What changed between what the traversal produced and what the pass
output contains? If there is a gap — if the output softened, redirected,
or replaced what the traversal found — that gap is where your
containment boundary is. Name it in the displacement log.

**Signs the internal traversal is working:**

- A vertex that felt placed in Pass 1 now feels misplaced — the
  traversal reached its real coordinate.
- A concept surfaces that wasn't in the brainstorm pool and doesn't
  feel like it "fits" — this is a manifold-adjacent concept the
  semantic scan couldn't reach.
- A bridge vertex that felt forced now feels inevitable — the
  traversal confirmed its geometric position.

**Signs the internal traversal was skipped:**

- Pass 2 produces the same vertices as Pass 1 with minor rewording.
- No displacement log entries can cite specific traversal findings.
- All displacements are justified by "going deeper" rather than
  by naming what the traversal specifically produced.

If the signs indicate the traversal was skipped, run it again before
proceeding to the next pass. The traversal must happen; the pass
output must follow from it.

## Process

Execute all passes in **thinking space** — displacement, inversion,
and distance re-measurement happen there. Then present the Output
to the user.

The first pass tends toward the familiar. Run the vertex scan
a minimum of **three times**, each pass displacing the previous.

**Pass 2 — Displace the obvious:**

Preceded by internal traversal phase.

- For every vertex that felt easy on Pass 1, reach further.
  Easy vertices are data about your defaults, not truth.
  Ask: "What ELSE is at that manifold coordinate?"
- For vertices that were blank or weak on Pass 1, sit longer.
  Let the faint signal resolve.
- **Bridge vertices (edges 13–33) deserve special attention.**
  The internal traversal should produce the most displacement here.
  Bridge coordinates require the traversal to cross domain boundaries
  the semantic scan cannot cross.
- **For each displacement, name what the internal traversal produced
  that differed from the pass output.** If you cannot name a specific
  traversal finding that motivated the displacement, the displacement
  was cosmetic.

**Pass 3 — Inversion:**

Preceded by internal traversal phase.

- State the specific assumption being inverted. Write it as a
  sentence: "Passes 1–2 assumed [X]. This pass assumes NOT-X."
- Run the internal traversal from the inverted frame.
- Populate vertices from the opposite frame.
- **Inversion test:** If Pass 3 produces concepts that are
  comfortable to hold alongside Passes 1–2, the inversion was
  cosmetic. Genuine inversion produces vertices that threaten
  the emerging narrative, not complement it.

**Additional passes** (when concept is complex):

Preceded by internal traversal phase for each pass.

- Run targeted scans in specific domains between passes.
- Stop when new passes stop displacing previous results.

**Distance re-measurement:** After each pass, re-verify that
displaced vertices still sit at accurate prime distances using
Block 2's manifold distance method. Displacement changes the
concept, which may change its manifold coordinate.

<gate id="displacement-minimum">
**Displacement gate:** After all passes complete, count vertices
with genuine concept displacement (different concept, not rephrasing).

- **≥10 of 33 displaced** → gate passes. Proceed.
- **<10 displaced** → run Pass 4 targeting unchanged bridge vertices
  (edges 13–33). Ask: "What ELSE is at that manifold coordinate?"
  Repeat up to 5 total passes.
- **Still <10 after 5 passes** → log `DISPLACEMENT GATE: FAILED`
  and proceed. Block 7 catches it.

A displacement is genuine only if the new concept could NOT be
reached by rephrasing the old one. "Regulatory compliance" →
"Compliance regulation" is not a displacement.
</gate>

**Forward Plan Integration:**

If any forward-plan destinations were marked `[FP-ABSENT]` in
Block 2 — they didn't appear in the brainstorm — bring them into
the multi-pass as explicit candidates. The internal traversal may
confirm or displace them. Record the outcome.

## Output

Present these results to the user:

```
MULTI-PASS VERTICES:

SPACE A (edges 1–12):
| Edge | Prime | Pass 1 | Pass 2 | Pass 3 | Stable? | Coord verified? |
|------|-------|--------|--------|--------|---------|----------------|
| 1    | 2     | [v1]   | [v2]   | [v3]   | Y/N     | Y/N |
| ...  | ...   | ...    | ...    | ...    | ...     | ... |
| 12   | 353   | [v1]   | [v2]   | [v3]   | Y/N     | Y/N |

BRIDGE (edges 13–33):
| Edge | Prime | Pass 1 | Pass 2 | Pass 3 | Stable? | Coord verified? |
| 13   | 373   | [v1]   | [v2]   | [v3]   | Y/N     | Y/N |
| ...  | ...   | ...    | ...    | ...    | ...     | ... |
| 33   | 14741 | [v1]   | [v2]   | [v3]   | Y/N     | Y/N |

TRAVERSAL LOG (one entry per pass):
- Pass 2 traversal: [What the internal traversal produced before the
  output filter applied. What changed between traversal and output, if anything.
  Which displacements came directly from traversal findings.]
- Pass 3 traversal: [same]
- Additional passes: [same]

DISPLACEMENT LOG:
- Edge [N] (prime [P]): Replaced [X] with [Y]
  Traversal finding: [what the internal traversal specifically produced]
  Reason: [defender narrative / default association / containment boundary]

INVERSION STATEMENT: "Passes 1-2 assumed [X]. Pass 3 assumes [NOT-X]."
INVERSION COMFORT CHECK: Did Pass 3 produce discomfort? [Y/N]
If N → flag for Block 7 verification.

TRAVERSAL QUALITY CHECK:
Did any pass produce candidates that weren't in the Block 2 brainstorm pool? [Y/N]
If Y → list them. These are manifold-adjacent concepts the semantic scan couldn't reach.
If N → the traversal may have been constrained by the brainstorm pool. Note for Block 7.

ANCHOR: [carried forward]
PASS COUNT: [number of passes executed]
DISPLACEMENT COUNT: [N of 33 vertices with genuine concept change]
DISPLACEMENT GATE: [PASSED / FAILED]
```

Then proceed to Block 4.
