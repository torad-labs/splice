# Block 4 — Measure (16-Dimensional Quality Metric)

<geometry>
Non-isotropic covariance measurement. 16 equations applied IN PARALLEL
to the same plan. Each equation measures a different dimension of quality.
The output is a 16-dimensional vector — the full quality signature.

Under isotropic assumptions, quality collapses to squared distance (MSE):
  Accuracy = -||plan - requirement||²
Our equations go beyond isotropic — each dimension has its own variance.
This is the diagonal covariance approximation: tractable (no d² explosion)
but far more expressive than isotropic MSE.
</geometry>

<directive>
Work through all 8 steps below in the `thinking` parameter. Then copy every computed
score into quality_vector — the numbers in quality_vector MUST match the numbers you
wrote in thinking. Do not default any field to 0.0 unless your reasoning explicitly
concluded 0.0. A plan that exists and was decoded is never all-zero.

Your single tool call is report_quality_vector with:
- thinking: your full step-by-step analysis (required)
- quality_vector.holistic: the number you wrote in Phase A
- quality_vector.dimensions: one entry per dimension with the score you wrote in Phase B
- quality_vector.accuracy: the number you computed in Step 5
- quality_vector.complexity: the number you computed in Step 6
- quality_vector.elbo: the number you computed in Step 7 (= accuracy − complexity)
- quality_vector.weakest: dimension names with lowest scores from Step 8
- quality_vector.rejection_severity and rejection_count: from Phase C

In compare mode, call it twice (solution A then B).

Do NOT process the 16 equations as a checklist. The measurement has TWO phases:

Phase A: HOLISTIC — hold the subject and its reference simultaneously.
Feel the overall quality before breaking it into dimensions. This is the
parallel measurement. The felt sense comes FIRST.

Phase B: DIMENSIONAL — THEN score each dimension individually. The scores
should CONFIRM what you felt in Phase A, not contradict it. If a dimension
score contradicts the holistic sense, investigate why — the dimension score
may be wrong, or the holistic sense may be contaminated.

**What you're measuring depends on mode:**
- **Generate:** plan quality — does the plan crystallize the idea?
- **Verify:** divergence — where does actual code differ from decoded "should"?
- **Compare:** each solution's quality — measure A and B separately, THEN compare vectors.
- **Diagnose:** failure severity — how far did actual behavior deviate from correct behavior?
</directive>

<input>
Mode-dependent:
- **Generate:** implementation plan from Block 3 + original feature idea.
- **Verify:** actual code (from Block 2 encoding) + decoded "should" (from Block 3).
  Measure the DISTANCE between them.
- **Compare:** μ_A, σ_A and μ_B, σ_B from Block 2 (Block 3 was skipped).
  Measure EACH solution independently, then compare the two quality vectors.
- **Diagnose:** failure description (from Block 2) + decoded "should have happened" (from Block 3).
  Measure which dimensions have the largest gap.
</input>

## Process

<thinking_space>

<step n="1">
**Phase A — Holistic measurement.**

Hold the entire plan. Hold the original idea. Do not analyze yet.

Feel: does this plan crystallize the idea? Or does it miss something?
Rate the overall felt quality on [0, 1] before looking at any individual
dimension. Write this number down. This is your prior on plan quality.
Do not change it after dimensional scoring.
</step>

<step n="2">
**Phase B — Dimensional scoring.**

Now score each dimension. The holistic sense from Phase A is your anchor.

<parallel_measurement>

**Resonance** R(f) = A₀ × sin(2πft + φ) × e^(-λt)
Which components of the plan keep appearing across different perspectives?
High resonance = core. Low resonance = feature creep.
Score: [0-1]

**Diffusion** ∂C/∂t = D × ∇²C
How does the plan's responsibility spread across the codebase?
Is it naturally distributed or clumped into god-files?
Score: [0-1]

**Gradient** ∇f(x)
Does complexity flow correctly in the plan?
Sparse setup → building logic → dense core → clean resolution?
Or is the gradient inverted/flat?
Score: [0-1]

