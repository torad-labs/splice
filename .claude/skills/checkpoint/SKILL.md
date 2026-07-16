---
name: checkpoint
description: >-
  Mid-session drift rescue. Re-anchor to the master plan + active task
  list and produce a structured "where am I vs where I should be"
  comparison with concrete file:line evidence. Use when you (the user)
  sense Claude has drifted, when a long session has lost the thread,
  before committing to a major architectural call, or invoke
  `/checkpoint`. Pairs with `/pin-plan` (auto-anchor) — pin-plan
  prevents drift, checkpoint catches drift that already happened.
user-invocable: true
argument-hint: "[optional: focus area, e.g. 'on the database migration step']"
---

# Checkpoint

<context>
The 2026-05-02 insights report flagged "long sessions tend to drift
for a day or more before architectural misalignment is caught" — the
"1.5-day megakernel drift" pattern. Skill protocol pinning fixes
start-of-skill drift; `.claude/PLAN.md` auto-surface fixes general
plan drift; this skill catches drift that already accumulated.

Manually invokable. Forces a structured comparison with **specific
file:line / TaskList-id evidence**, not vibes.
</context>

<instructions>

## Process

### 1. Gather the anchors

Read, in order:

1. `.claude/PLAN.md` (if it exists in cwd) — the master plan.
2. `TaskList` (call it) — current task state.
3. The git log of the last ~20 commits on the working branch
   (`git log --oneline -20`) — what's actually shipped.
4. The user's most recent few prompts (the last 3 user turns) —
   what they think we're doing.

If `.claude/PLAN.md` doesn't exist and there's no in-progress
TaskList, this skill has nothing to anchor against. Tell the user
and recommend `/pin-plan` for next time. Stop.

### 2. Produce the comparison

Output a four-column table with **concrete evidence in each row**:

| What plan says | What I've actually been doing | Evidence | Drift verdict |
|---|---|---|---|
| Step N from PLAN.md (quote it) | Concrete tool calls / commits / files modified | `git log` SHAs, `TaskList` IDs, `file:line` references | aligned / partial / drifted / unknown |

If a row is "drifted":
- Name the **specific deviation** (not "we're doing something different",
  but "the plan says PR #1 should add column X with default Y; commit
  abc1234 added column X with no default").
- Name the **likely cause** (e.g., "I forgot to read PLAN.md before
  PR #2 and improvised").
- Name the **proposed correction** (e.g., "amend PR #2 to add the
  default; or update PLAN.md if the plan was wrong").

If a row is "partial":
- Name what's done vs what remains.
- Name the next concrete step.

If a row is "unknown":
- Say what evidence is missing and how to get it.

### 3. Surface the highest-leverage drift first

Sort the table so the most architecturally consequential drift is on
top. Don't bury a "we're now building a different system" finding
under three "missing comment" findings.

### 4. Recommend a path forward — no drift phrases

End with **one of two crisp outputs**, no others:

- **"Resuming" path** — if drift is small or none, list the next 3
  concrete actions you're about to take from the plan and proceed.
  Do not ask "want me to continue?"

- **"Recalibrate" path** — if drift is large, name the **single
  decision** the user must make (e.g., "Two paths: (a) ship the
  current trajectory and amend PLAN.md to match; (b) revert the
  last 4 commits and resume from plan step 3. (a) preserves work
  but ships a slightly different system; (b) honors the plan but
  loses ~3 hours."). Default action if no answer comes within the
  current message: name it.

</instructions>

<constraints>

- **Concrete evidence, every row.** No "we drifted from the plan" with
  no SHA / file:line / TaskList ID. If you can't cite, the row is
  "unknown" — get the evidence first.
- **No drift phrases.** This skill ends in either a resumption with
  a named next-3-actions, or a recalibration question with a named
  default. Never "let me know how to proceed."
- **Don't auto-correct without consent.** If the comparison surfaces
  drift, propose the correction; do not silently revert / rewrite /
  re-target work without a clear thumbs-up from the user.
- **Don't pad.** A clean comparison with 3 aligned rows and 1 drifted
  row is more useful than 12 rows of trivial diffs.

</constraints>
