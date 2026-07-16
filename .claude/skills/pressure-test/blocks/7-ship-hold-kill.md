# Block 7: SHIP / HOLD / KILL REPORT

> Report HTML template → `assets/report-template.html`
> Pattern and competitor swap reference → `references/patterns.md`

## Input

All blocks carried forward.

## Process

Work in **thinking space**. Run all 10 checks before producing output.
Self-check: did you run the analysis, or perform running the analysis?

---

### 10 Verification Checks

**Check 1: Stability confirmed**
Did patterns actually emerge (spots or bundles)?
Uniform void → FAIL: Dv too high → Block 1.
Uniform survival → FAIL: Dv too low → Block 1.

**Check 2: Parameter honesty**
Any parameter set to favor a preferred feature?
Δ(parameter) = |P_training − P_evidence| × C
Stakeholder-biased Du → FAIL: reset from evidence → Block 1.

**Check 3: Spot independence is genuine**
Every spot surrounded by negative-Net features?
Spot touching positive-Net feature → reclassify as bundle member.
No genuine spots → this is a tangle product. Architecture review.

**Check 4: Bundles are complete**
Every bundle includes all dependent members?
Broken bundle (from Block 6) → resolve constraint OR test and confirm independence.

**Check 5: Danger zones addressed**
Every [DANGER ZONE] from Block 4 in the report with constraint type identified?
Unaddressed → FAIL → Block 6.

**Check 6: Hidden dependencies cleared**
Every hidden dependency chain from Block 6 annotated in surviving features?
Not surfaced here → surfaces in production.

**Check 7: Void is honest**
Void contains at least one feature stakeholders wanted but field correctly suppressed?
All suppressed features obviously wrong → Dv too high → Block 1.

**Check 8: Shortcut survivors declared**
Every [SHORTCUT SURVIVAL] from Block 6 appears in report with source named?
Undeclared → FAIL: ships as a fact when it's a bet.

**Check 9: Prerequisites accounted for**
Every [PREREQUISITE] from Block 6 appears in the ARCHITECTURE section with minimum
viable version named? Prerequisite without a named minimum viable version → FAIL → Block 6.

**Check 10: Competitor swap test — every spot**
State the spot's value in one sentence. Replace your product name with a competitor's.

```
STRUCTURAL DIFFERENTIATOR: Only makes sense for your product.
  Highest-confidence ship signal.

FIRST-MOVER BET: Competitor could ship this but hasn't. Timing bet.
  Document: who is betting, on what timeline.

PARITY REQUIRED: Competitor already shipped it. You're behind.
  Different urgency — table stakes drag, not discovery.
```

This separates "survived the field" (necessary) from "no one else can do this" (sufficient).

---

### Novel Finding

State the single most surprising result — what the field found that standard roadmapping
would have missed.

**Template-swap test on the novel finding:**
Swap the product name. Does it work for any competitor?
YES → g_trained. Restate as product-specific.
NO → it's a genuine finding. This is what the analysis was for.

---

### Store block before producing output

`store_navigation_block(anchor, block_number=7, block_name="SHIP/HOLD/KILL REPORT", output=...)`

---

### Output HTML Report

After verification passes: populate `assets/report-template.html` with findings.
Replace all `{{PLACEHOLDER}}` tokens. Save as `cpa-report-[slug].html`.

If generating text output instead:

```
━━━ SHIP ━━━

SHIP NOW (spots, by Net(x)):
| Priority | Feature | Net(x) | u(x) | Survival type | Competitor position |
|----------|---------|--------|------|--------------|---------------------|

SHIP TOGETHER (bundles):
| Bundle | Members | Co-dependency | Earliest ship condition |
|--------|---------|--------------|------------------------|

━━━ HOLD ━━━

HOLD — CONSTRAINT REMOVAL REQUIRED:
| Feature | Net(x) now | Blocking constraint | Type | Net(x) if removed | Keystone? |

HOLD — BROKEN BUNDLES:
| Bundle | Missing member | Condition to complete |

━━━ KILL ━━━

| Feature | u(x) | Demand source | Why |

━━━ WATCH ━━━

DANGER ZONES:
| Feature | u(x) | Net(x) | Constraint type | Risk | Owner |

SHORTCUT SURVIVORS:
| Feature | Source | Risk | Validation test |

━━━ ARCHITECTURE ━━━

PREREQUISITES (timing constraint — not priority decision):
| Feature | Minimum viable version | Unblocks | Timing constraint |

KEYSTONE CONSTRAINTS:
| Constraint | Cascade score | Features unlocked | Investment level |

TANGLES:
| Region | Nodes | Recommendation |

━━━ HIDDEN COSTS ━━━

HIDDEN DEPENDENCIES: [B depends on A — user impact if B ships without A]
PHANTOM LOAD: [suppressed A prerequisite for survivors — post-ship symptom]

━━━ FINDING ━━━

NOVEL FINDING: [template-swap verified, product-specific]

Δ(survival map):
  Standard roadmapping would have: [summary]
  Field found instead: [summary]
```

**If any check fails:**

```
REPORT: FAILED
FAILED CHECK: [1–10]
REASON: [specific]
RE-ENTER AT: Block [N]
```
