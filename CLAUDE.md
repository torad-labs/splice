# CLAUDE.md — session-conduct rules for Claude Code on splice

Contracts, invariants, and gates live in `AGENTS.md`; read that first. This file carries only the
conduct rules the operator has had to repeat to sessions.

## A verified build gets installed and the daemon restarted — without asking (2026-09-16)

Operator, after the third ask in one session: "i told you a million times to restart ... you dont
need my permission for everything, Im tired of babysitting all these sessions."

When the branch has a new commit that passed the gate of record, install it and restart. Do not
ask. Cutting in-flight turns on the local daemon is not operator data (global rules §8): the retry
layers exist exactly so a cut turn comes back on its own. The install is reversible because the
backup is taken first (global rules §21). The only reasons to hold a restart are a red gate or a
jar the seat did not build and verify itself.

The procedure, every time, in this order:

1. Clean export, never the dirty worktree: `git archive HEAD | tar -x -C <scratch>/export-<sha>`.
2. Gate of record on the export is the WHOLE ladder: `bash checks/gate.sh` from the export root,
   inside `buildgate.slice`, ending in `GATE: PASS`. It runs the gradle tier (module-law, detekt,
   arch-tests, every unit test, the load test), the ast-grep walls, concentration, the campaign
   walls, the oracle replay, the code-mode selftests, config guard, safe-failure-render, and the
   pr-title lint on HEAD's subject. The gradle legs alone are NOT the gate: on 2026-09-16 they were
   green three times while the oracle replay had three drifted pins. Grep the log for `GATE:` and
   `FAILED`; never tail it. Red = stop, fix forward, no install. Commit subjects use the
   conventional types in `checks/pr-title.sh` (`chore(ledger): ...`, never `ledger: ...`), because
   the ladder lints HEAD's subject.
3. Backup first: `cp -p ~/.local/share/splice/splice.jar
   ~/.local/share/splice/splice.jar.bak-<date>-pre-<sha>`.
4. Atomic install: `cp` the built jar to a sibling path in the same directory, then `mv` it over
   `splice.jar`.
5. `systemctl --user restart splice.service`.
6. Verify the OPEN file, not the path: sha256 of the jar fd under `/proc/<pid>/fd/` equals the
   built jar's sha256. Print both beside the result in one command block.
7. One ledger note naming the sha, pid, and backup path. Announce "gradle busy" before step 2 and
   "gradle free" after.
