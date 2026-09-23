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

1. Clean export of the exact sha, never the dirty worktree:
   `git archive <sha> | tar -x -C <scratch>/export-<sha>`. The export exists to BUILD the jar from a
   tree with no dirty edits; it cannot run the ladder (no `.git`, no `node_modules`).
2. Gate of record is CI's `gate` job — `npm run gate` = `bun tools/gate run`, the WHOLE ladder —
   passing on the EXACT sha being landed and installed: `gh run view <run> --json
   headSha,conclusion` names that sha and `success`, or `gh pr checks <pr>` shows `gate pass` with
   the PR's head at that sha. Land by fast-forward only; a merge made after the run is a new sha and
   needs its own pass. Operator ruling (2026-09-23), after the local run lost three gates in one
   afternoon while CI passed the same ladder on the same sha (run 35917093436 on 679f1954): the local
   run sits in `buildgate.slice`, which hostshield declares earlyoom's FIRST victim (MANIFEST:
   "Buildgate scopes get OOMScoreAdjust=800", so its JVMs outscore everything else on the box), and
   it holds one machine-wide lock that serialises every session's landing. A local
   `bun tools/gate run` is optional feedback before pushing, never the verdict.

   The ladder runs the gradle tier (module-law, detekt, the architecture laws — concentration,
   safe-failure-render and release readiness among them — every unit test, the load test), the
   ast-grep walls, the campaign walls, the oracle replay, the code-mode selftests, config guard,
   the console lint/test, and the pr-title lint on HEAD's subject. The gradle legs alone are NOT the gate: on 2026-09-16 they were
   green three times while the oracle replay had three drifted pins. Read a red job's log for `GATE:`
   and `FAILED`; never tail it. Red = stop, fix forward, no install. Commit subjects use the
   conventional types in `tools/gate/src/lib/conventional.ts` (`chore(ledger): ...`, never `ledger: ...`), because
   the ladder lints HEAD's subject.
3. Backup first: `cp -p ~/.local/share/splice/splice.jar
   ~/.local/share/splice/splice.jar.bak-<date>-pre-<sha>`.
4. Atomic install: `cp` the built jar to a sibling path in the same directory, then `mv` it over
   `splice.jar`.
5. `systemctl --user restart splice.service`.
6. Verify the OPEN file, not the path: sha256 of the jar fd under `/proc/<pid>/fd/` equals the
   built jar's sha256. Print both beside the result in one command block.
7. One ledger note naming the sha, the CI run, the pid, and the backup path. Announce "gradle busy"
   before the jar build in step 1 and "gradle free" after.
