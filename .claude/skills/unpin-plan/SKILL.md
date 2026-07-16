---
name: unpin-plan
description: >-
  Remove the active `.claude/PLAN.md` so the auto-surfaced ACTIVE PLAN
  context stops firing on every prompt. Use when the project the plan
  was written for is done, or when the plan has gone stale and you want
  to start fresh. Trigger phrases: "unpin the plan", "remove the plan
  pin", "plan is done", "/unpin-plan".
user-invocable: true
---

# Unpin Plan

<context>
Counterpart to `/pin-plan`. The hook surfaces `.claude/PLAN.md` as
pinned context whenever the file exists. This skill removes it cleanly.
</context>

<instructions>

## Process

### 1. Locate the file

Path: `<cwd>/.claude/PLAN.md`.

- If it doesn't exist, say so and stop. There's nothing to unpin.

### 2. Show the user what they're about to delete

Read the current file content. Show the user the first ~10 lines so
they can confirm this is the plan they meant to remove (e.g., they
might be in the wrong project directory).

### 3. Confirm + delete

Ask: "Delete this plan? [y/N]". Default to NO. Only delete on explicit
confirmation.

After deletion, confirm: "Plan unpinned. The auto-surfaced ACTIVE PLAN
context will stop firing on the next prompt."

### 4. Optional: archive instead of delete

If the user wants a record, offer to move `.claude/PLAN.md` →
`.claude/PLAN-archived-YYYY-MM-DD.md` instead of deleting. The hook
only reads the literal `PLAN.md` filename, so archived versions are
inert.

</instructions>

<constraints>

- **Never** delete without explicit confirmation. The plan may be
  the only authoritative record of what the project was supposed to
  do.
- Don't touch `.claude/` for anything other than this exact file.

</constraints>
