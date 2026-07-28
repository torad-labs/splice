---
name: grant
description: Report the wall-grant state (.claude/state/walls-grant.json) — what is unlocked, time remaining, and how to issue or revoke. Read-only; issuing happens on the UserPromptSubmit hook path.
argument-hint: "[minutes] [reason] | revoke"
disable-model-invocation: true
allowed-tools: Bash(python3 .claude/hooks/modules/userpromptsubmit/03_grant_command.py *)
---

NOTE (2026-07-28): the arguments placeholder is QUOTED in the bang-line below. Unquoted, the
operator's own reason text is evaluated as shell — a reason containing `(`, `)`, `;`, `&` or a
backtick dies with a bash syntax error BEFORE anything runs, and the fallback branches never fire
either, so the operator sees a raw shell trace instead of grant state. Hit live by a reason reading
"review of #62" in parentheses. The read-only CLI ignores its argv (it only prints status), so
collapsing the arguments into one quoted word costs nothing.

Do not write the placeholder's literal name in this prose: Claude Code interpolates it in the BODY
too, so the note substitutes the operator's own arguments into itself and reads as nonsense.

The grant state below already executed deterministically. Relay it to the user verbatim and take
no further action — in particular, do NOT attempt to issue, extend, or revoke a grant yourself.
Issuing is operator-only by construction: it lives on the UserPromptSubmit hook path, which fires
only on text a human typed into the prompt box.

!`M=.claude/hooks/modules/userpromptsubmit/03_grant_command.py; if [ ! -f "$M" ]; then echo "§grant — NOT INSTALLED. Run:  bash dev/walls-grant/install.sh  (see dev/walls-grant/README.md)"; elif ! python3 "$M" "$ARGUMENTS"; then echo; echo "§grant — STATUS UNAVAILABLE: the module IS installed but failed to run (error above)."; echo "  This tells you nothing about whether a grant is active — the gate is the authority."; echo "  Re-running the installer will not help unless the error says it will."; fi`