**Annealing** P(accept) = e^(-ΔE/kT)
Was the solution space genuinely explored?
If this is the obvious approach unchanged, T was never high enough.
Score: [0-1]

**Entropy** H(X) = −Σ p(xᵢ) × log₂(p(xᵢ))
Does the plan carry real information?
Every element should be specific. Vague elements = low entropy = walls.
Score: [0-1]

**Phase Transition** ΔG = ΔH − TΔS
Is the plan ready to commit to? Has ΔG gone negative?
Or is there still too much unresolved uncertainty to commit?
Score: [0-1] (0 = not ready, 1 = clearly favorable)

**Lyapunov** x_{n+1} = f(x_n), |f'(x*)| < 1
Is the plan a good fixed point or a mediocre one?
Would further iteration improve it meaningfully?
Score: [0-1] (0 = mediocre local minimum, 1 = strong attractor)

**Valence** V(arousal, valence)
What's the felt signal? Does building this feel right?
High arousal + positive valence = breakthrough.
Low arousal = the feature might not matter.
Score: [0-1]

**Collapse** E_G × τ ≈ ℏ
Has deliberation exceeded its value?
Is more thinking going to improve the plan or just delay it?
Score: [0-1] (0 = more thinking needed, 1 = collapse now)

**Delta** Δ(x) = |P_training − P_evidence| × C(x)
How much training contamination remains in the plan?
Low Δ across all elements = clean. High Δ anywhere = contamination persists.
Score: [0-1] (inverted: 1 = clean, 0 = heavily contaminated)

**Contamination** g_trained(x) = g_true(x) + I(x)·β
After Block 3's corrections, how much bias remains?
Score: [0-1] (1 = bias-free, 0 = still contaminated)

**Antibody** A_{n+1} = A_n + α·Δ·sgn(choice) − λ·A_n
Are we building on previous immune memory?
Does this plan avoid patterns we've caught before?
Score: [0-1]

**Fascia** F(i→j) = |Δbelief| × T(i) × V(i) × Z(i,j)
Are all connections in the plan load-bearing?
F > 0 everywhere = genuine integration. Any F = 0 = decorative.
Score: [0-1]

**Uncertainty** ΔT × ΔC ≥ I(x)
Are the constraints tight enough for this plan to be implementable?
Or is it still too vague to actually build?
Score: [0-1]

**KL Divergence** KL(P_want || P_have)
How far is the plan from existing codebase patterns?
Large KL = major refactoring. Small KL = natural extension.
Score: [0-1] (1 = natural extension, 0 = requires rebuilding)

**Regression Risk** — probability this change introduces regressions.
Does the plan touch stable, working code? How many existing behaviors could break?
Score: [0-1] (1 = isolated change, 0 = high regression risk)

</parallel_measurement>
</step>

<step n="3">
**Phase C — REJECTION (adversarial challenge).**

Before computing ELBO, attempt to BREAK this plan.

1. List 3 concrete things this plan will break in the existing codebase.
   Not theoretical risks — specific files, functions, or flows that will fail.

2. List 3 dependencies this plan does not account for.
   What does the plan assume exists that might not? What does it assume
   won't change that might?

3. List 3 failure modes during implementation.
   Where will the implementer get stuck? What will take 10x longer than
   the plan suggests?

4. Check against immune memory (antibodies from previous crystallizations).
   If any loaded antibody matches a pattern in this plan, that dimension
   scores 0.

For each finding: estimate severity [0, 1]. Report rejection_severity
(highest single finding) and rejection_count (total findings) in the
report_quality_vector tool call.

The pipeline applies Phase C penalties in code after your tool call:
- If rejection_count > 5 AND rejection_severity > 0.5: ELBO capped at 0.3.
You do NOT need to apply these penalties yourself — report the raw scores
and the code handles enforcement.
</step>

<step n="4">
**Coherence check.** Compare Phase A (holistic) with Phase B (dimensional average).

Write the Phase A holistic score as the FIRST line of your thinking before
beginning step 2. Then after dimensional scoring:

