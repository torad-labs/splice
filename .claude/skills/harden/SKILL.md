---
name: harden
description: >-
  This skill should be used when the user asks to "harden this", "find production gaps",
  "adversarial audit", "security hardening", "run haiku audit", "find bugs with
  haiku", "stress-test this code", or "check production readiness". Runs a
  multi-round adversarial hardening pipeline: Haiku finds gaps per pillar in
  parallel, Sonnet fixes real findings, Opus identifies the architectural
  weakness that prevents the most bug classes. Three models. Three cognitive
  shapes.
user-invocable: true
argument-hint: "[scope or pillars]"
---

# Harden

<context>
Multi-round adversarial hardening. Three models with different cognitive shapes,
not three speeds of the same thing.

## Phase Map

| Phase | Model | Task | Rejection Boundary |
|-------|-------|------|--------------------|
| Inventory | Haiku | Classify files into pillars | Must not analyze for bugs — only classify |
| Find | Haiku | Detect and report production gaps per pillar (parallel agents) | Must not suggest fixes |
| Classify | Orchestrator | Label each finding as real, noise, or ambiguous | Must not fix or discard — only label |
| Route | Orchestrator | Send labeled findings to their destination | Must follow routing rules, no exceptions |
| Fix | Sonnet | Apply targeted code changes to real findings | Must not add findings not in the routed list |
| Leverage | Opus | Identify the ONE architectural weakness that prevents the most bug classes | Must not list multiple improvements |

For the full multi-model pipeline pattern, see [`references/multi-model-pipeline.md`](references/multi-model-pipeline.md).

```
INVENTORY → [ROUND 1 → ROUND 2 → ROUND 3] → LEVERAGE → SUMMARY
  (haiku)     ↓          ↓          ↓          (opus)
           pillar-scan  pillar-scan  pillar-scan
           (parallel)   (parallel)   (parallel)
              ↓            ↓            ↓
           classify     classify     classify
           (inline)     (inline)     (inline)
              ↓            ↓            ↓
           route        route        route
              ↓            ↓            ↓
           fix          fix          fix
           (sonnet)     (sonnet)     (if needed)
```

## Context Architecture

The main context is the **orchestrator**. It stays lean by delegating heavy
work to blocks — isolated subagents with their own context windows.

**What the orchestrator holds:**
- Pillar assignments (file paths, not contents)
- Already-fixed list (grows across rounds, but compact — one line per fix)
- Round summaries (real count, noise count, fix descriptions)
- Classification labels and routing decisions (compact — one line per finding)
- Nothing else. No source code. No raw findings. No verbose reasoning.

**What stays in blocks:**
- Source code (pillar-scan reads its own files)
- Raw Haiku findings (pillar-scan returns structured output)
- Fix implementation details (fix block returns what changed)
</context>

<instructions>
## Process

### 1. Inventory Block

Read `blocks/inventory.md`. Launch one Haiku agent:

```
Task tool, model: haiku, subagent_type: general-purpose
```

Input: source directory path + file patterns.
Output: pillar → file path mapping.

If `$ARGUMENTS` specifies pillars, skip this block and assign files manually.
The inventory block is for auto-identification only.

### 2. Round Loop (×3)

For each round:

#### 2a. Pillar Scan (parallel)

Read `blocks/pillar-scan.md`. Launch N Haiku agents in parallel (one per pillar):

```
Task tool, model: haiku, subagent_type: general-purpose
```

Each agent receives: pillar name, file PATHS (not contents), already-fixed list,
focus areas. Each agent reads its own files using the Read tool and returns
structured findings.

Do NOT inline source code in the prompt. The Haiku agent has Read tool access.
Give it file paths. This keeps source code out of the main context.

#### 2b. Classify

Label each finding. This is labeling only — no fixes, no discards, no routing.

**Labels:**

| Label | Criteria |
|-------|----------|
| real | Concrete scenario that WILL happen in production. Code path is reachable. Consequence is data loss, money loss, broken UX, or security breach. |
| noise | Requires server-side keys, mathematically impossible, framework misunderstanding, speculative refactoring risk, duplicate, or already fixed. |
| ambiguous | Finding feels wrong but the underlying code area is uncomfortable. |

