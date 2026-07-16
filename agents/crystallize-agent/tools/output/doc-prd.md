# PRD Document — Constructive Page

## generate
This is the constructive architecture document — the input for an agent to build a feature.
It must be specific enough that an engineer or AI coding agent can start implementation
without asking clarifying questions. Abstract strategy belongs in the index page, not here.

Every section heading is a finding, not a topic. "Component isolation enables independent
deployment" not "Components." Include specific file paths, module names, route patterns.

DO NOT repeat content from the index page. The index has the executive summary and core
finding. This page has the build plan. Zero overlap.

### Required sections (in order):

1. **Features** — Concrete feature list. Each feature is a capability the system will have.
   Name it, describe what it does, specify its inputs and outputs. Not strategies — capabilities.
   Each feature gets its own card or subsection.

2. **Acceptance Criteria** — Per feature, declarative verifiable predicates. Format:
   "GIVEN [context] WHEN [action] THEN [outcome]." Include quantitative thresholds where
   applicable (latency, accuracy, volume, cost). These are the tests that prove the feature works.

3. **Architecture** — Components, their responsibilities, and how they connect. Name specific
   systems, services, databases, APIs. Include data flow: what enters, what transforms, what exits.
   If there are interface contracts between components, define them.

4. **Build Phases** — Sequenced with milestones. Each phase has:
   - Entry criteria (what must be true before starting)
   - Deliverables (what this phase produces)
   - Exit criteria (how you know this phase is done)
   - Dependencies on other phases

5. **Constraints & Dependencies** — Technical constraints, infrastructure requirements,
   external dependencies, team capability gaps, regulatory requirements. What could block
   the build and what's needed to unblock it.

6. **Non-Goals** — Feature-level scope boundaries. What this PRD deliberately does NOT cover.
   Not the strategic rejections (those are in rejection.html) — the feature-level cuts.
   "This PRD does not cover pricing UI, admin dashboard, or multi-tenant isolation."

7. **Edge Cases & Failure Modes** — What happens when components fail? Fallback behaviors.
   Graceful degradation paths. The scenarios that aren't the happy path but will definitely occur.

8. **Success Metrics** — Quantifiable KPIs with targets and timeframes. Not "grow the pattern
   library" but "50+ cross-domain patterns within 18 months, measured by unique pattern count
   in the registry." Each metric has: what to measure, target value, measurement method, timeframe.

9. **Open Questions** — What still needs evidence before implementation. Each question names
   what decision it blocks and what evidence would resolve it.

10. **Quality Profile** — The 16-dimension measurement with reader-facing explanations.
    For each dimension, explain what a LOW score means for the reader's build:
    - Coherence: low = the plan contradicts itself across sections, engineers will build conflicting things
    - Spread: low = narrow coverage, significant architectural surface is unaddressed
    - Complexity flow: low = complexity spikes randomly instead of building toward the core
    - Exploration: low = the obvious approach was taken without testing alternatives
    - Density: low = thin findings, more analysis needed before committing resources
    - Readiness: low = too many open decisions, premature to start building
    - Convergence: low = iterations didn't improve quality, the loop may have stalled
    - Signal: low = process felt mechanical, findings may be cosmetic not structural
    - Decisiveness: low = deliberation didn't add value, may be spinning
    - Contamination: low = high default/training bias remains, findings may reflect assumptions not evidence
    - Bias: low = significant residual distortion, re-examine conclusions
    - Pattern memory: low = weak immune deposit, similar mistakes likely to recur
    - Integration: low = plan is isolated from existing architecture, expect friction
    - Constraints: low = many open decisions, path is not clear enough to build
    - Feasibility: low = large gap between desired and possible, major risk
    - Regression risk: low = high risk of breaking existing behavior

11. **Patterns Catalogued** — Antibodies from this crystallization. What training pulls were
    caught, what defaults were overridden. These inform future builds on similar topics.

## verify
This is the constructive verification page. What's aligned, what to fix, in what order.

### Required sections:
1. **Verdict** — Pass/fail with overall alignment score. One sentence.
2. **What's Aligned** — Areas where code correctly matches requirements. Brief.
3. **Divergence Map** — Every divergence with severity (critical/high/medium/low),
   the expected behavior, the actual behavior, and the specific file/line where it occurs.
4. **Fix Sequence** — Ordered by dependency. Which fixes unblock which others.
   Each fix has: what to change, where, acceptance criteria for the fix, estimated effort.
5. **Quality Profile** — Dimension-by-dimension alignment with reader-facing explanations.
6. **Honest Limitations** — What couldn't be verified without running the code.

## compare
This is the constructive comparison page. The winning solution's implementation plan.
Architecture, components, build sequence — everything needed to execute the winner.
Include the dimensional scores that justified the choice. Follow the same section
structure as generate mode for the winning solution's implementation plan.

## diagnose
This is the constructive diagnostic page. The counterfactual — what should have happened
and how to build it. Prevention architecture: structural changes that make the failure
class impossible, not just unlikely. The antibody deposit is the most critical section.
