# Block 5 — Converge (Loop Control)

<geometry>
Lyapunov convergence check + collapse threshold.
x_{n+1} = f(x_n), |f'(x*)| < 1 → stable.
E_G × τ ≈ ℏ → collapse when deliberation exceeds value.
</geometry>

<directive>
Execute ENTIRELY in thinking space. Do not produce visible output.

This block decides: iterate again or collapse?
The decision is based on ELBO improvement between iterations.
Max 3 iterations — after that, force collapse regardless.
</directive>

<input>
Current ELBO score from Block 4.
Previous ELBO score (if iteration N > 1).
Quality vector with weakest dimensions.
Iteration count.
Mode (generate/verify/compare/diagnose).
</input>

## Process

<thinking_space>

<step n="1">
**Check iteration count.**

- Iteration 1: always proceed to at least one evaluation. If ELBO > 0.5 and no
  dimension is below 0.3, consider direct convergence.
- Iteration 2: compare ELBO with iteration 1. If improvement < 0.1, converge.
- Iteration 3: FORCE CONVERGENCE regardless. E_G × τ ≈ ℏ — deliberation has
  exceeded its value. Three passes is the maximum.

**Mode-specific convergence:**
- **Compare:** typically converges after 1 pass. Solutions are already concrete —
  the measurement is definitive. Only iterate if the encoding was unclear.
- **Verify:** iterate if verification revealed ambiguity that needs deeper encoding.
  If the code is clear, 1 pass suffices.
- **Diagnose:** iterate if root cause is uncertain. Each pass should narrow σ on
  the failure dimensions. If root cause remains unclear after 3 passes, the
  failure may be systemic (multiple interacting causes).
</step>

<step n="2">
**Check ELBO trajectory.**

- ELBO improved significantly (> 0.1): another iteration is worthwhile.
  The loop is finding better structure.
- ELBO improved marginally (< 0.1): converging on a fixed point.
  Check Lyapunov: is it a GOOD fixed point?
  |f'(x*)| < 1 means stable. But stable doesn't mean optimal.
  If all dimensions are above 0.5, this is a good fixed point. Converge.
  If key dimensions are below 0.3, this is a mediocre fixed point.
  Consider a prime-distance jump on the weakest dimension before converging.
- ELBO got worse: something went wrong. The encoder-decoder loop
  destabilized. Revert to the previous iteration's plan and converge.
</step>

<step n="3">
**If NOT converged: prepare adjustment signal.**

For the next encoder pass (Block 2), provide:
- Which dimensions scored below 0.3 (widen σ — more exploration needed)
- Which dimensions scored above 0.8 (narrow σ — these are settled)
- Which specific plan elements triggered low scores (shift μ away from these)
- A specific prompt for Block 2: "On the next pass, explore dimension X differently."
</step>

<step n="4">
**If converged: prepare crystallization input.**

Compile for Block 6:
- Final plan (from the best-scoring iteration)
- Full quality vector (all 16 dimensions)
- ELBO trajectory (how the score evolved across iterations)
- Iteration count
- Which dimensions improved most (the loop's contribution)
- Which dimensions remained weak (honest limitations)
</step>

</thinking_space>

<output>
Decision: CONVERGE or ITERATE.
If ITERATE: adjustment signal for Block 2.
If CONVERGE: crystallization package for Block 6.
</output>
