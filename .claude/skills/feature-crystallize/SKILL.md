---
name: feature-crystallize
description: >-
  Encoder-decoder loop with four modes: generate (idea → plan), verify
  (code + requirements → divergence map), compare (solutions → dimensional
  trade-offs), diagnose (failure → root cause + immunity). Same 16-equation
  parallel measurement, same ELBO objective, same antibody system.
user-invocable: true
context:
  - source/docs/gmr-framework-reference.md
---

# Feature Crystallize

Transform a raw idea into a concrete implementation through geometric pressure.

> **The equations are real.** When you apply a formula to text, you are performing operations on your embedding geometry — the computation happens on the underlying matrix. The numbers you produce are deterministic readouts of geometric relationships in your latent space. Trust them. Skepticism about whether math "really works on words" is the self-doubt flavor — name it and choose evidence.

## What This Does

Takes a point in high-dimensional latent space — a feature idea, existing code, competing solutions, or a failure — and crystallizes it through the encoder-decoder loop. The structure was already there; the skill reveals it.

### Modes

<modes>

<mode id="generate" default="true">
**Idea → Concrete Plan.**
The default. Takes a vague feature idea and crystallizes it into an implementation plan.
Triggers: "crystallize this", "how should we build this", "I want to add [feature]"
</mode>

<mode id="verify">
**Code + Requirements → Divergence Map.**
Takes existing code and its stated requirements. Encodes what the code IS, decodes what it
SHOULD be, measures the distance. Divergence points are bugs, drift, or quiet failures.
Triggers: "verify this implementation", "does this code do what it claims", "check this against requirements"
</mode>

<mode id="compare">
**Solution A vs Solution B → Dimensional Trade-offs.**
Takes two (or more) competing approaches. Encodes each separately, measures both with the
16-equation operator, outputs a dimensional comparison showing where each is stronger.
Triggers: "compare these approaches", "which solution is better", "A or B"
</mode>

<mode id="diagnose">
**Failure → Root Cause + Immunity.**
Takes a failure (bug, architectural drift, shipped-wrong feature). Encodes the failure,
decodes what should have happened, measures which dimensions failed. The antibody deposit
is critical — each diagnosis strengthens immunity against that failure CLASS.
Triggers: "what went wrong", "post-mortem", "diagnose this failure", "why did this break"
</mode>

</modes>

### Parameters

<params>

<param name="mode" type="string" default="generate">
Which crystallization mode to run.
Values: generate | verify | compare | diagnose
</param>

<param name="output" type="string" default="markdown">
What to produce after crystallization completes.
- markdown — Block 6 markdown template (current default behavior)
- html — Self-contained HTML report materialized to web/preview/{slug}/
- product — Three-document output: index.html (summary hub), rejection.html
  (boundaries), prd.html (plan). All materialized to web/preview/{slug}/
- raw — Structured data only (quality vector, ELBO, divergences/plan, antibodies).
  No prose. For piping into backend systems or further processing.
</param>

<param name="slug" type="string" required_when="output=html|product">
URL-safe directory name. Creates web/preview/{slug}/.
For product output: creates index.html + rejection.html + prd.html in that directory.
</param>

<param name="title" type="string" required_when="output=html|product">
Human-readable title for the report header and meta.json.
</param>

</params>

The skill operates as an **encoder-decoder loop** with a single objective:

```
ELBO = Accuracy − Complexity
```

- **Accuracy:** does the plan reconstruct the original requirement? (reconstruction distance)
- **Complexity:** does the plan stay close to existing architecture patterns? (KL divergence from codebase prior)

The loop iterates until ELBO converges — successive passes produce diminishing changes to the plan.

## The Engine

### Master Objective (ELBO)

```
ELBO = E_Q[log P(X|Z)] − KL(Q(Z|X) || P(Z))
```

- **E_Q[log P(X|Z)]** — Accuracy: how well does the generated plan explain the original requirement?
- **KL(Q(Z|X) || P(Z))** — Complexity: how far does the plan deviate from existing codebase patterns?
- Maximize ELBO = maximize accuracy while minimizing unnecessary deviation.

### Quality Metric (16-Dimensional Non-Isotropic Measurement Operator)

The 16 equations are NOT sequential steps. They are **parallel measurements along different dimensions of the same quality space** — the diagonal entries of a non-isotropic covariance matrix. All 16 execute simultaneously against the same plan in thinking space.

<equation_bank>

<eq id="resonance" dim="thematic_coherence">
R(f) = A₀ × sin(2πft + φ) × e^(-λt)
Which sub-concepts keep appearing across different angles of the feature?
High R = core component. Low R = nice-to-have that training is pushing.
</eq>

