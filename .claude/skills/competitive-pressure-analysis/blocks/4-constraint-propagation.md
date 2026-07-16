# Block 4: CONSTRAINT PROPAGATION

> Constraint types and resolution paths → `references/constraints.md`

## Input

```
DEMAND PROPAGATION: [u(x) values, zones, false flags, phantoms from Block 3]
FEATURE STAGING: [I(x) values for all nodes]
PARAMETERS: Du, Dv, f, k
```

## Process

Work in **thinking space**. Run the constraint field.
Dv > Du — constraints cover more ground than demand. This is not a penalty. It is the physics.
Complexity always reaches further than desire.

**Constraint field:**
`v(x) = I(x) × (f+k) × Dv`

Constraint nodes [C] propagate globally regardless of distance.
Dependency chains: total constraint at a node = I(own) + Σ I(dependencies × decay)

**Net field:**
`Net(x) = u(x) − v(x)`
Net > 0: candidate for survival | Net = 0: equilibrium | Net < 0: suppressed

---

### Danger Zone Flags

High u(x) + negative Net(x) = DANGER ZONE.

These are the most important nodes. Political momentum drives them onto roadmaps despite
structural cost the field correctly identifies. They will ship. Document them before they do.

For every [DANGER ZONE]:
- What users want (from u(x))
- What it actually costs (from v(x))
- Constraint type (read `references/constraints.md` for classification)
- Who owns the risk

---

### Keystone Cascade Analysis

For each constraint node and high-I(x) feature — ask: if this constraint were removed,
how many currently-suppressed features would cross Net(x) > 0?

```
Cascade score:
  0–1: Isolated. Low leverage.
  2–3: Regional. Unlocks a cluster.
  4+:  KEYSTONE. Restructures the survival map entirely.
```

Keystones go in the architecture roadmap, not the feature roadmap.
Removing a keystone is a multiplier, not a feature.

---

### Constraint Type Separation — mandatory

Two sources of constraint look identical but require different responses.
See `references/constraints.md` for full guidance.

[TECHNICAL]: Engineering work to resolve.
[POLITICAL]: Organizational work to resolve.
[RESOURCE]: Budget/headcount/timeline. Time-bounded.

For every negative-Net node: identify primary type before proceeding.

## Output

```
CONSTRAINT PROPAGATION:

CONSTRAINT SOURCES (top emitters):
| Node | I(x) | Radius (hops) | Nodes affected | Cascade score | Keystone? |
|------|------|--------------|----------------|---------------|---------|

DEPENDENCY CHAINS:
| Node | Dependencies | Total I(x) inherited |

NET FIELD:
| Node | u(x) | v(x) | Net(x) | Zone | Flags |
|------|------|------|--------|------|-------|

DANGER ZONES (high demand, negative Net):
- [node]: u=[val], Net=[val]
  Users want: [description]
  Actual cost: [description]
  Constraint type: [TECHNICAL/POLITICAL/RESOURCE]
  Risk owner: [role]

KEYSTONES:
| Constraint | Cascade score | Features unlocked if removed |
```

→ Block 5
