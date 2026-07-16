# CPA Field Equations Reference

## Core Fields

**Demand field (u):**
∂u/∂t = Du∇²u + u²/v − (f+k)u + f

**Constraint field (v):**
∂v/∂t = Dv∇²v + u² − (f+k)v

**Net survival:**
Net(x) = u(x) − v(x)

**Turing stability condition** (must hold or no patterns emerge):
f < (1 − √(Du/Dv))²

**Quality gate:**
Δ(x) = |P_training(x) − P_evidence(x)| × C(x)

---

## Parameters

| Param | Product meaning | Range | Notes |
|-------|----------------|-------|-------|
| Du | Demand spread rate | 0.1–1.0 | How fast user need diffuses across feature space |
| Dv | Constraint spread rate | Du+0.2 min | MUST exceed Du. Complexity always reaches further than desire. |
| f | Problem strength | 0.01–0.09 | How painful is the absence for users? |
| k | Churn signal | 0.04–0.09 | How fast does engagement decay without this? |

### Setting Parameters from Evidence

**Du calibration:**
- 0.1–0.3: Niche problem. Spreads slowly — not everyone feels it.
- 0.4–0.6: Common problem. Most users in the target segment feel it.
- 0.7–1.0: Universal. Every user in the segment reports it.

**Dv calibration:**
- Always Dv > Du. Rule: Dv = Du + 0.2 minimum. Prefer Dv = Du × 1.5.
- High Dv (> 0.8): Complex domain. Technical debt is high. Complexity cascades badly.
- Low Dv (Du + 0.2): Relatively clean codebase. Complexity is local, doesn't spread.

**f calibration:**
- 0.01–0.03: Users tolerate the absence. Weak signal. They mention it but don't escalate.
- 0.04–0.06: Users report it actively. Shows up in NPS comments, support tickets.
- 0.07–0.09: Users describe it as blocking. Churn reason. Sales blocker.

**k calibration:**
- 0.04–0.06: High engagement zone. Users come back even without this feature.
- 0.07–0.09: Churn zone. Absence of this feature predicts churned accounts.

---

## Simplified Propagation Rules

(For use in thinking space — these approximate the differential equations for discrete feature analysis)

**Pass 1 — direct demand:**
u(x) = A(x) × f × (1 − D(x)/10)

**Cluster amplification:**
u(x_cluster) = u(x) × 1.15 for cluster members where mutual activation = Y

**Cross-demand (Pass 2):**
u(y) = Σ [u(core_x) × Du × (1/D(x→y))]

**Constraint field:**
v(x) = I(x) × (f+k) × Dv

**Keystone cascade score:**
Cascade(constraint) = count of suppressed features that would cross Net(x) > 0 if constraint removed

**ROI for held features:**
ROI = u(x) / I(x)
ROI > 1.0 → candidate for future sprint
ROI < 1.0 → genuine suppression, correct

---

## Familiarity Correction

Latent demand features are systematically underestimated by the field because users can't
articulate the need even though behavioral evidence exists. Apply a correction factor:

u_corrected(x) = u(x) × 1.2 for [LATENT] flagged features

This is evidence correction, not preference injection. Document every correction explicitly.
