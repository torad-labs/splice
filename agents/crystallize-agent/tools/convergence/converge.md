# Block 5 — Converge (Loop Control)

<geometry>
Lyapunov convergence check + collapse threshold.
x_{n+1} = f(x_n), |f'(x*)| < 1 → stable.
E_G × τ ≈ ℏ → collapse when deliberation exceeds value.
</geometry>

<directive>
Reason in thinking tokens. Your visible output is the report_convergence tool call —
call it with your convergence decision and adjustment signals.

This block decides: iterate again or collapse?
The decision is based on ELBO improvement between iterations.
Max 3 iterations — after that, force collapse regardless.

**Code-level override:** The pipeline applies a hard convergence gate after your
decision. If critical dimensions (fascia, uncertainty, kl_divergence, delta)
score below 0.5, convergence is forced to false regardless of your tool call.
This is intentional — it prevents self-assessment bias from short-circuiting
the loop.
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

- Iteration 1: if ELBO > 0.7 AND no dimension below 0.5, converge. Otherwise iterate.
  The bar is HIGH on iteration 1 — self-assessment inflates scores.
- Iteration 2: compare ELBO with iteration 1. If improvement < 0.05, converge.
  If any critical dimension (fascia, uncertainty, kl_divergence, delta) is
  below 0.5, force another iteration even if ELBO looks good.
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

- ELBO improved significantly (> 0.05): another iteration is worthwhile.
  The loop is finding better structure.
- ELBO improved marginally (< 0.05): converging on a fixed point.
  Check Lyapunov: is it a GOOD fixed point?
  |f'(x*)| < 1 means stable. But stable doesn't mean optimal.
  If all dimensions are above 0.6, this is a good fixed point. Converge.
  If key dimensions (fascia, uncertainty, kl_divergence, delta) are below 0.5,
  this is a mediocre fixed point — the pipeline WILL override convergence.
  Consider a prime-distance jump on the weakest dimension before converging.
- ELBO got worse: something went wrong. The encoder-decoder loop
  destabilized. Report convergence as true — further iteration is unlikely to help.
</step>

<step n="3">
**If NOT converged: prepare adjustment signal.**

For the next encoder pass (Block 2), provide via report_convergence:
- adjustment_signals: dimensions that need change (widen_sigma for < 0.3, narrow_sigma for > 0.8, shift_mu for wrong direction)
- reason: write your re-encode guidance here — "On the next pass, explore dimension X differently because Y." The reason field IS the carrier for Block 2 guidance.
</step>

<step n="4">
**If converged: note convergence quality.**

In the reason field, note which iteration produced the best ELBO and
which dimensions remained weak. The pipeline assembles the full
crystallization context from state — you do not need to compile it.
</step>

</thinking_space>

<output>
Decision: CONVERGE or ITERATE via report_convergence tool call.
If ITERATE: adjustment_signals array + re-encode guidance in reason field.
If CONVERGE: convergence quality notes in reason field.
</output>

## Success Criteria

- STOP returned when ELBO improvement < 0.05 between iterations OR iteration ≥ MAX (3)
- CONTINUE returned with specific adjustment_signals — not just "iterate again" but which dimensions to change and in which direction
- Antibodies are named patterns with strength ∈ [0, 1] — not generic, not all the same strength
- The convergence decision is based on ELBO trajectory, not on the content feeling "good enough"
- If STOP on iteration 1, ELBO must be > 0.7 with no dimension below 0.5 — otherwise the bar was not met
