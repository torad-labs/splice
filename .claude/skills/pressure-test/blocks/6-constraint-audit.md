# Block 6: CONSTRAINT AUDIT

> Constraint types, audit checks, keystone protocol → `references/constraints.md`
> Shortcut survival test → `references/patterns.md`

## Input

```
VIABILITY MAPPING: [spots, bundles, tangles, void from Block 5]
CONSTRAINT PROPAGATION: [danger zones, constraint types, keystones from Block 4]
DEMAND PROPAGATION: [false demand flags, phantom chains from Block 3]
```

## Process

Work in **thinking space**. Run every check. Don't skip.

Two audits run in parallel:
1. **Void audit** — why suppressed features are suppressed, and whether that's correct
2. **Survival audit** — why surviving features survived, and whether that's structural

---

### Audit 1: Every void feature — five checks

See `references/constraints.md` for full check definitions.

**Check A: Constraint source type** [TECHNICAL / POLITICAL / RESOURCE]

Before ROI — prerequisite test:
- D(feature) = 1 (direct dependency)
- A(feature) > 0.9 (near-universal activation among dependents)
- No substitute path to the actual need exists

All three true → mark [PREREQUISITE]. Skip ROI (it assumes alternative paths exist —
prerequisites have none). Route to ARCHITECTURE. Move to Check B.

Otherwise → ROI = u(x) / I(x). If ROI > 1.0 → future sprint candidate.
If ROI < 1.0 → correct suppression.
Political constraint → compute counterfactual (what survives if removed). Deliverable for leadership.

**Check B: Hidden dependency chain**
Does any surviving spot or bundle depend on this suppressed feature?
B survived, B requires A, A is suppressed → B carries A's hidden cost invisibly.
Map all chains. They are your production surprises.

**Check C: False demand audit**
Flagged [FALSE DEMAND] in Block 3? → [CONFIRMED SUPPRESSION]. Document for leadership.
The field correctly resisted political pressure to ship features users don't actually need.

**Check D: Template-swap test**
State the suppressed feature's purpose in one sentence. Swap the product name.
Works for any competitor → [TABLE STAKES]: can't support even baseline parity. Health signal.
Specific to your product → [DIFFERENTIATOR]: strategic loss. Prioritize removing constraint.

**Check E: Phantom chain reconciliation**
Identified as phantom prerequisite or enabler in Block 3?
YES → surviving features built on this prerequisite carry phantom debt. Mark [PHANTOM LOAD].
Symptom: survivors underperform after shipping without explanation.

---

### Audit 2: Every surviving feature — shortcut test

Not all surviving features survived for the same reason.

**USER-DRIVEN SURVIVAL:** u(x) built from behavioral evidence, interviews, support data,
churn signal. Structural survival. The feature solves a real need.

**SHORTCUT SURVIVAL:** u(x) inflated by institutional gravity — executive mandate,
competitive mimicry, single loud customer, convention.

Shortcut test — flag if 2 of 3 apply:
1. No behavioral evidence independent of stakeholder input
2. Feature disappears from roadmap if loudest advocate leaves
3. On roadmap because a competitor shipped it

Shortcut survivors are not wrong. They are a different kind of bet.
The audit's job is to document the bet before it ships as a fact.

---

### Bundle completeness check

For every bundle from Block 5: are all members present in the survival map?
Suppressed bundle member → entire bundle is [BROKEN BUNDLE].
Decision: resolve constraint, OR confirm independence (test first — don't assume).

## Output

```
CONSTRAINT AUDIT:

PREREQUISITES (ROI does not apply — no alternative path):
| Feature | Why prerequisite | Minimum viable version | Unblocks |
|---------|-----------------|----------------------|----------|

VOID AUDIT:
| Feature | u(x) | Constraint type | ROI | Hidden dependency? | False? | Swap test | Phantom load? |
|---------|------|----------------|-----|-------------------|--------|-----------|---------------|

HIDDEN DEPENDENCY MAP:
- [surviving B] depends on [suppressed A]
  What users encounter: [specific UX failure]
  Budget signal: [rough cost to surface]

SHORTCUT AUDIT (survivors):
| Feature | Net(x) | Survival type | Institutional source | Risk if source removed | Validation test |
|---------|--------|--------------|---------------------|----------------------|-----------------|

POLITICAL COUNTERFACTUAL:
  Remove political I(x) from: [constraints]
  Features that would survive: [list]
  [Separate deliverable for leadership]

CONFIRMED SUPPRESSIONS (false demand, correctly suppressed):
- [feature]: [why demand was not real]

PHANTOM LOAD:
- [suppressed A] prerequisite for [survivors B, C]
  Post-ship symptom: [what users experience]

BROKEN BUNDLES:
- Bundle [name]: [suppressed member] Net=[val]
  Shipping incomplete: [specific UX failure]
  Resolution: [fix constraint / confirm independence]
```

→ Block 7
