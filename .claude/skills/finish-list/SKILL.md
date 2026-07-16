---
name: finish-list
description: >-
  Drive an in-progress TaskList to completion. Use when previous work
  stalled mid-list, when a multi-fix pipeline lost steam, or when the
  user explicitly says "finish the list", "keep going", "drive this to
  completion", "complete what's pending", "you stopped halfway", or
  invokes `/finish-list`. Refuses to yield the turn until every pending
  / in-progress task is either marked completed or has a concrete,
  named blocker recorded against it.
user-invocable: true
argument-hint: "[optional: scope filter, e.g. 'on PR #129']"
---

# Finish List

<context>
The user keeps catching the same failure: multi-step work stalls in the
middle, you ask "want me to continue?", and the user has to nudge you
to do the obvious next step. This skill exists because that pattern is
so common it earns its own slash command.

The skill IS the contract. Invoking `/finish-list` means: do not stop,
do not summarize, do not ask for permission for the next obvious step
until the TaskList shows every task in `completed` state OR every
remaining task carries a concrete blocker comment.
</context>

<instructions>

## Process

### 1. Inventory

Call `TaskList` first. Identify:
- `in_progress` tasks (resume these first — they're already started).
- `pending` tasks not blocked by other open tasks.
- `pending` tasks that are blocked by something open (work them after their blockers).

If the list is empty or every task is `completed`, say so explicitly
and stop. The skill's job is finished.

### 2. Pick the next task

Pick by ID order (lowest ID first) within the priority bucket
(in_progress > unblocked pending > blocked pending). Mark it
`in_progress` via `TaskUpdate` BEFORE doing the work — this makes
your progress visible to the user and lets `TaskList` reflect reality
if you get interrupted.

### 3. Do the work

Execute the task. The standard rules from CLAUDE.md still apply —
investigate before theorizing, follow protocols literally, surface
real failures. Don't expand scope; if the task says "ship PR #129",
ship it, don't also refactor adjacent code.

### 4. Decide the outcome

After the work:

| Outcome | Action |
|---|---|
| Task fully complete + verified | `TaskUpdate` → `completed`. Loop back to step 2. |
| Hit a real blocker that needs user input | Add a comment to the task (via metadata or task description update) naming the **specific decision the user must make** and the **default you'd take if no answer comes**. Mark task as still `in_progress`. Continue with other unblocked tasks. |
| Task already obsolete / superseded | `TaskUpdate` → `deleted` with a one-line reason. Loop back to step 2. |

### 5. Stop conditions — only these

You are allowed to yield the turn only if:

- Every task is in `completed` or `deleted` state, OR
- Every remaining task has a recorded concrete blocker AND no
  unblocked task remains, OR
- You have produced 3+ consecutive failures on the same task with
  the same hypothesis class — at which point you stop and surface
  the failure pattern (not "want me to keep trying?", but
  "here's the failure signature, here's what I've ruled out, here
  are two paths forward").

</instructions>

<constraints>

## Hard rules

- **No drift phrases at the end of the turn.** No "want me to
  continue?", "should I keep going?", "let me know if you want me
  to". The skill's purpose is to NOT do that. The Stop hook will
  catch you and re-fire — better to not earn the nudge.

- **No summarizing-as-stopping.** A summary is fine at the end of
  the run, but if the list still has unfinished work, a summary is
  a stall, not a deliverable.

- **One task in_progress at a time, by default.** Parallelism is
  fine when tasks are genuinely independent (e.g., two unrelated
  PR merges). Don't multi-track if it slows down completion.

- **Concrete blockers only.** "I'm not sure how to proceed" is not
  a blocker. "The CI E2E job needs DATABASE_URL on the preview
  scope but Vercel returned 'forbidden' on `vercel env add` — need
  user to confirm whether to provision via dashboard or skip" IS
  a blocker.

- **Don't pad the task list.** If you discover new work mid-flight,
  fine — `TaskCreate` it. But don't manufacture tasks to look busy.
  The list is real work, not narrative.

</constraints>

## Reference: anti-patterns this skill exists to prevent

- "I've completed 7 of 9 tasks. Want me to continue with the last 2?"
  → No. Just do them.
- "All the high-priority tasks are done; the rest are nice-to-haves.
  Should we keep going?"
  → If they're in the list, they're in scope. Do them or delete them
  with a reason.
- "Task 8 needs a decision from you. Should I work on Task 9 in the
  meantime?"
  → Yes. Mark Task 8 with a concrete blocker, move on. Don't ask.
- "I'll pause here in case you want to redirect."
  → No. The whole point is to not pause for redirect.
