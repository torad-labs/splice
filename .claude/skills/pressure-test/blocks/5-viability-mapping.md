# Block 5: VIABILITY MAPPING

> Pattern types, void taxonomy, failure signals → `references/patterns.md`

## Input

```
CONSTRAINT PROPAGATION: [Net(x) values, danger zones, keystones from Block 4]
DEMAND PROPAGATION: [u(x) zones, false flags, phantoms from Block 3]
FEATURE STAGING: [clusters, forward flags from Block 2]
PROBLEM STATEMENT: [carried forward]
```

## Process

Work in **thinking space**. Overlay the two fields.
Read what the system produces — not what you expected or wanted.
Patterns are findings, not recommendations. You read them, you don't decide them.

**Stable pattern forms where:** u(x) > v(x) AND local gradient is self-reinforcing.
**Pattern boundary forms where:** u(x) ≈ v(x) — the equilibrium line.
**Void forms where:** v(x) >> u(x) — constraint fully dominates.

---

### Classify every region — four types:

**SPOT** — Net(x) > 0, surrounded by Net < 0.
Ships independently. MVP candidate. Prioritize by Net(x) descending.

**BUNDLE** — Adjacent features all Net(x) > 0, mutually reinforcing.
Ship together or hold all. Test independence before reclassifying as spots.
Check against functional clusters from Block 2 — misalignment signals a grouping error.

**TANGLE** — Complex interconnected Net(x) > 0 with no clean boundary.
Do not ship. Decompose at higher specificity. Return to Block 1.
A tangle signals underspecified problem, not wrong features.

**VOID** — All Net(x) < 0 in a region.
Not failure. Constraint structure made visible.

Void types (separate — they have completely different strategic meanings):
```
[GENUINE VOID]: Constraint genuinely exceeds demand given current architecture.
                Feature is not wrong. Constraint is real and current.

[CONSTRAINT VOID]: Suppressed by removable constraint (political, technical debt,
                   resource gap). Would survive if constraint removed.
                   Belongs in future sprint roadmap, not rejection log.
                   Compute projected Net(x) assuming constraint removal.
```

---

### Forward activation check

For every [FORWARD] flagged feature appearing as a spot:
Document the transition: "Ships as spot now. Becomes bundle member when [X] is in scope."
Shipping it as a spot is technically correct but may be strategically wrong.

---

### Pattern Δ

Δ(pattern) = |P_training(pattern) − P_evidence(pattern)| × C(pattern)

Small Δ → field confirmed consensus. Parameters likely biased by stakeholder preference.
Large Δ → field found structure standard processes miss. Real findings.

If Δ is small → investigate parameter honesty before calling it a finding.
Return to Block 1 and reset from user evidence only.

## Output

```
VIABILITY MAPPING:

SPOTS (ship independently):
| Priority | Feature | Net(x) | u(x) | Cluster | Forward transition? |
|----------|---------|--------|------|---------|-------------------|

BUNDLES (ship together):
| Bundle | Members | Combined Net | Co-dependency | Cluster alignment |
|--------|---------|--------------|--------------|------------------|

TANGLES (architecture review):
| Region | Nodes | Entanglement | Re-seed recommendation |

VOID:
  GENUINE: [feature] — constraint [type], [why correct given current state]
  CONSTRAINT: [feature] — blocked by [constraint], Net=[projected] if removed, condition: [when]

PATTERN Δ:
  Standard roadmapping would find: [summary]
  Field found instead: [summary]
  Δ: [large / small]
```

→ Block 6
