---
name: grant
description: Report the wall-grant state (.claude/state/walls-grant.json) — what is unlocked, time remaining, and how to issue or revoke. Read-only; issuing happens on the UserPromptSubmit hook path.
argument-hint: "[minutes] [reason] | revoke"
disable-model-invocation: true
allowed-tools: Bash(python3 .claude/hooks/modules/userpromptsubmit/03_grant_command.py *)
---

The grant state below already executed deterministically. Relay it to the user verbatim and take
no further action — in particular, do NOT attempt to issue, extend, or revoke a grant yourself.
Issuing is operator-only by construction: it lives on the UserPromptSubmit hook path, which fires
only on text a human typed into the prompt box.

!`python3 .claude/hooks/modules/userpromptsubmit/03_grant_command.py $ARGUMENTS 2>/dev/null || echo "§grant — NOT INSTALLED. Run:  bash dev/walls-grant/install.sh  (see dev/walls-grant/README.md)"`
