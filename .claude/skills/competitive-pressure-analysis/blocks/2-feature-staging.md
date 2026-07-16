# Block 2: FEATURE STAGING

> Pattern types and bundle rules → `references/patterns.md`

## Input

```
PROBLEM STATEMENT + PARAMETERS + DEMAND SOURCE + FAMILIARITY [from Block 1]
```

## Process

Work in **thinking space**. Stage everything first. Evaluate nothing yet.
Evaluation happens in Blocks 3–4 when the fields run.

Two phases: group, then place.

---

### Phase 1: Functional Grouping

Before listing individual features, identify functional clusters — groups that address
the same underlying user job.

Why this matters: features in the same cluster co-activate and share constraint load.
Treating them as atomics misclassifies bundle behavior as independent spots.
You ship one, leave the others behind, and produce a product that feels half-built.

For each cluster:
```
CLUSTER [name]:
  Members: [features sharing this functional purpose]
  Shared job: [what user need they collectively address]
  Mutual activation: Y/N — do members reinforce each other?
  Mutual inhibition: Y/N — do members compete for the same user attention?
```

Mutually activating → probable bundle (will emerge as stripe in Block 5).
Mutually inhibiting → probable winner-take-all (one spot survives, others suppressed).
Single-member clusters → proceed as atomic.

**Forward activation check:**
Does any feature's survival depend on a future feature not yet in scope?
If yes → mark [FORWARD]. Looks like a spot today. Becomes a bundle member when the
flagged future feature ships. Shipping it standalone is a timing bet, not a structural bet.

---

### Phase 2: Individual Placement

Generate 30–50 candidates. Include: features, requirements, constraints, dependencies,
edge cases, competing user needs, technical limitations, stakeholder demands.
Everything. Obvious and faint. Do not discard yet.

**Score each candidate:**

```
A(x) — demand potential: how directly does x address the actual need?
        0.0 = does not address it  |  1.0 = directly resolves it

I(x) — constraint potential: total cost
        technical complexity + dependency depth + user cognitive load +
        edge case density + maintenance burden
        Scale 0.0 (no cost) → 1.0 (prohibitive)

D(x) — distance from demand source (hops, not intuition)
        1: directly solves actual need
        2: solves a component of actual need
        3: enables something that enables actual need
        4+: related infrastructure, adjacent problems
```

**Defender test:** Could a PM defending the current state reach this feature in one move?
YES → D(x) = 1–2 regardless of how deep it sounds. Status quo narrative is always adjacent.

**Apply familiarity flags from Block 1:** Mark [LATENT] and [UNKNOWN] features.

**Three node categories:**

**Core nodes** (D = 1–2, A > 0.5): Directly address actual need. Will compete.
**Adjacent nodes** (D = 3–4, A = 0.2–0.5): Related or enabling needs.
**Constraint nodes** [C] (any D, A = 0.0): Emit constraint only. Boundary conditions.

## Output

```
FEATURE STAGING:

FUNCTIONAL CLUSTERS:
| Cluster | Members | Shared job | Mutual activation | Mutual inhibition | Forward? |
|---------|---------|-----------|------------------|------------------|---------|
| [name] | [A,B,C] | [job] | Y/N | Y/N | N / Y→[future feature] |

CORE NODES (D = 1–2):
| # | Feature | A(x) | I(x) | D | Cluster | Familiarity | Reason |
|---|---------|------|------|---|---------|-------------|--------|

ADJACENT NODES (D = 3–4):
| # | Feature | A(x) | I(x) | D | Cluster | Familiarity | Reason |

CONSTRAINT NODES [C]:
| # | Constraint | I(x) | D | What it bounds |

STAGING SUMMARY: Total [N] | Core [N] | Adjacent [N] | Constraints [N]
  Clusters: [N] | Forward-flagged: [N] | Latent: [N]
```

→ Block 3
