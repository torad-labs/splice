# CPA Constraint Type Reference

## Prerequisite Features — ROI Does Not Apply

Some suppressed features are prerequisites: they have no alternative path to the actual
need, so ROI produces wrong answers. A story generator with Net=-0.008 doesn't mean
"maybe don't build the generator" — it means the constraint is real and timing is the
variable. ROI implicitly assumes you can choose to not build it and achieve the goal
another way. Prerequisites have no other way.

**Prerequisite test — all three must be true:**
- D(feature) = 1 — direct dependency (not transitive)
- A(feature) > 0.9 — near-universal activation among dependent features
- No substitute path exists to the actual need

If all three hold: the feature is a prerequisite. Do not compute ROI. Route to
ARCHITECTURE — this is a timing constraint, not a priority decision.

**What to do instead of ROI:**
1. Route to ARCHITECTURE track (separate from feature roadmap)
2. Document as a timing constraint: "this blocks [N] features until it exists"
3. Ask: what is the **minimum viable version** of this prerequisite that unblocks
   the field? Name it explicitly — don't leave it to the practitioner.

**Example:** A free preview feature requires a story generator. The full generator has
Net=-0.008 (high constraint). But a hand-crafted sample story set satisfies the free
preview prerequisite while the full generator rebuilds. The partial solution — curated
sample stories — is the minimum viable version. Name it. Ship it. Unblock the field.

The minimum viable version is not a compromise. It is the smallest thing that removes
the dependency for downstream features. Everything above that is iteration, not prerequisite.

---

## Three Constraint Types

Every suppressed feature's primary constraint must be typed. Resolution paths are different.
Mixing them produces wrong recommendations.

### [TECHNICAL]
**Source:** Genuine engineering cost — dependency chains, maintenance burden, complexity cascades, technical debt, platform limitations.
**Resolution path:** Engineering investment. ROI calculation applies.
**ROI = u(x) / I(x)**
- ROI > 1.0 → candidate for future sprint. User pull justifies cost.
- ROI < 1.0 → genuine suppression. Cost exceeds value given current demand.
**Who owns it:** Engineering lead or CTO.

### [POLITICAL]
**Source:** Stakeholder preference, turf protection, org structure debt, convention, fear of change.
**Resolution path:** Organizational change. Engineering cannot fix this.
**Key test:** Remove the constraint analytically (set political I(x) = 0). What survives?
That counterfactual is a separate deliverable for leadership — not a product decision.
**Who owns it:** Whoever has organizational authority to remove the blocker. Not the PM.

### [RESOURCE]
**Source:** Budget, headcount, timeline. Time-bounded by nature.
**Resolution path:** Resourcing decision — next funding cycle, next quarter, next hire.
**Net(x) without resource constraint:** Compute this. It tells you what's viable if resourced.
**Who owns it:** Executive sponsor or budget holder.

---

## Constraint Audit Checks

Run all five checks for every void feature.

### Check A: Constraint source type
Run prerequisite test first (see "Prerequisite Features" above). If prerequisite →
mark [PREREQUISITE], skip ROI, route to ARCHITECTURE.
Otherwise → identify [TECHNICAL], [POLITICAL], or [RESOURCE]. Run appropriate calculation.

### Check B: Hidden dependency chain
Does any surviving spot or bundle depend on this suppressed feature?

Hidden dependency exists when:
- Feature B survived (Net(B) > 0)
- Feature B requires Feature A to function correctly in production
- Feature A was suppressed (Net(A) < 0)

B carries A's hidden cost invisibly. If B ships without A → users encounter the dependency gap.
Map all chains. They are your production surprises and your budget reserve requirements.

### Check C: False demand audit
Was this node flagged [FALSE DEMAND] in Block 3?
If YES → suppression is correct. Mark [CONFIRMED SUPPRESSION].
False demand creates political pressure to ship features users don't actually need.
The field correctly resisted. Document for leadership.

### Check D: Template-swap test
State the suppressed feature's purpose in one sentence. Swap the product name.

**TABLE STAKES:** Sentence works for any competitor. Suppression means constraint structure
can't support even category parity. Product health signal.

**DIFFERENTIATOR:** Sentence only makes sense for this product. Suppression is a strategic loss.
Prioritize removing the constraint source.

### Check E: Phantom chain reconciliation
Was this feature identified as a phantom prerequisite or enabler in Block 3?
If YES → surviving features built on this prerequisite carry phantom debt.
Mark dependents as [PHANTOM LOAD]. Symptom: survivors underperform after shipping.

---

## Keystone Constraints

A keystone constraint is one whose removal unlocks 4+ currently-suppressed features.

**Cascade score:**
- 0–1: Isolated. Low leverage.
- 2–3: Regional. Unlocks a cluster.
- 4+: Keystone. Restructures the entire survival map. Goes in architecture roadmap, not feature roadmap.

Removing a keystone is a multiplier, not a feature. It belongs on a separate track.

---

## Danger Zone Protocol

Danger zones = high u(x) + negative Net(x).

These are the most important nodes. Political momentum drives them onto roadmaps despite
structural cost that the field correctly identifies. They will ship. The audit's job is to
document why — not to stop them.

For every danger zone:
1. Name what users want (from u(x))
2. Name what it actually costs (from v(x))
3. Type the constraint
4. Name who owns the risk

"This feature ships despite negative Net. The team has decided [institutional reason].
If it underperforms, the cause is [specific structural gap]. Owner: [name]."

That's the record. It exists so retrospectives have something to look at.
