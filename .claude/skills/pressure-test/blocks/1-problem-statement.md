# Block 1: PROBLEM STATEMENT

> Parameter ranges and calibration → `references/equations.md`

## Input

User's product requirement, feature request, roadmap, or request to pressure-test
features already shipped.

## Process

Work in **thinking space**. Present only the Output block.

**Operation 1: Separate stated from actual.**

The stated requirement is what someone asked for.
The actual need is what they're trying to solve.
These are never the same sentence. If they are, you haven't found the actual need yet.

Quality gate: Δ(x) = |P_training(x) − P_evidence(x)| × C(x)
Large Δ → navigate the actual need. Small Δ → proceed with stated.

**Operation 2: Set parameters from evidence only.**

Never set parameters to favor a preferred outcome — it poisons every downstream block.

See `references/equations.md` for calibration ranges and evidence-to-parameter mapping.
Minimum rule: Dv > Du always. Prefer Dv = Du × 1.5.

Stability check before proceeding: f < (1 − √(Du/Dv))²
Fails → no patterns will emerge → adjust before continuing.

**Operation 3: Familiarity dimension.**

Classify demand basis before staging. This prevents the field from burying discovery
features that users need but haven't articulated.

```
KNOWN:   Users articulate the need. Trust u(x) directly.
LATENT:  Behavioral evidence exists, no explicit articulation.
         Flag [LATENT] → apply u(x) × 1.2 correction in Block 3.
         Document the correction explicitly. It's evidence-based, not preference.
UNKNOWN: No signal. Flag [UNKNOWN]. Do not run through field.
         Treat as constraint void in Block 5 with note.
```

## Output

```
PROBLEM STATEMENT:
  Stated: [exact as given]
  Actual need: [different sentence — what they're trying to solve]
  Δ: [large / small]

PARAMETERS:
  Du = [value] — [evidence]
  Dv = [value] — [evidence, must be > Du]
  f  = [value] — [evidence]
  k  = [value] — [evidence]

STABILITY: f < (1 − √(Du/Dv))² → [passes / fails]

DEMAND SOURCE: [actual need at u₀]

FAMILIARITY:
  Known: [signals]
  Latent: [behavioral evidence without articulation]
  Unknown: [no signal — flag before staging]
```

→ Block 2
