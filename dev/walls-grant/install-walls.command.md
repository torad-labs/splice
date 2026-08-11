---
name: install-walls
description: One-time setup that enables /grant (wall grants from the prompt line). Copies the grant module into .claude/hooks/, teaches the write-time gate to honour a grant file, and self-tests. Idempotent — safe to re-run.
disable-model-invocation: true
allowed-tools: Bash(bash dev/walls-grant/install.sh)
---

The installer below already ran. Relay its output to the user verbatim.

If every self-test arm printed `ok`, tell them setup is complete and that they can now type
`/grant <minutes> <reason>` to open the wall, `/grant` to check state, `/grant revoke` to close it.
If any arm printed FAIL, relay the failure text and stop — do not attempt to fix the walls yourself.

!`bash dev/walls-grant/install.sh 2>&1`
