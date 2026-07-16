# CPA Survival Pattern Reference

## Four Pattern Types

### SPOT
**Field condition:** Net(x) > 0, surrounded by Net < 0 on all sides.
**What it means:** Feature survives independently. User demand exceeds constraint cost on its own.
**Decision:** Ship alone. MVP candidate. Prioritize by Net(x) value descending.
**PM failure mode:** Holding a spot waiting for other features. Don't. Spots are ready now.

**Competitor swap test on spots:**
- STRUCTURAL DIFFERENTIATOR: Feature only makes sense for your product. No competitor can replicate it from the same demand source. Highest-confidence ship signal.
- FIRST-MOVER BET: Competitor could ship this but hasn't. Timing bet, not structural bet. Document the bet explicitly — who's betting, on what timeline.
- PARITY REQUIRED: Competitor already shipped it. You're catching up. Different urgency — this is table stakes drag, not discovery.

### BUNDLE
**Field condition:** Multiple adjacent features all have Net(x) > 0, mutually reinforcing.
**What it means:** Features survive together because they share constraint load. Individually, each falls below threshold. Together, they sustain.
**Decision:** Ship the full bundle or hold all of it. Partial bundle shipping produces broken products.
**PM failure mode:** Shipping the "ready" bundle members because they look like spots. They're not. Test independence first.

**How to identify real bundles vs accidental adjacency:**
- Real bundle: removing one member causes surviving members to fall below Net > 0.
- Accidental adjacency: removing one member doesn't affect others' survival. Reclassify as independent spots.
- Test before reclassifying. Don't assume.

**Forward activation flag:** A spot today can become a bundle member when a future feature ships.
Mark these [FORWARD]. "Ships as spot now. Becomes bundle member when [X] is in scope."

### TANGLE
**Field condition:** Complex interconnected Net(x) > 0 regions with no clean boundary.
**What it means:** Problem is underspecified. Demand and constraint fields are entangled — no clean separable features.
**Decision:** Do not ship. Return to Block 1 and re-seed at higher specificity, OR flag for architecture review.
**What tangles reveal:** The product concept is the problem, not the features. You're trying to build before you've understood the shape.

### VOID
**Field condition:** Net(x) < 0 across a region.
**What it means:** Not failure. Constraint structure made visible.

Two void types with completely different strategic meanings:

**[GENUINE VOID]:** Constraint genuinely exceeds demand given current resources and architecture. The field is correct. These features are not wrong — the constraint is real and current. Document the constraint so future re-runs know what changed.

**[CONSTRAINT VOID]:** Suppressed by removable constraint — political org structure, fixable technical debt, or time-bounded resource gap. If the constraint source were removed, these features would survive. These belong in a future sprint roadmap, not a rejection log. Compute projected Net(x) assuming constraint removal.

---

## Failure Signals

These indicate bad parameters, not bad features. Fix the parameters, not the features.

| Signal | Cause | Fix |
|--------|-------|-----|
| All spots | Features are genuinely independent | Ship incrementally — this is correct |
| All bundles | Product is deeply interdependent | Ship as system — this is correct |
| All tangles | Problem too complex as stated | Re-seed at higher specificity |
| Uniform void | Dv too high — over-constraining | Lower Dv |
| Uniform survival | Dv too low or Du too high | Raise Dv |
| Small Δ on pattern | Parameters captured stakeholder preference | Reset from user evidence, return to Block 1 |

---

## Pattern Δ Interpretation

Δ(pattern) = |P_training(pattern) − P_evidence(pattern)| × C(pattern)

P_training: would a standard roadmapping process (user stories, stakeholder interviews, competitive analysis) reach the same spots, bundles, and void?

P_evidence: does the field produce decisions that contradict stakeholder assumptions?

**Small Δ:** Field confirmed consensus. Parameters likely biased by stakeholder preference. Useful validation — but not discovery. Investigate parameter honesty before claiming the result as a finding.

**Large Δ:** Field found structure standard processes miss. These are your real findings. The novel finding belongs in the SHIP/HOLD/KILL report and should pass the template-swap test.

---

## Shortcut vs Structural Survival

Not all surviving features survive for the same reason. Classify every spot and bundle member:

**USER-DRIVEN SURVIVAL:** u(x) built from behavioral evidence, user interviews, support data, churn signal. Survival is structural. The feature solves a real need.

**SHORTCUT SURVIVAL:** u(x) inflated by institutional gravity — executive mandate, competitive mimicry, single loud customer, convention. Feature survived because of political momentum, not demand propagation.

**Shortcut test — flag if 2 of 3 are true:**
1. No behavioral evidence independent of stakeholder input
2. Feature disappears from roadmap if loudest internal advocate leaves
3. Feature is on roadmap because a competitor shipped it

Shortcut survivors are not wrong. They are a different kind of bet. Document the bet before they ship.
