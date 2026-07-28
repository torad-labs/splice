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

!`M=.claude/hooks/modules/userpromptsubmit/03_grant_command.py; if [ ! -f "$M" ]; then echo "§grant — NOT INSTALLED. Run:  bash dev/walls-grant/install.sh  (see dev/walls-grant/README.md)"; elif ! python3 "$M" $ARGUMENTS; then echo; echo "§grant — STATUS UNAVAILABLE: the module IS installed but failed to run (error above)."; echo "  This tells you nothing about whether a grant is active — the gate is the authority."; echo "  Re-running the installer will not help unless the error says it will."; fi`
