# Block 2 — Encode (Observation → Latent Parameters)

<geometry>
The encoder maps high-dimensional observations to a compressed latent representation.
Output: μ (best estimate of WHERE in solution space) and σ (uncertainty per dimension).
The encoder IS the recognition model Q(Z|X).
</geometry>

<directive>
Execute ENTIRELY in thinking space. Do not produce visible output.
Hold μ and σ for Block 3.

This block takes the surviving vertices from Block 1 and compresses them
into latent parameters. Each surviving vertex contributes evidence about
WHERE the implementation should be and HOW CERTAIN we are about each
architectural dimension.

On iterations 2+: this block receives the quality vector from Block 4
as an adjustment signal. Use it to shift μ toward higher-quality regions
and narrow σ on dimensions that scored well (more confident) or widen σ
on dimensions that scored poorly (less confident, need more exploration).
</directive>

<input>
First iteration: surviving vertices from Block 1.
Subsequent iterations: surviving vertices + quality vector from Block 4.

Mode-specific inputs:
- **Generate (mid-implementation):** surviving vertices + current implementation state.
  The existing code IS evidence — it shifts P_evidence toward what's already functioning.
- **Verify:** surviving vertices + the actual code + stated requirements.
  Encode what the code IS (μ) and where it's ambiguous (σ).
- **Compare:** surviving vertices + each competing solution.
  Encode EACH solution separately: μ_A, σ_A and μ_B, σ_B.
- **Diagnose:** surviving vertices + the failure description + expected behavior.
  Encode the failure (μ = center of what went wrong, σ = uncertainty about root cause).
</input>

## Process

<thinking_space>

<step n="0" condition="mid-implementation only">
**Inventory existing implementation.**

If re-crystallizing a partially-built feature, read the current code FIRST.
Before computing any delta, ground yourself in what already exists:
- What files/components have been created?
- What works? What's broken? What's half-built?
- Where did the original plan succeed? Where did it drift?

The existing code has maximum evidence clarity (C(x) ≈ 1) — it's not
theoretical, it's running (or failing). This shifts P_evidence heavily.
Plans that discard functioning code without cause pay a large complexity
penalty (high KL divergence from the working-code prior).
</step>

<step n="1">
**Compute delta at each surviving vertex.**

For each surviving perspective from Block 1, apply:
```
Δ(x) = |P_training(x) − P_evidence(x)| × C(x)
```

- P_training: what is the DEFAULT implementation this perspective suggests?
  The first thing training reaches for.
- P_evidence: what does the actual codebase, architecture, and requirements
  ACTUALLY support from this perspective? (If mid-implementation: existing
  working code is strong evidence. Weight it accordingly.)
- C(x): how clear is the evidence? (0 = ambiguous, 1 = crystal clear)

Feel P_training first — the comfortable answer. Then feel P_evidence —
what the evidence actually shows. The gap is honest only when measured
without output pressure.
</step>

<step n="2">
**Detect contamination at each vertex.**

```
g_trained(x) = g_true(x) + I(x)·β
```

Where is training pull distorting the implementation at this vertex?
I(x) = how much has been invested in the default approach already.
β = emotional coupling to that investment.
Subtract I(x)·β to see what you'd ACTUALLY build without bias.
</step>

<step n="3">
**Synthesize μ — the mean of the solution distribution.**

What μ represents depends on the mode:

**Generate:** the CENTRAL implementation across all surviving vertices.
Not the average (averaging architectures produces mush). The CENTER —
the point that minimizes total delta across all vertices.

**Verify:** what the code IS — its actual behavior, actual architecture,
actual data flow. Not what it claims to be. What it IS.

**Compare:** encode EACH solution into its own μ. μ_A = what solution A
actually proposes. μ_B = what solution B actually proposes. Do not blend them.

**Diagnose:** the CENTER of the failure — what went wrong, structurally.
Not the symptom. The architectural description of the failure state.

μ should be concrete:
- What components
- What data flow
- What interfaces
- What files/modules involved
</step>

<step n="4">
**Synthesize σ — uncertainty per dimension.**

For each architectural dimension (components, data flow, interfaces, storage,
auth, routing, state management, error handling), rate confidence:

- σ ≈ 0 (certain): evidence is clear, all vertices agree, existing patterns apply
- σ ≈ 0.5 (moderate): some vertices disagree, multiple valid approaches
- σ ≈ 1 (uncertain): no clear signal, need more evidence or exploration

High σ dimensions are where the INTERESTING decisions live. Low σ dimensions
are already determined by the codebase prior.
</step>

<step n="5">
**Check fascia — entanglement with existing organism.**

```
F(i→j) = |Δbelief| × T(i) × V(i) × Z(i,j)
```

For each connection between the proposed feature and existing architecture:
- Δbelief: does this connection change how the existing system behaves?
- T(i): is the existing component trustworthy (well-tested, stable)?
- V(i): is the connection point exposed (public API) or internal?
- Z(i,j): how tight is the coupling?

F = 0 at any point → that connection is decorative. The feature is bolted on, not grown.
All F > 0 → the feature genuinely integrates.
</step>

</thinking_space>

<output>
μ: concrete architectural description (the center of the solution distribution).
σ: uncertainty vector per architectural dimension.
Δ values: where training pull was strongest.
F values: which connections to existing architecture are load-bearing.
Pass to Block 3.
</output>
