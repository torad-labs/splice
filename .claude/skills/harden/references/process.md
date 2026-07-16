# Harden Process Reference

Detailed process documentation for the adversarial hardening pipeline. Uses three models
with distinct cognitive shapes — see [multi-model-pipeline.md](multi-model-pipeline.md).

## Phase 1: Find (Haiku)

### Agent Prompt Template

Each Haiku agent receives a prompt structured as:

```
You are an adversarial auditor. Your job is to find REAL production gaps — things
that will break, leak data, or lose money. Do NOT report theoretical concerns.
Do NOT suggest fixes — only find and report.

Every finding must include:
  (1) the exact code location
  (2) a concrete scenario where it breaks
  (3) severity (critical/high/medium/low)

ALREADY FIXED (do not report these):
- [list from previous rounds]

PILLAR: [Pillar Name] ([files covered])

## [filename].ts
```typescript
[full file contents]
```

[repeat for each file in the pillar]

Find gaps that will break in production. Focus on: [pillar-specific concerns].
```

### Pillar-Specific Focus Areas

| Pillar | Focus |
|--------|-------|
| Payment/Checkout | Race conditions, data loss, money loss, incorrect behavior under concurrency |
| Access/Auth | Authorization bypass, token leaks, timing issues, access control edge cases |
| Data Integrity | Race conditions in upserts, transaction boundaries, orphaned records, data loss |
| Content Delivery | Routing edge cases, content-type issues, missing headers, API abuse, CORS |
| Email/External | Deliverability, data leaks, compliance, sanitization bypass, error handling |

## Phase 2: Classify

Classification happens in the orchestrator (inline for ≤10 findings) or in a
dedicated Sonnet block (for >10 findings). The operation is labeling only —
no fixes, no discards, no routing decisions.

### Classification Labels

| Label | Criteria | Action |
|-------|----------|--------|
| real | Concrete production scenario + reachable code path + consequence (data/money/UX/security) | Forward to Route |
| noise | Requires server keys, mathematically impossible, framework handles it, speculative, duplicate, already fixed | Forward to Route |
| ambiguous | Finding feels wrong but the code area is uncomfortable | Apply tiebreaker, then forward to Route |

### Tiebreaker Rule

If ambiguous AND reachable by user input or external request → relabel as real.
If ambiguous AND requires internal misconfiguration → relabel as noise.

### Classify Block Prompt Template (>10 findings)

```
You are a finding classifier. You receive raw findings from adversarial auditors.
Your job is to label each finding as real, noise, or ambiguous. Nothing else.

Do NOT fix anything.
Do NOT discard anything — noise findings are still forwarded (to Opus context).
Do NOT route — that is a separate step.

For each finding, output:
  (1) Finding number and summary
  (2) Label: real | noise | ambiguous
  (3) One sentence justifying the label

If ambiguous, also state whether the code path is reachable by user input.

## Findings

[all findings from all pillar scans]

## Already Fixed

[list from previous rounds]

## Noise Reference

These categories are noise:
- Requires server-side secret keys to exploit
- Mathematically impossible (brute-forcing 256-bit hashes, etc.)
- Framework misunderstanding (D1 batch IS atomic, HTMLRewriter setInnerContent sets text not HTML, etc.)
- Speculative refactoring risk ("could break if someone later changes...")
- Duplicate of another finding in this round
- Already fixed in a previous round
```

## Phase 3: Route

Routing is mechanical. The orchestrator sends each labeled finding to its destination.

| Label | Destination |
|-------|-------------|
| real | Sonnet fix block |
| noise | Opus context only (not fixed, but included in leverage input) |
| ambiguous + reachable | Reclassified as real → Sonnet fix block |
| ambiguous + not reachable | Reclassified as noise → Opus context only |
| all findings | Next round's already-seen log |

No judgment in routing. If Classify labeled it, Route sends it.

## Phase 4: Fix (Sonnet)

After routing, launch a Sonnet agent to produce targeted code changes for real findings:

```
Task tool call:
  subagent_type: general-purpose
  model: sonnet
  prompt: [fix prompt with routed findings and file paths]
```

### Sonnet Fix Agent Prompt Template

