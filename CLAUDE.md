# CLAUDE.md — session-conduct rules for Claude Code on splice

Contracts, invariants, and gates live in `AGENTS.md`; read that first. This file carries only the
conduct rules the operator has had to repeat to sessions.

## A build runs in the background while the seat keeps coding (2026-10-09)

Operator: "as an engineer when I code and I have a long build, I'm gonna start coding other things ... I don't keep staring at the screen." Every gradle, gate, test or push command runs in the background, never in the foreground and never in a `sleep` or poll loop. While it runs, the seat edits the next item in its queue. A lock, a peer's file or a red build is never a reason to stop: take the next item and come back when the result arrives. A seat that waits is a splice defect, not a pause.

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

1. Install a jar built from the pushed sha. When CI ran green on that sha, download its artifact:
   `gh run download <run> -n splice-jar -D <scratch>/splice-jar-<sha>` (it holds
   `build/libs/app-all.jar` and `src/main/dist/bin/splice-launch`). When CI can't run, build that
   sha in a throwaway tree under /tmp (`git worktree add --detach`, `./gradlew :app:shadowJar`), take
   `app/build/libs/app-all.jar` and `app/src/main/dist/bin/splice-launch` from it, and delete the
   tree once the install is verified. The build is reproducible: CI's jar and a local jar of the
   same sha hash identically. When CI can run again, rebuild there and reinstall if the jar differs.
2. A sha is installable when the pushed commit passed the repo's hooks, and CI is green on it if CI
   ran. CI never blocks an install (Marcos's ruling, 2026-10-09). Red = stop, fix forward, no
   install.
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
7. One line, where the landing is reported, naming the sha, the CI run, the pid, and the backup path.

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

## Project laws

Marcos's standing rulings for splice, one per line.

- 0.4.0 ships everything on the branch, V4-444 included, once Marcos approves V4-444 by eye.
- Each section's "Resolved" block in `docs/specs/v0.4.0.md` is Marcos's decision and is that feature's spec.
- Retry default is total: known errors get a specific plan; every other error gets a bounded generic retry and an honest message.
- Idle is a probe, never an error: act only on a dead path, by re-anchoring; never reap a model thinking in silence.
- Separation: vendor facts live in that vendor's provider module; a shared file carries at most one dispatch line per head.
- Head isolation: no head state leaks into ~/.claude or another head; cross-head resume is an explicit -r copy with its model rewritten.
- Reasoning effort is part of the cache key: never vary it per turn type, compaction included, and do not propose it.
- No Python: repo tooling is bun/TypeScript; the gate's no-python leg fails on a tracked .py file.
- A claim about how something looks is proven by reading a capture or measuring the rendered DOM, never by a passing test, a grep, or a name's absence from the source; when Marcos says a surface is wrong, the answer is a capture, not a question back.
- Fail-closed boot: config findings refuse boot, all listed at once, and the CLI offers an interactive fix session; never boot on a finding or fix silently.
- Kotlin style: no new top-level functions and no new companion objects; top-level const val is allowed; test paths are exempt; the quality/rules walls enforce it.
- The architecture rules pack (`quality/rules/kotlin`) is wanted: a rule that contradicts the architecture means the architecture is wrong.
- Adding a head is one of three moves: reuse a schema (pure TOML), declare a new one (dialect and auth grammar), or reuse one but reshape it (quirks).
- mirror_reasoning stays false on every head: never re-enable it for parity, continuity or any agent theory; ask Marcos and wait.
- Kimi's built requests and its goldens stay byte-identical.
- The Responses websocket transport ships default off; turning it on is Marcos's one-line TOML change, never a code default.
- A cross-head fallback for a hard-down primary head is Marcos's decision to make.
- A refused claim, a blocked file or an unanswered question is never a stopping point: take the next open work and say in one line what you took and what you are still waiting on.
- No version bump and no release cut without Marcos's explicit go.
- GitHub settings, branch protection, rulesets and required checks are Marcos's to change.