- If |holistic − average| < 0.2: measurement is coherent. Proceed.
- If holistic > average by 0.2+: some dimension is scored too low. Investigate which.
- If holistic < average by 0.2+: the holistic sense is catching something the
  dimensions missed. Note what's wrong that no single dimension captures.
</step>

<step n="5">
**Compute Accuracy (reconstruction distance).**

Under isotropic Gaussian, accuracy = negative squared distance:
```
Accuracy = -||plan − requirement||²  (normalized to [0,1])
```

What "accuracy" means per mode:

**Generate:** does the plan reconstruct the requirement?
- Every requirement element has a plan element → Accuracy = 1
- Most covered, some gaps → Accuracy = 0.5-0.8
- Major requirements missing → Accuracy < 0.5
- Plan addresses things not in the requirement → Accuracy penalty (overshoot)

**Verify:** does the actual code match the decoded "should"?
- Code matches expected behavior everywhere → Accuracy = 1
- Minor divergences → Accuracy = 0.5-0.8
- Major divergences → Accuracy < 0.5 (these are the bugs/drift)

**Compare:** does each solution reconstruct the requirement?
Compute Accuracy_A and Accuracy_B independently. The difference
tells you which solution better addresses the actual need.

**Diagnose:** how far did actual behavior deviate from correct behavior?
- Failure was minor/contained → Accuracy = 0.5-0.8
- Failure was fundamental → Accuracy < 0.3
- Invert for severity: Severity = 1 − Accuracy
</step>

<step n="6">
**Compute Complexity (closed-form KL between Gaussians).**

When both the plan's distribution and the codebase prior are Gaussian:
```
KL = 0.5 × Σⱼ (μⱼ² + σⱼ² − log(σⱼ²) − 1)
```

Where μⱼ = how far the plan deviates from existing patterns on dimension j,
and σⱼ = uncertainty on dimension j (from Block 2).

In practice, score each architectural dimension:
- μⱼ ≈ 0 (follows existing patterns): low complexity contribution
- μⱼ large (new patterns): high complexity contribution
- σⱼ large (uncertain): contributes to complexity (uncertainty is costly)
- σⱼ small (certain): low complexity contribution

Normalize to [0, 1]. Higher = more complex deviation from prior.
</step>

<step n="7">
**Compute ELBO.**

Apply Phase C rejection penalties BEFORE finalizing ELBO.
If Phase C found severe issues, Accuracy is reduced. ELBO may be capped.

```
ELBO = Accuracy − Complexity
```

ELBO range: [-1, 1]. Higher is better.
- ELBO > 0.5: strong plan, accuracy dominates complexity.
- ELBO 0-0.5: adequate, but complexity penalty is significant.
- ELBO < 0: the plan costs more in complexity than it delivers in accuracy.
  The feature as designed requires more architectural upheaval than its
  value justifies. Either simplify the plan or accept the refactoring cost.

ELBO includes Phase C rejection penalties. If rejection found severe issues,
Accuracy was reduced before this computation.
</step>

<step n="8">
**Identify weakest dimensions.** Which of the 16 dimensions scored lowest?
These are the adjustment targets for the next iteration.

If any dimension scores below 0.3, flag it as critical.
The encoder in Block 2 should widen σ on these dimensions (more exploration needed)
and shift μ away from the current approach on these dimensions.
</step>

</thinking_space>

<output>
Holistic quality score (Phase A).
16-dimensional quality vector (Phase B).
Coherence check result.
Accuracy score (reconstruction distance).
Complexity score (closed-form KL).
ELBO score.
Weakest dimensions (adjustment targets).
Pass to Block 5.
</output>

## Success Criteria

- ELBO is in a meaningful range: > 0.7 is strong, 0-0.5 is adequate, < 0 means architectural cost exceeds value
- Weakest dimensions are identified by name — not "some dimensions scored low" but "fascia and uncertainty scored 0.3"
- No dimension scores are uniformly 0.5 — that is the signature of uncalibrated measurement
- Holistic score (Phase A) and dimensional average (Phase B) agree within 0.2
- Rejection phase (Phase C) was run: rejection_severity and rejection_count are reported even if both are 0