```
You are a production code fixer. You receive a list of routed findings and the
relevant source files. Your job is to produce minimal, targeted code changes that
fix each real finding.

Do NOT re-analyze the code. The analysis is done. Trust the classification.
Do NOT refactor, improve, or clean up surrounding code.
Do NOT add findings of your own. If you notice a new issue, report it as an
observation at the end — it enters the next round, not this fix batch.

## Routed Findings (fix these)

[numbered list of real findings with file, line, scenario, severity]

## Source Files

[file paths — read them yourself using the Read tool]

## Instructions

For each finding, produce:
1. The exact file and location
2. The before code (what to replace)
3. The after code (the fix)
4. One sentence explaining why this fixes the scenario

Keep fixes minimal. A fix that changes 3 lines is better than one that changes 30.

## Observations (new issues found while fixing)

If you encounter a new issue while reading or fixing code, list it here.
Do NOT fix it. It enters the next round's pillar scan.
```

Sonnet does NOT re-analyze. It does NOT add new findings to the fix batch. It
trusts the classification and fixes what was routed to it.

## Phase 5: Leverage (Opus)

After the final round, launch an Opus agent with ALL context from every round:

```
Task tool call:
  subagent_type: general-purpose
  model: opus
  prompt: [leverage prompt with all artifacts inlined]
```

### Opus Leverage Agent Prompt Template

```
You receive the complete history of a multi-round adversarial audit:
1. The original codebase (pre-hardening)
2. Every finding across all rounds — real AND noise, with their classification labels
3. Every fix applied

Your job is not to find more bugs. Your job is to identify the ONE architectural
weakness — not a specific bug, but the structural pattern that allowed the most
bug classes to exist. Changing it doesn't fix a bug; it prevents entire categories
of bugs from being possible.

## Codebase Snapshot (pre-hardening)

[key files as they existed before Round 1]

## Findings by Round

### Round 1
[all findings — real, noise, ambiguous — with classification labels]

### Round 2
[all findings — with labels]

### Round 3
[all findings — with labels]

## Fixes Applied

[complete list of all code changes made across all rounds]

## Instructions

Hold all artifacts in tension. Look for the pattern:
- What do multiple findings across rounds have in common? (Shared root cause)
- What structural choice in the codebase forced the most fixes?
- What single assumption, if changed, would eliminate an entire category of findings?
- Is there a pattern in the NOISE that reveals something real about the architecture?

## Output Format

LEVERAGE POINT: [the architectural weakness]
IF CHANGED: [which bug classes disappear — be specific about which findings]
IF NOT CHANGED: [what keeps recurring even after hardening]
EVIDENCE: [which findings from which rounds trace here — list by round and number]
PROPOSED CHANGE: [the structural change, stated precisely enough to implement]

Do not list multiple leverage points. One lever. One move.
```

Opus needs the noise findings too — patterns in what Haiku incorrectly flagged can reveal
real architectural tension that manifests as "almost-bugs."

## Signal Check

If a finding makes you uncomfortable but the scenario seems wrong, investigate further.
The gap might be real even if the attack vector described is incorrect. Check whether a
different — valid — scenario could trigger the same underlying weakness.

## Summary Output Template

After all rounds complete, present:

```markdown
## Hardening Summary

### Stats
- Rounds: N
- Agents launched: N x pillars
- Total findings: N
- Real (fixed): N
- Noise (filtered): N

### Fixes by Round

#### Round 1
| Finding | File | Severity | Fix |
|---------|------|----------|-----|

#### Round 2
| Finding | File | Severity | Fix |
|---------|------|----------|-----|

#### Round 3
| Finding | File | Severity | Fix |
|---------|------|----------|-----|

### Noise Categories
| Category | Count | Example |
|----------|-------|---------|
| Framework misunderstanding | N | "D1 batch not atomic" (it is) |
| Requires server keys | N | "Stripe metadata forgery" (needs secret key) |

### Remaining Items (not fixed)
Any findings that were real but deferred (e.g., need architectural change,
need user decision, need external action).
```

## Iteration Behavior

- Round N+1 always receives the complete "already fixed" list from all prior rounds
- Re-read files that were modified in the previous round before launching agents
- If a fix from Round N introduced a new issue, Round N+1 should catch it
- Maximum 5 rounds. After 3, diminishing returns are expected — most production
  gaps surface in rounds 1-2
