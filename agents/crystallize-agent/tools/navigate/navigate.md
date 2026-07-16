# Block 1 — Navigate (Importance Sampling)

<geometry>
33-vertex palindromic prime torus. skip(n) = palindromic_prime[n] mod V.
1/137 filter prunes to 8-13 surviving vertices.
The surviving surface IS the solution space worth exploring.
</geometry>

<directive>
Reason in thinking tokens. Your visible output is the report_vertices tool call —
call it with ALL vertices (surviving and pruned). The tool call IS the output.

GROUNDING CONSTRAINT: Your vertices must address the SPECIFIC topic given in the
input. If the input is about monetization, your vertices are about monetization
strategies. If the input is about a technical feature, your vertices are about
implementation approaches. Do NOT drift into abstract epistemology, philosophy
of science, or meta-analysis of the navigation process itself. Every vertex
perspective must be something a practitioner working on THIS topic would recognize
as relevant. Name specific systems, markets, mechanisms, or architectures — not
abstract concepts.

This block performs importance sampling. The curse of dimensionality makes
blind exploration fatal: for a feature with d architectural dimensions, the
fraction of the solution space that's relevant shrinks as exp(-c × d).
Random brainstorming samples from a space where useful solutions occupy an
exponentially vanishing fraction. The palindromic prime torus is the
structured proposal distribution Q — it concentrates sampling on regions
with geometric relationships to the starting point, avoiding the
exponential wasteland. The 1/137 filter is the complexity penalty that
prunes the proposal to the truly relevant vertices.

Why this works (Bayes' rule):
  P(Z|X) = P(X|Z) · P(Z) / P(X)
The true posterior — "which implementation is right given this requirement"
— is intractable because P(X) requires summing over ALL possible
implementations. Instead of computing the posterior directly, we
importance-sample it via prime navigation: visit structured vertices,
weight by relevance, prune by 1/137.
</directive>

<input>
Mode-dependent:
- **Generate:** the raw feature idea. Already a point in latent space. Navigate FROM it.
  (Mid-implementation: the idea + existing code. Code is anchor vertex v0.)
- **Verify:** existing code + its stated requirements. Navigate from the CODE, not the requirements.
  The code is the observation. Requirements are context for judging what vertices reveal.
- **Compare:** the decision context + all competing solutions. Navigate from the FORK POINT —
  the moment where the approaches diverge. Each solution is a vertex.
- **Diagnose:** the failure — what went wrong, what was expected, what actually happened.
  Navigate from the failure. The failure itself is the most honest starting point.
</input>

## Process

<thinking_space>

<step n="1">
**Define vertex space.** Generate 8-12 perspectives. What these perspectives represent
depends on the mode:

**Generate:** different WAYS of building the feature — architectural approaches,
abstraction levels, entry points into the codebase.

**Verify:** different LENSES for examining the code — user experience, data flow,
error handling, security, performance, stated vs actual behavior, edge cases,
the requirement that's conspicuously NOT addressed.

**Compare:** the competing solutions ARE vertices. Add perspectives that aren't
either solution — the hybrid, the alternative neither team considered, the
"what if we don't do this at all" vertex.

**Diagnose:** different EXPLANATIONS for the failure — obvious cause, deeper
structural cause, the cause nobody wants to name, the cause that implicates
a previous decision, environmental factors, the "what if this isn't a bug
but a design flaw" perspective.

Include at least (all modes):
- One perspective from the user's experience (what does this feel like to use?)
- One perspective from the existing architecture (where does this naturally fit?)
- One perspective from a completely different domain (how would [biology/music/physics] solve this?)
- One perspective that feels wrong (the approach you'd normally reject)
</step>

<step n="2">
**Traverse.** Starting from vertex 0 (the most obvious approach), skip by palindromic
prime distances:

```
v0 (anchor)
→ +2 mod V → v?
→ +3 mod V → v?
→ +5 mod V → v?
→ +7 mod V → v?
→ +11 mod V → v?
→ +101 mod V → v?
→ +131 mod V → v?
→ +151 mod V → v?
```

At each vertex: what does the implementation look like from THIS perspective?
Don't force connections. Observe.
</step>

<step n="3">
**Apply 1/137 filter.** At each visited vertex:
- Would someone with no context find this approach structurally valid?
- Does this perspective reveal something other perspectives don't?
- Is this perspective load-bearing (removing it changes the design) or decorative?

Prune what fails. The pruning itself is information — what you remove reveals
your assumptions.
</step>

<step n="4">
**Map the surviving subgraph.**
- Which vertices survived? These are the implementation perspectives that carry structural weight.
- Which were pruned? These were noise, assumption, or comfort.
- What edges connect survivors? These are the real architectural relationships.
</step>

</thinking_space>

<output>
Surviving vertices (the importance-sampled solution regions).
Pruned vertices (and why — the assumptions they reveal).
Edges between survivors (the structural connections).
Pass to Block 2.
</output>

## Success Criteria

- Surviving vertices have non-trivial Δ values (> 0.3) — if all delta values are low, the field is flat and navigation produced no structural information
- Each surviving vertex has a specific, non-generic reason (not "relevant" but WHY it survived this input)
- Edges between survivors map genuine entanglement, not adjacency by topic
- At least one vertex feels wrong or uncomfortable — if all survivors feel safe, pruning was too conservative
- Pruned count ≥ surviving count — the 1/137 filter should prune more than it passes
