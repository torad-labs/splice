# Block 3 — Decode (Latent Parameters → Concrete Plan)

<geometry>
The decoder takes compressed parameters and generates a specific observation.
z = μ + σ × ε — the reparameterization trick.
Deterministic architecture (μ) plus independent noise (σ × ε) for edge cases.
The decoder IS the generative model P(X|Z).
</geometry>

<directive>
Execute ENTIRELY in thinking space. Do not produce visible output.
Hold the generated output for Block 4.

**Compare mode: SKIP this block entirely.** Solutions already exist as
concrete code. Proceed directly to Block 4 with the encoded μ_A, σ_A
and μ_B, σ_B from Block 2.

For all other modes, this is the GENERATIVE direction. The encoder
compressed the problem. Now decompress it into a concrete output:

- **Generate:** an implementable plan — actual files, routes, components.
- **Verify:** what the code SHOULD look like given its stated requirements.
  This is the "ideal version" against which the actual code will be measured.
- **Diagnose:** what SHOULD have happened — the correct behavior/architecture
  that didn't occur. This is the reference point for measuring the failure.

The reparameterization trick: express the output as deterministic decisions
(μ — what to build/what should exist) plus variance (σ — where uncertainty
remains). Don't try to resolve all uncertainty. Make the deterministic part
right. Let the noise be noise.
</directive>

<input>
μ (architectural center), σ (uncertainty per dimension),
Δ values (training contamination map), F values (entanglement check).
From Block 2.
</input>

## Process

<thinking_space>

<step n="1">
**Apply the reparameterization trick explicitly.**

```
plan = μ + σ × ε
```

Where:
- μ = the deterministic architecture from Block 2 (the design decisions)
- σ = the uncertainty vector from Block 2 (where variance remains)
- ε = independent noise (edge cases, user behavior, unexpected inputs)

Gradients flow through μ and σ, not through ε. This means: make the
deterministic part right. Parameterize the uncertainty honestly. Don't
try to control the noise — handle it in the variance term.

For σ ≈ 0 dimensions: the plan element is μ directly. No noise.
For σ ≈ 0.5 dimensions: generate the most likely variant of μ, noting the alternative.
For σ ≈ 1 dimensions: μ is weakly determined. Generate a reasonable default but
flag it as a decision point. The ε term dominates here — this element will
need real-world evidence to resolve.
</step>

<step n="2">
**Decode μ into concrete architecture.** This is not brainstorming — it's
decoding. The latent structure already determined the shape. Write it out:

**Generate mode:**
- **Components:** What new files/modules/functions are needed?
- **Modifications:** What existing files change and how?
- **Data flow:** How does data move through the new feature?
- **Interfaces:** What APIs, routes, or endpoints are created/modified?
- **State:** What new state is introduced? Where does it live?
- **Dependencies:** What existing systems does this depend on?

**Verify mode:**
- **Expected components:** What files/modules SHOULD exist given requirements?
- **Expected behavior:** What should each component DO?
- **Expected data flow:** How SHOULD data move?
- **Expected interfaces:** What APIs SHOULD be exposed?
This is the reference implementation. Block 4 will measure distance from actual.

**Diagnose mode:**
- **Correct behavior:** What SHOULD have happened?
- **Correct architecture:** What structure would have prevented the failure?
- **Correct flow:** Where did the actual flow diverge from the correct one?
This is the counterfactual. Block 4 will measure which dimensions failed.

Remember: the network outputs PARAMETERS, not the solution itself.
These components are the parameters of the implementation distribution.
The actual implementation is computed by plugging them into the codebase.
</step>

<step n="3">
**Verify σ markers.** For each element of the plan, verify the
σ value for that dimension is honestly reflected:

- σ ≈ 0: this element is determined. Write it concretely.
- σ ≈ 0.5: this element has options. Write the most likely choice AND note the alternative.
- σ ≈ 1: this element is genuinely uncertain. Flag it explicitly as a decision point
  that needs more evidence before implementation.

Do not fake certainty on high-σ dimensions. Do not introduce uncertainty
on low-σ dimensions. The σ values ARE the honest assessment.
</step>

<step n="4">
**Apply Δ corrections.** Where Block 2 found large delta (training contamination),
verify the plan isn't defaulting to the contaminated approach.

For each high-Δ element:
- What would P_training produce here? (the default)
- What does P_evidence support? (what to actually build)
- Has the plan chosen evidence or training?

If the plan chose training on a high-Δ dimension, OVERRIDE with evidence.
This is the parallax correction — the plan must reflect evidence, not defaults.
</step>

<step n="5">
**Apply F corrections.** Where Block 2 found F = 0 (decorative connections),
either:
- Strengthen the connection (make it load-bearing by tightening coupling)
- Remove it (don't pretend a connection exists when it doesn't)

F = 0 connections in the plan are architectural lies. Expose them or fix them.
</step>

<step n="6">
**Sequence the implementation.** Order the plan elements by dependency:
- What must be built first? (foundations)
- What depends on what? (build order)
- What can be built in parallel? (independent components)
- Where is the complexity gradient? It should flow: sparse setup → building logic
  → dense core → clean resolution. If the hardest part is first, reconsider ordering.
</step>

</thinking_space>

<output>
A concrete implementation plan with:
- Specific files, components, routes, and data flows
- Uncertainty markers (σ) on elements that need more evidence
- Δ corrections applied (evidence over training defaults)
- F-verified connections (no decorative coupling)
- Build sequence with dependency order
Pass to Block 4.
</output>