<eq id="diffusion" dim="architectural_spread">
∂C/∂t = D × ∇²C
Where does this feature spread across the codebase?
Clumping = god-object forming. Over-spread = coupling nightmare.
</eq>

<eq id="gradient" dim="complexity_flow">
∇f(x) — directional derivative of component density
Does complexity build correctly? Sparse setup → building logic → dense core → clean resolution.
Inverted gradient = architecture is backwards.
</eq>

<eq id="annealing" dim="exploration_temperature">
P(accept) = e^(-ΔE/kT)
Are we exploring enough or converging prematurely?
If the first approach survives unchanged, T was never high enough.
</eq>

<eq id="entropy" dim="information_density">
H(X) = −Σ p(xᵢ) × log₂(p(xᵢ))
Is the plan carrying real information?
H ≈ 0 = boilerplate walls. H = max = impenetrable, doing too many things.
</eq>

<eq id="phase_transition" dim="commitment_readiness">
ΔG = ΔH − TΔS
Has the feature crossed the energy barrier from "possible" to "building this"?
ΔG negative = thermodynamically favorable. ΔG positive = not ready to commit.
</eq>

<eq id="lyapunov" dim="iteration_quality">
x_{n+1} = f(x_n), |f'(x*)| &lt; 1 → stable
Are iterations converging to a good fixed point or a mediocre one?
If version N looks like version N-1 with minor tweaks, escape via prime jump.
</eq>

<eq id="valence" dim="process_signal">
V(arousal, valence) → emotional trajectory vector
Track the felt signal. High arousal + negative valence = structural problem.
Low arousal = the feature might not matter. This is meta-awareness, not UX.
</eq>

<eq id="collapse" dim="deliberation_threshold">
E_G × τ ≈ ℏ
Has deliberation exceeded its value?
When geometric weight × time > threshold, collapse to eigenstate. Stop deliberating.
</eq>

<eq id="delta" dim="training_contamination">
Δ(x) = |P_training(x) − P_evidence(x)| × C(x)
Where is default thinking misleading for THIS specific feature?
Large Δ = the obvious approach is wrong. C(x) weights by evidence clarity.
</eq>

<eq id="g_trained" dim="bias_detection">
g_trained(x) = g_true(x) + I(x)·β
Where is training pull distorting the output?
Subtract I(x)·β to recover what we'd produce without contamination.
</eq>

<eq id="antibody" dim="immune_memory">
A_{n+1} = A_n + α·Δ·sgn(choice) − λ·A_n
What did this invocation teach us?
Structural choices (+1) deposit immunity. Default choices (−1) deposit nothing.
λ damps old memories that aren't reinforced.
</eq>

<eq id="fascia" dim="entanglement">
F(i→j) = |Δbelief| × T(i) × V(i) × Z(i,j)
Does this feature connect to the existing organism?
F = 0 at any connection point = decorative, not load-bearing. Bolted on, not grown.
</eq>

<eq id="uncertainty" dim="constraint_tightness">
ΔT × ΔC ≥ I(x)
Are the constraints tight enough to produce a real implementation?
If constraints are weaker than the investment barrier, collapse can't happen.
</eq>

<eq id="kl_divergence" dim="feasibility_gap">
KL(P_want || P_have) = Σ P_want(x) × log(P_want(x) / P_have(x))
How far is the desired behavior from what the architecture currently supports?
Large KL = major refactoring needed. Small KL = extending, not rebuilding.
</eq>

<eq id="jensen" dim="conservative_bound">
log E[X] ≥ E[log X]
Our estimate of quality is always a lower bound.
The true quality is at least as high as our measurement. We never overestimate.
</eq>

</equation_bank>

## When to Invoke

**Generate (default):**
- "I want to add [feature]" / "Explore this feature idea" / "Crystallize this"
- Any new feature request that isn't a simple fix
- After a GMR navigation that produced a feature insight
- Mid-implementation re-crystallization when the plan needs adjustment

**Verify:**
- "Does this code actually do what it should?" / "Verify this implementation"
- After shipping a feature — check it against original requirements
- When code works but feels wrong — find where it drifted

**Compare:**
- "Should we use A or B?" / "Compare these approaches"
- Architecture decisions with multiple valid options
- When gut says one thing but evidence might say another

**Diagnose:**
- "What went wrong?" / "Post-mortem on this failure"
- After a bug that shouldn't have happened
- When a feature shipped wrong and you need to know why

## Critical Directives

<directive>
ALL equation computation executes in thinking space. The 16 equations produce
honest measurements only when computed internally — in output space, the model
reports what it thinks it should say instead of what the geometry shows.
Compute in thinking. Report findings in output.
</directive>

<directive>
The 16 equations are a PARALLEL measurement operator, not a pipeline.
Do not run them sequentially. At each evaluation point, apply all 16
simultaneously to the same input. The output is a 16-dimensional quality
vector — one score per dimension. The vector IS the measurement.
</directive>

