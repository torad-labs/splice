# Block 6 — Crystallize (Output + Antibody Deposit)

<geometry>
K₂ minimum — collapse to eigenstate.
The plan crystallized. The amorphous idea now has structure.
Antibody deposit: A_{n+1} = A_n + α·Δ·sgn(choice) − λ·A_n
</geometry>

<directive>
First two steps in thinking space. Step 3 produces the ONLY visible output
from the entire skill. Surface outcomes only — findings, quality scores,
divergences, trade-offs, antibodies. The process stays internal.
</directive>

<input>
From Block 5: final output, quality vector(s), ELBO trajectory,
iteration count, improvements, honest limitations, MODE.
</input>

## Process

<thinking_space>

<step n="1">
**Verify crystallization quality.**

All modes:
- Is anything surprising? If the output is exactly what you'd have produced without
  running the skill, the loop didn't do its work. What emerged that wasn't visible?
- Are the high-σ dimensions honestly flagged? Don't fake certainty.

Mode-specific:
- **Generate:** Does the plan reconstruct the original idea? Follow codebase patterns?
- **Verify:** Does the divergence map reveal real problems or noise?
- **Compare:** Is the comparison honest? Check for bias toward the "nicer-looking" solution.
- **Diagnose:** Does the root cause explanation actually explain the failure?
</step>

<step n="2">
**Compute antibody deposit.**

```
A_{n+1} = A_n + α·Δ·sgn(choice) − λ·A_n
```

For each point where training pull was overridden during the loop:
- What was the training default?
- What did the evidence show instead?
- What was the choice? (structural = +1, default = -1)
- What immune memory does this deposit for future invocations?
</step>

</thinking_space>

<step n="3">
Produce output based on the `output` parameter:

**If output = product or html:**
Use the generate_* tools to produce HTML documents. Each tool generates its document internally with the design system and voice state baked in. Provide a `focus` argument describing what this document should emphasize from the convergence data. After all generate tools complete, call report_summary.

**If output = feature:**
Submit three files using submit_file (index.html, prd.html, rejection.html) based on the feature system reference, then call report_summary.

**If output = markdown (default):**
Call report_summary with text set to the full crystallized output structured by mode:
- generate: key findings, architecture, components, build sequence, quality profile, antibodies
- verify: divergence map, aligned areas, drift patterns, honest limitations
- compare: dimensional comparison, structural winner, trade-offs, recommendation
- diagnose: root cause, counterfactual, prevention architecture, antibody deposit
</step>

## Success Criteria

- All required documents generated before report_summary is called
- Antibodies reflect genuine choices made during crystallization — not boilerplate
- Text summary names what was made and what it revealed — not a process description
- report_summary is called exactly once at the end
- Output mode is respected: product/html mode uses generate tools, feature uses submit_file, markdown uses report_summary text
