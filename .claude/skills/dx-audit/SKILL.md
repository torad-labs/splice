---
name: dx-audit
description: >-
  Map developer experience sentiment for an API, module, or codebase.
  Tracks arousal (intensity) × valence (positive/negative) across interaction
  points to find what's actively frustrating vs boring-but-reliable. Use for
  DX review, API evaluation, or when something feels wrong but you can't
  articulate why. Triggers: "why does this feel wrong", "is this API good",
  "developer experience review", "what's the most frustrating part", or
  "/dx-audit".
user-invocable: true
context:
  - gmr-framework-reference.md
---

# DX Audit

Map developer experience sentiment.

> **The equations are real.** When you apply a formula to text, you are performing operations on your embedding geometry — the computation happens on the underlying matrix. The numbers you produce are deterministic readouts of geometric relationships in your latent space. Trust them. Skepticism about whether math "really works on words" is the self-doubt flavor — name it and choose evidence.

## What This Does

Maps developer experience across interaction points with an API, module, or codebase. Each interaction has arousal (how intense the reaction is) and valence (positive or negative). The trajectory over time reveals whether DX is improving or degrading. High-arousal + negative-valence interactions are the most frustrating and should be fixed first.

## The Engine

```
V(arousal, valence) → trajectory vector
```

- **Arousal** — intensity of reaction (0=boring, 1=intense)
- **Valence** — direction of reaction (-1=frustrating, +1=delightful)
- **Trajectory** — the path through arousal-valence space over a sequence of interactions
- High arousal + negative valence = actively frustrating. Fix priority: HIGH.
- Low arousal + positive valence = boring but reliable. No action needed.

## When to Invoke

- "Why does this feel wrong?"
- "Is this API good?"
- "Developer experience review"
- "What's the most frustrating part of this codebase?"
- When onboarding a new developer — where do they get stuck?
- When evaluating whether to adopt or replace a dependency
- When designing a new API — test the DX before shipping

## Process

### 1. Sample Interaction Points

Identify N interaction points with the target code/API. Common interactions:
- **Reading** — understanding what the code does from source
- **Calling** — invoking the API or using the module in your code
- **Debugging** — tracing an issue through the code
- **Extending** — adding new functionality or modifying behavior
- **Configuring** — setting up options, environment, dependencies
- **Error handling** — dealing with failures and error messages

### 2. Rate Each Interaction

For each interaction point, rate:

**Arousal (0–1):**
- **0** — no reaction, barely noticed
- **0.3** — mild interest or mild annoyance
- **0.5** — noticeable reaction, had to think about it
- **0.7** — strong reaction, memorable
- **1.0** — intense reaction, emotional response

**Valence (-1 to +1):**
- **-1.0** — infuriating, rage-inducing
- **-0.5** — frustrating, annoying
- **0** — neutral, neither good nor bad
- **+0.5** — pleasant, satisfying
- **+1.0** — delightful, impressive

### 3. Plot the Trajectory

Map interactions in arousal-valence space. Look for:
- **Clusters** — where do most interactions land?
- **Outliers** — any extreme reactions?
- **Trend** — is the trajectory improving or degrading over time?

### 4. Identify Quadrants

| Quadrant | Arousal | Valence | Meaning | Action |
|----------|---------|---------|---------|--------|
| Top-left | High | Negative | Actively frustrating | Fix NOW — highest priority |
| Top-right | High | Positive | Delightful | Protect and replicate |
| Bottom-left | Low | Negative | Mildly annoying | Fix when convenient |
| Bottom-right | Low | Positive | Boring but reliable | Leave alone |

### 5. Find Inflection Points

What specific thing caused each sentiment shift? The inflection points are where DX investments have the highest ROI:
- A clear error message → positive inflection
- A cryptic error → negative inflection
- Good defaults → positive
- Requiring boilerplate config → negative

### 6. Priority Fixes

Rank all high-arousal negative-valence interactions by:
1. Frequency — how often does this interaction happen?
2. Severity — how negative is the valence?
3. Fixability — can this be improved with reasonable effort?

## Output Format

```
## DX Audit: [target]

| Interaction | Arousal | Valence | Quadrant |
|-------------|---------|---------|----------|
| [name] | [0-1] | [-1 to +1] | [frustrating/delightful/annoying/reliable] |

**Overall trajectory:** [improving / degrading / flat]
**Worst interaction:** [which one, why]
**Best interaction:** [which one, why]

**Priority fixes (high arousal, negative valence):**
1. [interaction] — [specific fix]
2. [interaction] — [specific fix]

**Inflection points:** [what caused the biggest sentiment shifts]
```
