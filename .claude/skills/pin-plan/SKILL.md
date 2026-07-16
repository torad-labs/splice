---
name: pin-plan
description: >-
  Write a master plan to `.claude/PLAN.md` in the current project so it
  gets auto-surfaced as ACTIVE PLAN context on every prompt for the rest
  of the session (and across session boundaries — the file persists).
  Use when starting a multi-hour or multi-day project where drift is
  likely. Trigger phrases: "pin this plan", "make this the plan",
  "stick to this plan", "/pin-plan".
user-invocable: true
argument-hint: "[plan body, or omit to be prompted]"
---

# Pin Plan

<context>
The 2026-05-02 insights report flagged "long sessions tend to drift for
a day or more before architectural misalignment is caught." The fix is
a self-organizing pin: write the master plan to `.claude/PLAN.md`, and
the `pin-skill-protocol` hook surfaces it on every UserPromptSubmit
with strong drift-check framing.

This skill is the ergonomic helper. The pin itself is structural.
</context>

<instructions>

## Process

### 1. Get the plan content

If the user provided plan text as the argument to `/pin-plan`, use that
directly. Otherwise, ask them: "What's the master plan? Paste the full
text. I'll write it to `.claude/PLAN.md` so it auto-surfaces on every
turn until you `/unpin-plan` or delete the file."

If they have an existing plan in chat or in a file (e.g., a planning
document the user just wrote), offer to use that — confirm the source
before writing.

### 2. Write to `.claude/PLAN.md`

Path: `<repo root>/.claude/PLAN.md` (relative to current working dir).

- Create the `.claude/` directory if it doesn't exist (`mkdir -p`).
- Write the plan as the file content. No additional framing — the
  hook's `render_active_plan` adds the drift-check header and
  surrounding instructions.
- If `.claude/PLAN.md` already exists, **do not silently overwrite**.
  Diff the new content against existing, surface the diff, and ask
  the user whether to overwrite, append, or cancel.

### 3. Confirm

Tell the user the file path that was written and remind them:
- The pin is now active for every subsequent turn.
- To remove: `rm .claude/PLAN.md` or invoke `/unpin-plan`.
- The file is also a normal markdown file — they can edit it directly
  in their editor as the plan evolves.

### 4. Optional: gitignore check

If the project has a `.gitignore`, check whether `.claude/` is ignored.
Warn the user if it isn't and they probably don't want to commit
PLAN.md to version control. Don't auto-edit `.gitignore`.

</instructions>

<constraints>

- **Never** modify the file content beyond what the user provided.
  No "improvements," no rewording, no normalization. The plan is the
  user's contract with themselves.
- **Never** delete an existing `.claude/PLAN.md` without explicit
  consent. Overwrite-with-confirmation only.
- The file lives at the cwd of the Claude Code session. If cwd is not
  the project root (rare), surface that and ask whether to write here
  or somewhere else.

</constraints>
