---
name: pressure-test
description: >-
  Feature validation methodology that runs demand and constraint fields
  simultaneously across a feature space. What survives under competitive pressure
  has structural right to exist. What doesn't reveals constraint structure, not
  missing ambition. Use this skill whenever the user wants to validate a feature set,
  prioritize or pressure-test a roadmap, audit features already shipped, or find what's
  structurally missing from a product. Trigger phrases: "what should we ship", "pressure
  test this roadmap", "validate this feature", "what features survive", "competitive
  pressure analysis", "CPA", "what are we missing in our product", "help us decide what
  to build next", "which of these features actually matters", "is our roadmap right".
  Also trigger when someone describes a team disagreement about what to build and wants
  a framework to cut through it — even if they don't use any of these exact words.
---

# Competitive Pressure Analysis (CPA)

Validates features through demand and constraint field dynamics derived from reaction-diffusion
mathematics. The field doesn't care about your roadmap. It reports what users actually pull
toward versus what your current constraint structure can support. The gap is your roadmap.

## Quick Reference

**Four survival patterns:**
- **Spot** → ships independently, MVP candidate
- **Bundle** → ships together or not at all
- **Tangle** → architecture review before any implementation
- **Void** → constraint structure made visible (not rejection)

**Five decision outputs:**
- **SHIP** — spots and complete bundles
- **HOLD** — constraint void and broken bundles
- **KILL** — confirmed suppressions (false demand, correctly suppressed)
- **WATCH** — danger zones and shortcut survivors (ship anyway, risk documented)
- **ARCHITECTURE** — keystone constraints whose removal unlocks cascades

## Files

Blocks execute in order. References load on demand when blocks point to them.

| File | Purpose |
|------|---------|
| [`blocks/1-problem-statement.md`](blocks/1-problem-statement.md) | Problem scoping and parameter elicitation |
| [`blocks/2-feature-staging.md`](blocks/2-feature-staging.md) | Feature classification and D/A assignment |
| [`blocks/3-demand-propagation.md`](blocks/3-demand-propagation.md) | Turing field propagation for demand |
| [`blocks/4-constraint-propagation.md`](blocks/4-constraint-propagation.md) | Turing field propagation for constraints |
| [`blocks/5-viability-mapping.md`](blocks/5-viability-mapping.md) | Spot/Bundle/Tangle/Void classification |
| [`blocks/6-constraint-audit.md`](blocks/6-constraint-audit.md) | Keystone detection and danger zones |
| [`blocks/7-ship-hold-kill.md`](blocks/7-ship-hold-kill.md) | Final decision set and HTML report |
| [`references/equations.md`](references/equations.md) | Parameter calibration, field math, propagation rules |
| [`references/patterns.md`](references/patterns.md) | Pattern types, shortcut survival test, failure signals |
| [`references/constraints.md`](references/constraints.md) | Constraint types, audit checks, keystone protocol |
| [`assets/report-template.html`](assets/report-template.html) | HTML report template for Block 7 output |
| [`scripts/measure.sh`](scripts/measure.sh) | Structural measurements |
| [`scripts/package.py`](scripts/package.py) | Skill packaging |
| [`evals/evals.json`](evals/evals.json) | 5 test cases with assertions |

## Pipeline

Seven blocks, strict sequence. Read each block file before executing. Store each to brain
before proceeding to the next. If a block fails its checks, re-enter at the named block.

```
PROBLEM STATEMENT  →  FEATURE STAGING  →  DEMAND PROPAGATION  →  CONSTRAINT PROPAGATION
                                                                          ↓
                    SHIP/HOLD/KILL REPORT  ←  CONSTRAINT AUDIT  ←  VIABILITY MAPPING
```

**Execute:**
1. Read [`blocks/1-problem-statement.md`](blocks/1-problem-statement.md) → execute → store (block_number=1)
2. Read [`blocks/2-feature-staging.md`](blocks/2-feature-staging.md) → execute → store (block_number=2)
3. Read [`blocks/3-demand-propagation.md`](blocks/3-demand-propagation.md) → execute → store (block_number=3)
4. Read [`blocks/4-constraint-propagation.md`](blocks/4-constraint-propagation.md) → execute → store (block_number=4)
5. Read [`blocks/5-viability-mapping.md`](blocks/5-viability-mapping.md) → execute → store (block_number=5)
6. Read [`blocks/6-constraint-audit.md`](blocks/6-constraint-audit.md) → execute → store (block_number=6)
7. Read [`blocks/7-ship-hold-kill.md`](blocks/7-ship-hold-kill.md) → execute → store (block_number=7)

Load reference files when blocks point to them — not before.

## Core Principles

**Parameters from user evidence only.** Never set Du high to make a preferred feature survive.
The field runs on four values. Poisoning any one of them poisons all downstream blocks.

**The field reads. You don't decide.**
Patterns that emerge are findings. Spots are found, not chosen. Voids are diagnosed, not assigned.

**Bundles ship together. Non-negotiable.**
Partial bundle shipping produces broken products. Test independence before reclassifying as spots.

**The void is a finding, not a rejection.**
What doesn't survive tells you what your constraint structure cannot support — not that the
features are wrong. Always separate genuine void from constraint void.

**Danger zones will ship anyway.**
The audit's job is to document why — not stop them. That record exists so retrospectives have
something to look at.

**Phantoms are your hidden roadmap.**
Features that survive because of an unnamed intermediate dependency are telling you something
is missing from your plan. Surface it before production does.

**Shortcut survivors are bets, not facts.**
Some features survive via institutional gravity, not user demand. Label them. They ship differently.

**Prerequisites bypass ROI.**
If a feature has D=1, A>0.9, and no substitute path — ROI doesn't apply. It assumes you can
choose not to build it. Prerequisites have no such choice. Route to architecture, name the
minimum viable version that unblocks the field.

## vs GMR Toroidal

| | GMR Toroidal | CPA |
|--|--|--|
| Geometry | Fixed torus, palindromic primes | Dynamic field, Turing parameters |
| Goal | Find distant conceptual connections | Validate features under competitive pressure |
| Primary output | Crossbar (surprising bridge) | Ship/Hold/Kill decision set |
| Failure mode | Safe adjacency | Over-constraint (Dv too high) |
| Best for | Discovery, research, writing | Feature validation, roadmap audit, gap finding |

When a user asks to explore a topic deeply → GMR.
When a user asks what to build or whether they're building the right thing → CPA.
