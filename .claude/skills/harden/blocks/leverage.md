# Block: Leverage

Identify the ONE architectural weakness that would prevent the most bug classes.

## Contract

**Model:** opus
**Runs:** once, after all rounds complete
**Input:** complete audit history — all findings (real + noise), all fixes, round metadata
**Output:** leverage point + evidence + proposed change

## Prompt Template

```
You receive the complete history of a multi-round adversarial audit.

Your job is not to find more bugs. Your job is to identify the ONE architectural
weakness — not a specific bug, but the structural pattern that allowed the most
bug classes to exist. Changing it doesn't fix a bug; it prevents entire
categories of bugs from being possible.

## Audit History

### Round 1
Real findings: {round_1_real}
Noise findings: {round_1_noise_summary}
Fixes applied: {round_1_fixes}

### Round 2
Real findings: {round_2_real}
Noise findings: {round_2_noise_summary}
Fixes applied: {round_2_fixes}

### Round 3
Real findings: {round_3_real}
Noise findings: {round_3_noise_summary}
Fixes applied: {round_3_fixes}

## Instructions

Hold all artifacts in tension. Look for the pattern:
- What do multiple findings across rounds have in common? (shared root cause)
- What structural choice forced the most fixes?
- What single assumption, if changed, would eliminate an entire category?
- Is there a pattern in the NOISE that reveals something real about the architecture?

## Output Format

LEVERAGE POINT: {the architectural weakness}
IF CHANGED: {which bug classes disappear — be specific about which findings}
IF NOT CHANGED: {what keeps recurring even after hardening}
EVIDENCE: {which findings from which rounds trace here — list by round and number}
PROPOSED CHANGE: {the structural change, stated precisely enough to implement}

One lever. One move. Do NOT list multiple improvements.
```

## Why This Is a Block

Opus needs the full audit history to find leverage — but the raw history
(findings, fixes, noise across 3 rounds) is large. As a block, Opus receives
a curated summary of each round's output rather than the main context holding
everything cumulatively. The main context passes round summaries (compact) and
receives one leverage point (compact).

## Noise Matters

Include noise findings in the input. Patterns in what Haiku incorrectly flagged
can reveal real architectural tension that manifests as "almost-bugs." If 8
noise findings across 3 rounds all point to the same structural concern from
different angles, that concern might be real even though each individual finding
was technically noise.
