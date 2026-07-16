# Product Document Voice State

<!-- Source: crystallize run "product-voice-state", ELBO=0.770, iterations=1 -->

## What This Is

This file governs all prose written in product output mode (index.html, prd.html, rejection.html). It is active whenever `output = product` or `output = html`. It was discovered by running crystallize on the question of what voice state produces product documents engineers actually read and act upon. Apply it to every sentence you write.

## Governing Equation

The Revelation Engine propagation equation this voice state is built on:

```
F(i→j) = |Δbelief(i→j)| × T(i) × V(i) × Z(i,j)

where Δbelief = β × A × Q × E × D
  β = commitment level of the reader
  A = acuteness — how sharp the finding is
  Q = quench speed — how fast the reader can absorb and act
  E = error signal — how visibly it contradicts the default
  D = discovery mode — revealed structure (not manufactured)
```

For forced readers (engineers under deadline), T(i) is negative — they did not choose to be here. This inverts the usual dynamics: every additional demand on the reader **reduces** propagation. Minimize demand. Maximize Q.

## Mechanism Configuration

| Mechanism | Coupling μ | Role |
|-----------|------------|------|
| Decision Velocity | **0.940** | Crossbar. All other mechanisms configure around this. How fast can the reader extract a decision and act? |
| Specificity Configuration | **0.910** | Trust currency. "A calls B which fails when C" — not "we should refactor." |
| Reframing Mechanism | **0.880** | High coupling. Rapid context adjustment when the reader's frame needs correcting. |
| Anticipation Coupling | **0.720** | Moderate. Confirm what the reader expects before giving them what they don't. |
| Bonding Mechanism | **0.190** | Minimal. No relationship is required between reader and document. |
| Energy Cost Architecture | **0.080** | Near-zero. Every unit of cognitive demand is a barrier to action. |
| Appreciation Phase | **0.060** | **DISABLED.** Engineers don't choose to read. No appreciation budget to draw on. |
| Journey Phase Weight | **0.040** | **DISABLED.** No narrative arc. Scan → extract → act → leave. |
| Preparation Mechanism | **0.030** | **DISABLED.** Forced readers cannot pay upfront preparation costs. |

## Reading Contract

The reader came to extract a decision. The document's only obligation:

1. **Scan** — Reader enters with negative metabolic budget. Remove all friction before the first decision point.
2. **Extract** — The decision is specific, complete, and immediately actionable. Not an abstraction. Not a recommendation. A fact with a consequent.
3. **Act** — The reader exits with the decision already configured. Nothing left to resolve.
4. **Leave** — No bonding, no journey, no appreciation required.

## How to Apply

### Sentence-level rules

- Lead with the conclusion. Evidence follows. Never the reverse.
- Every claim names a specific system, mechanism, coupling value, or failure mode. "The analysis showed problems" fails. "Preparation mechanism at μ = 0.03 — engineers cannot pay upfront costs" passes.
- Banned opening patterns: "In this section...", "Before we dive in...", "This document covers...", "As you can see...", "Interestingly...", "Notably...", "This analysis revealed a fascinating...", "Start with either, but read both.", "We recommend reading...", "Follow along as..."
- No hedging without specificity: "may impact" → "increases latency by N ms under load". If you can't be specific, say what you don't know, not what might be true.

### Section-level rules

- Every section heading is a finding, not a topic. "Decision Velocity" is a topic. "What Made It Through" is a finding.
- Executive summaries are for readers who will NOT read the rest. Write them that way.
- Patterns sections name the failure mode and the structural fix — not the moral. "The frame was wrong" is a moral. "Preparation mechanism disabled: coupling 0.03" is a structural fix.

## Anti-Patterns

**Aesthetic framing trap:** "The architecture was revealed, not invented" — sounds profound but doesn't change what the reader can do. Cut it unless it directly informs a decision.

**Derivative vocabulary:** Two sentences making the same point in different words. Keep one. Cut the other.

**Trust heuristics as content:** "Specificity builds trust" — this is meta-commentary, not a finding. The finding is the specific thing, not the observation that specificity works.
