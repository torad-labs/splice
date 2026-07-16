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

<eq id="regression_risk" dim="regression_probability">
P(regression) = Σ touched_surfaces × fragility(surface) × (1 − test_coverage(surface))
How likely is this change to break existing working behavior?
Isolated changes with good test coverage = low risk. Changes touching stable,
untested code paths = high risk. Score: 1 = isolated, 0 = high regression risk.
</eq>
