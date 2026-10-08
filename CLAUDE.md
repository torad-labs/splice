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

Never wait for a turn, a stop, or another seat's work (2026-09-28). Operator: "YOU CAN ALWAYS INSTALL
AND RESTART SPLICE at anytime, never wait for a turn or for a stop. SPlice WAS BUIULT to recover, if
you restart, and the session dont recover bythesmelvfes we have a bug." Restart with
`splice restart --now` the moment the jar is installed. No gate on a local model server's load, a
peer's GPU run, a peer's "hold the restart" message or an idle window. A session that does not come
back on its own after a restart is a splice defect: file it as a row with the evidence, never wait
the next restart around it. Scar: a gated restart waited 15 minutes for rig's 8102 to report idle
while the green jar sat on disk.

The procedure, every time, in this order:

1. Install the jar the gate of record built, never a local build: `gh run download <run> -n
   splice-jar -D <scratch>/splice-jar-<sha>`, where `<run>` is the `ci` run of step 2. CI uploads
   that artifact only on a green run (`.github/workflows/ci.yml`, "keep the gate-built jar"); it
   holds `build/libs/app-all.jar` and `src/main/dist/bin/splice-launch` from that exact sha. No
   worktree, detached or not, no clone, no archive, no copy of the tree: the operator forbids every
   second checkout, build trees included (2026-09-28, again 2026-10-01). The build is
   reproducible: run 36954147029's artifact for b97c74a77 hashed identically to the jar built
   locally from that sha. The artifact lives 14 days; an older sha gets a fresh CI run.
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
4. Atomic install: `cp` the artifact's jar to a sibling path in the same directory, then `mv` it
   over `splice.jar`. The launcher ships beside it and the jar cannot refresh it: back up
   `~/.local/share/splice/splice-launch`, then put the artifact's `splice-launch` in place the same
   way (sibling copy, mode 0755, `mv`). A jar-only install left the launcher at
   shim-10 under a daemon that wants shim-11 (2026-10-01).
5. `splice restart --now`, at once, with no gate in front of it (see above).
6. Verify the OPEN file, not the path: sha256 of the jar fd under `/proc/<pid>/fd/` equals the
   artifact jar's sha256. Print both beside the result in one command block.
7. One ledger note naming the sha, the CI run, the pid, and the backup path.

## Never ask the operator to look at a screen no seat has looked at (2026-10-07)

Operator, after splice-lead asked him to review the console: "Who reviewed and approved the
console? ... I WANT WHOEVER APPROVED THIS BULLSHIT TO NEVER MAKE THIS MISTAKE AGAIN." Nobody had.
splice-lead asked after checking only that the page answered 200. The last whole-console approval
was a week old, and every later walk was at 1440 px in light mode. He uses dark mode on a wide
screen (his Oct 7 screenshot: 3394 x 1889), where Accounts drew text over its cards, cut controls
off at the right edge, and read as walls of explanation. Six redesigns reached him this way.

Before any seat asks the operator to look at a UI (a page, the console, a comp), the asking seat:
1. Opens every page it asks about, on the build he will see, in his setup (dark mode at his
   width) and at 1440 in both themes, and looks at every screenshot itself.
2. Fixes what is broken before he sees it: text over text, clipped text, content past the edge,
   crowding, walls of text, and any control whose effect a first-time user could not say from the
   screen. Nothing broken goes to him as a list of known issues.
3. Judges use, not only defects: walks every control against the screen's element table (what it
   shows, what a tap does, why it is there), checks that actions line up across cards and rows, and
   compares the screen side by side with its reference screens. Clean but unclear, cramped or
   templated fails.
4. Sends the screenshots it judged, with the sha, in the ask.

A passing test, an HTTP 200, a source review, a copy audit or another seat's older walk is not a
look. A seat that has not done 1-4 does not ask. A number that could mislead is fixed in the number
or its label first; a short line beside a control is allowed when its name alone would be cryptic.
Scar, Oct 7, 7:17 PM CT: nine screens passed splice-lead and marlin on defects alone at 3394; he
reviewed at 3828 and stopped at screen 2 ("What does note do?", cards that "all look like templates").