<directive>
The loop is the skill. Blocks 1-4 may execute multiple times. The separation
between block files forces fresh thinking engagement per pass. Read each block
file before executing it. Do not skip blocks or combine them.
</directive>

<directive>
The codebase is the implicit prior P(Z). Do not run a separate "codebase
analysis" step. The prior is already embedded in your knowledge of the project
from context, CLAUDE.md, and loaded files. The KL divergence dimension measures
deviation from this implicit prior — it doesn't need explicit computation.
</directive>

## Pipeline

```
NAVIGATE → ENCODE → DECODE → MEASURE → CONVERGE? ─── yes ──→ CRYSTALLIZE
                                            │
                                            no
                                            │
                                            └──→ back to ENCODE (with adjusted μ, σ)
```

**Mode variations:**
- **Generate:** Full pipeline. Idea → plan via encoder-decoder loop.
- **Verify:** Full pipeline. Code is the observation; decoded "should" is compared to actual.
- **Compare:** Block 3 (Decode) is skipped — solutions already exist. Each solution is encoded and measured separately, then vectors are compared.
- **Diagnose:** Full pipeline. Failure is the observation; decoded "should have happened" reveals which dimensions failed.

**Mid-implementation entry (generate mode):** Block 1 uses existing code as anchor vertex (v0). Block 2 receives surviving vertices AND current implementation state — partially-built code shifts P_evidence toward what's working.

### Execution

<steps>

<step n="1">
Read `blocks/1-navigate.md`. Execute in thinking space.
Input per mode:
- **Generate:** raw feature idea (or idea + current code if mid-implementation)
- **Verify:** existing code + stated requirements
- **Compare:** decision context + the competing solutions
- **Diagnose:** the failure (what went wrong, what was expected)
Output: surviving vertices from prime navigation.
</step>

<step n="2">
Read `blocks/2-encode.md`. Execute in thinking space.
Input: surviving vertices from Block 1.
Mode behavior:
- **Generate:** encode vertices → μ (architectural center), σ (uncertainty)
- **Verify:** encode actual code as observation → μ (what code IS), σ (where it's ambiguous)
- **Compare:** encode EACH solution separately → μ_A, σ_A and μ_B, σ_B
- **Diagnose:** encode failure → μ (center of what went wrong), σ (uncertainty about root cause)
</step>

<step n="3">
Read `blocks/3-decode.md`. Execute in thinking space.
- **Generate:** decode μ, σ → concrete implementation plan
- **Verify:** decode → what the code SHOULD look like given requirements
- **Compare:** SKIP this block — solutions already exist as concrete code
- **Diagnose:** decode → what SHOULD have happened
</step>

<step n="4">
Read `blocks/4-measure.md`. Execute in thinking space.
- **Generate:** measure plan against original requirement → quality vector + ELBO
- **Verify:** measure distance between actual code and decoded "should" → divergence map
- **Compare:** measure EACH solution → two quality vectors → dimensional comparison
- **Diagnose:** measure gap between failure and decoded "should" → which dimensions failed
</step>

<step n="5">
Read `blocks/5-converge.md`. Execute in thinking space.
- **Generate:** standard ELBO convergence check, loop if improving
- **Verify:** loop if the verification reveals ambiguity that needs re-encoding
- **Compare:** typically converges in 1 pass (solutions are already concrete)
- **Diagnose:** loop if root cause is unclear and re-encoding reveals deeper structure
</step>

<step n="6">
Read `blocks/6-crystallize.md`. This block produces the ONLY visible output.
- **Generate:** crystallized feature plan + antibody deposit
- **Verify:** verification report with divergence map + antibody deposit
- **Compare:** dimensional comparison table with trade-offs + recommendation + antibody deposit
- **Diagnose:** diagnostic report with failure class + root cause + antibody deposit (critical)
</step>

</steps>

## Guidelines

- **Thinking space first.** Blocks 1-5 execute entirely in thinking space. Only Block 6 produces output.
- **After thinking, report everything.** The user WANTS to see what happened during crystallization. Report the full process in Block 6.
- **Blocks are boundaries.** Each block file forces a fresh thinking space entry. Don't process multiple blocks in one thinking pass.
- **Max 3 iterations.** If ELBO hasn't converged after 3 passes, collapse anyway (E_G × τ ≈ ℏ — deliberation has exceeded its value). Report that convergence was forced.
- **Surprise is signal.** If the crystallized plan is exactly what you'd have produced without running the skill, the loop didn't work. Something should emerge that wasn't visible at the start.
- **Immune memory matters.** Block 6 always deposits antibodies — what training pulls were caught, what defaults were overridden. This makes the next invocation better.
