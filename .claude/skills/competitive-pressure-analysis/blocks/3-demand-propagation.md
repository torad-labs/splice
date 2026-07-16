# Block 3: DEMAND PROPAGATION

> Propagation equations → `references/equations.md`

## Input

```
FEATURE STAGING + PARAMETERS + DEMAND SOURCE + FAMILIARITY [from Blocks 1–2]
```

## Process

Work in **thinking space**. Run the demand field only.
High demand is not endorsement. High u(x) = user pull. Not structural right to exist.

Inhibition runs in Block 4. Don't mix them here.

---

### Pass 1 — Direct propagation from demand source

For each core node x:
`u(x) = A(x) × f × (1 − D(x)/10)`

**Familiarity correction:** For every [LATENT] flagged feature:
`u_corrected(x) = u(x) × 1.2`
Document every correction explicitly.

**Cluster amplification:** For features within a mutually activating cluster
where u(x) > 0.3 threshold:
`u(x_cluster) = u(x) × 1.15`
This is real behavior — features addressing the same job pull each other up.

---

### Pass 2 — Cross-demand propagation

Core nodes with u(x) > 0.3 become secondary demand sources.
For adjacent node y: `u(y) = Σ [u(core_x) × Du × (1/D(x→y))]`

Record:
- Amplifying pairs (shared demand zone) → will likely reinforce in Block 5
- Competing pairs (demand overlap) → will face mutual constraint in Block 4

---

### Pass 3 — Phantom chain detection

Before recording final u(x) values: trace every feature with u(x) > 0.4 back to the
demand source. Are all nodes along the path in your feature list?

**If NO → there's a gap. A phantom. Name the missing intermediate.**

Three phantom types — classify each:

```
[PHANTOM PREREQUISITE]: Must exist for the surviving feature to deliver value.
  If not in scope → flag surviving feature [PHANTOM DEPENDENT].
  It ships but underdelivers without the prerequisite.

[PHANTOM ENABLER]: Structural or technical capability implicitly enabling multiple
  survivors. Not user-facing. Flags as hidden infrastructure.
  Surfaces in Block 6 as hidden dependency.

[PHANTOM COMPETITOR]: A feature that would absorb this feature's demand if it existed.
  Its absence is why this feature has high u(x).
  If a competitor ships the phantom → this feature's demand collapses.
  Flag [COMPETITOR RISK].
```

Phantoms are your hidden roadmap. Document all of them.

---

**Anti-pattern — false demand:**
Test: remove the stakeholder. Does the user still feel the need?
NO → mark [FALSE DEMAND]. Still record u(x) but flag it. Block 6 audits these.

**Demand zones:**
High (u > 0.6) | Medium (u = 0.3–0.6) | Low (u < 0.3)

## Output

```
DEMAND PROPAGATION:

PASS 1 — Direct:
| Node | u(x) | Source | Latent corrected? | False? |
|------|------|--------|------------------|--------|

CLUSTER AMPLIFICATION:
| Cluster | Members above threshold | Bonus applied | New u(x) |

PASS 2 — Cross-demand:
Amplifying pairs: [A] ↔ [B] — [why they reinforce]
Competing pairs: [A] ↔ [B] — [why they compete]

PHANTOM CHAINS:
| Feature | u(x) | Phantom type | Missing intermediate | Risk |
|---------|------|-------------|---------------------|------|

FINAL:
| Node | u(x) | Zone | Corrected? | False? | Phantom dependent? |

FALSE DEMAND FLAGS: [node] — [why stakeholder, not user]

HIDDEN ROADMAP (phantoms):
- [missing intermediate]: needed by [features], type [PREREQ/ENABLER/COMPETITOR]
```

→ Block 4
