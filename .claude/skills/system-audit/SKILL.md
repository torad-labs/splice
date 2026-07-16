---
name: system-audit
description: >-
  Recursive system introspection — reads recent session logs from eLibrary,
  identifies recurring failure patterns, traces each back to a structural root
  cause (not the symptom), and outputs mechanical fixes (scripts/code/config,
  not CLAUDE.md rules). Produces an action list ranked by how much
  agent-reliability they remove from the dependency chain. Use when the system
  keeps breaking in the same ways, when a session ends badly, when CLAUDE.md
  feels like the only safeguard, or when the user says "system audit",
  "retrospect", "why do we keep hitting this", "what's still fragile",
  or "recursive analysis".
---

# System Audit

Recursive introspection that finds structural problems and outputs mechanical fixes.

Not advice. Not rules. Code.

## What This Is

The recurring failure pattern: something breaks, the fix goes into CLAUDE.md, it breaks again next session because CLAUDE.md depends on agent context and memory. This skill breaks that loop by tracing every failure to its structural root and producing a fix that works without agent cooperation.

Three passes through the same material:
1. **Surface pass** — what failed? (symptoms, error messages, what the user had to correct)
2. **Structural pass** — why did it keep happening? (what condition allowed the symptom to recur)
3. **Root pass** — what assumption baked into the system made that condition stable?

The root pass is the valuable one. It finds things like: "token lookup required the agent to have read the right file in a previous session" or "the redirect existed in a file the deploy script never touched."

## Protocol

### Step 1 — Load Recent Session Logs

```
read_session_logs (limit: 10)
```

Read what happened. Don't summarize — feel the shape of it. Where did the user have to correct something? Where did the agent say "I thought I fixed this"? Where did the word "again" appear?

### Step 2 — Identify Failure Clusters

Group failures by pattern, not by session. A token auth failure in session 3 and a token auth failure in session 7 are the same failure. The question is: what structural condition persisted across both sessions that allowed it to happen twice?

For each cluster:
- What was the symptom? (what the user saw)
- How many times? (severity signal)
- What was the stated fix? (what went into CLAUDE.md or was "resolved")
- Did the fix actually address the structural condition, or just the instance?

### Step 3 — Recursive Root Tracing

For each cluster, go back three levels:

**Level 1 (why it happened):** The immediate cause. "Token was wrong." "File path was absolute." "Node modules weren't installed."

**Level 2 (why the fix didn't hold):** What made the Level 1 cause recur after the fix? "Fix was a CLAUDE.md rule that requires agent context." "Fix assumed the agent would remember to check the credentials file." "Fix required the agent to have seen the specific error in a prior session."

**Level 3 (structural condition):** What is stable in the system that makes Level 2 true? "Deploy process has no pre-flight checks." "Token lookup is not automated." "Agent memory is session-scoped but the fix required cross-session memory."

Level 3 is where the actionable fix lives.

### Step 4 — Output Mechanical Fixes

For each root (Level 3), produce a specific mechanical fix:

| Pattern | Root | Fix | Removes dependency on |
|---------|------|-----|----------------------|
| Token lookup fails | Agent has to read credentials file in context | Script reads token automatically | Agent having read the file |
| Landing page redirect | JS in index.html not guarded | validate-deploy.mjs blocks deploy | Agent knowing the history |
| Deps not installed | No pre-flight check | check-brain-deps.mjs auto-installs | Agent remembering to install |

Score each fix: **0** = still depends on agent memory, **1** = partially mechanical, **2** = fully mechanical (works regardless of agent context).

Target: everything at score 2.

### Step 5 — Identify What's Still at Score 0

After all prior fixes, what still depends on agent memory or context? These are the remaining fragility points. Output them plainly — no hedging. These are the things that will break in the next session when context is lost.

## Output Format

```
SYSTEM AUDIT — [date]

RECURRING FAILURES
[cluster name] — [N occurrences]
  Surface: [what the user saw]
  Structural: [why the fix didn't hold]
  Root: [stable condition that made recurrence possible]
  Fix: [specific script/code/config change]
  Score: [0/1/2]

...

STILL FRAGILE (score < 2)
[list of remaining dependencies on agent context/memory]

RECOMMENDED ACTIONS (ranked by fragility removed)
1. [most impactful mechanical fix]
2. ...
```

## Notes

- CLAUDE.md rules are score 0 by default. They can become score 1 if backed by a pre-flight check, score 2 only if the check is automated and blocks the failing operation.
- The goal is not a perfect system. The goal is a system where the common failures are score 2 and the remaining fragility is explicit and known.
- Run this after any session where the user had to correct something more than once. Don't wait for it to be requested.