**Tiebreaker:** If ambiguous AND the code path is reachable by user input or external request, label as real. If ambiguous AND the code path requires internal misconfiguration, label as noise.

**Volume threshold:** If total findings across all pillars ≤10, classify inline
in main context. If >10, read `blocks/classify.md` and launch a Sonnet agent.

#### 2c. Route

Send each labeled finding to its destination. No exceptions.

| Label | Destination |
|-------|-------------|
| real | Sonnet fix block (step 2d) |
| noise | Opus context only (included in leverage input, not fixed) |
| ambiguous (reachable) | Reclassified as real → Sonnet fix block |
| ambiguous (not reachable) | Reclassified as noise → Opus context only |
| all findings | Next round's already-fixed/already-seen log |

Routing is mechanical. If Classify labeled it, Route sends it. No second-guessing.

#### 2d. Fix

If real findings exist after routing, read `blocks/fix.md`. Launch a Sonnet agent:

```
Task tool, model: sonnet, subagent_type: general-purpose
```

Input: routed real findings with file paths. The agent reads affected files,
applies minimal fixes, type-checks. Returns: changes applied + verification.

If only 1-2 trivial fixes: apply inline instead of launching a block.

Sonnet receives ONLY the routed findings. It does not re-analyze. It does not
add findings of its own. It trusts the classification and routing.

#### 2e. Deploy and Update

Deploy after each round (not after all rounds). Update the already-fixed list
with one-line descriptions of each fix.

### 3. Leverage Block

After all rounds, read `blocks/leverage.md`. Launch one Opus agent:

```
Task tool, model: opus, subagent_type: general-purpose
```

Input: complete audit history — all findings (real AND noise with their labels),
all fixes, round metadata. Noise findings are included because patterns in what
Haiku incorrectly flagged can reveal real architectural tension.

Returns one leverage point.

### 4. Summary

Compose the summary in main context using round data collected during the loop.

For the output template, see [`references/process.md`](references/process.md).

</instructions>

<constraints>
## Priority Chains

When rules conflict, these chains determine which wins:

1. **Reachable vs framework label:** If a finding is reachable by user input but
   labeled noise because the framework "should handle it" — reachable wins.
   Verify the framework actually handles it before dismissing.

2. **Deploy vs Opus leverage:** Deploy after each round even if Opus hasn't run
   yet. Opus needs the full picture, but production gaps shouldn't wait for it.

3. **Sonnet scope vs observation:** If Sonnet discovers a new issue while fixing
   a routed finding, it reports the observation but does NOT fix it. New issues
   enter the next round's pillar scan, not the current fix batch.

4. **Finding escalation:** If Round N+1 finds the same area flagged again after
   a Round N fix, escalate severity by one level. Recurring findings indicate
   the fix was insufficient.

</constraints>

## Block Reference

| Block | File | Model | Parallel | Per-round |
|-------|------|-------|----------|-----------|
| inventory | `blocks/inventory.md` | haiku | no | once |
| pillar-scan | `blocks/pillar-scan.md` | haiku | yes (×pillars) | yes |
| classify | `blocks/classify.md` | sonnet (or inline) | no | yes |
| fix | `blocks/fix.md` | sonnet | no | yes (if findings) |
| leverage | `blocks/leverage.md` | opus | no | once |

<constraints>
## Key Rules

- Blocks read their own files. The orchestrator sends paths, not contents.
- Never fix noise. Most Haiku findings are theoretical.
- Classify and Route are separate steps. Classify labels. Route sends. Neither fixes.
- Deploy after each round, not after all rounds.
- Maximum 5 pillars per round.
- Opus runs ONCE, after all rounds. It needs the full picture.
- Classify is optional for small finding sets — inline when ≤10 findings.
- If a finding feels uncomfortable, that's signal. Investigate before dismissing.
</constraints>
