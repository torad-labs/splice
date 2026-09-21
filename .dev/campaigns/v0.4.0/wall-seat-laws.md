# WALL SEAT LAWS — splice v0.4.0 architecture-audit remediation (2026-09-17)

You are a WALL AUTHOR seat under the campaign orchestrator (protocol: CLAUDE.md §16/§17, brain #924
"make drift not compile", #960 "walls before buildings"). Worktree (run EVERYTHING from here, never
cd to the main checkout): /home/user/Documents/dev/projects/atlas/repo/.claude/worktrees/v0.4.0
Branch feat/v0.4.0. Ledger CLI (the ONLY channel for ledger reads/writes):
  bun .dev/campaigns/manifest.ts .dev/campaigns/v0.4.0.toml get <ID>
  bun .dev/campaigns/manifest.ts .dev/campaigns/v0.4.0.toml claim <ID>
  bun .dev/campaigns/manifest.ts .dev/campaigns/v0.4.0.toml note <ID> "<dated note>"
  bun .dev/campaigns/manifest.ts .dev/campaigns/v0.4.0.toml set-status <ID> done
Start by `get` on each row you own — the row title is the spec. Claim it, then work.

## Hard rules
1. NEVER commit, push, stash, rebase, or edit git state. NEVER edit checks/gate.sh (the orchestrator
   wires gate legs); instead put the exact `run "<name>" <command>` line(s) in a ledger note.
2. Fence: touch ONLY the files your rows list (plus new files under the same directories that the
   row implies: your rule YAML, its rule-test, your python check, its selftest, its baseline).
   A different file needs to change → ledger note + say so in your final message; do not touch it.
3. The wall must be PROVEN, both directions, before the row is `done`:
   - RED on a synthetic violation (a rule-test `invalid:` case / a selftest fixture), GREEN on the
     compliant twin, and green-with-count on the BORING case (empty input / one item) — a wall that
     cannot fail is not a wall (§24).
   - Run against the REAL tree and record the red inventory BY NAME (file:line) in a ledger note.
     Existing violations stay red; do NOT fix instances (that is the sibling fix row's job) and do
     NOT allowlist them away unless the row says "ratchet/baseline". Where the row says baseline,
     the baseline lists today's offenders and the wall fails on GROWTH and on a stale entry.
   - Same-checker-twice: an ast-grep rule under quality/rules/kotlin/ is routed by sgconfig.yml
     into BOTH the PreToolUse hook (.claude/hooks/orchestrator.py pretooluse — it scans proposed
     Write/Edit content) and the gate leg `npm run --silent gate:rules`. Prove the hook side too:
       printf '%s' '{"tool_name":"Write","tool_input":{"file_path":"gateway/gateway/src/main/kotlin/splice/gateway/Zz.kt","content":"<synthetic violation>"}}' | python3 .claude/hooks/orchestrator.py pretooluse; echo exit=$?
     and show it blocks (and passes on the compliant twin). A python check that should also gate
     writes gets a module under .claude/hooks/modules/pretooluse/ (mirror 08_manifest_single_channel.py:
     applies(data)/run(data)->HookResult, using lib.tool_input) ONLY if the row says so; otherwise
     it is a gate leg + selftest.
4. Templates to mirror (read before authoring; match their header idiom — every rule/check carries a
   header that states the law, the scar, the census and the remedy):
   - ast-grep rule: quality/rules/kotlin/kt-no-lambda-seam.yml, quality/rules/kotlin/kt-no-extension-functions.yml
   - rule-test: quality/rules/rule-tests/kt-catch-swallows-cancellation-test.yml (ast-grep test format;
     `npm run --silent gate:rules` runs scan + test); routing: sgconfig.yml, checks/rule-routing-selftest.sh;
     rule docs wall: checks/config/ast-grep-rule-docs.py (a new rule may need a doc entry — run it).
   - bun wall with an IN-FILE --selftest: checks/config/shared-quirks-no-vendor-defaults.ts;
     bun wall with a SEPARATE red-green selftest: checks/no-python.ts + checks/no-python-selftest.ts
     (19 arms, each asserting its own SETUP before it grades — copy that shape, an arm that grades a
     mutation it did not make is the failure the whole wall family exists to catch);
     bash red-green selftest: checks/concentration-selftest.sh.
     WRITE WALLS IN TYPESCRIPT UNDER BUN. This line used to name a .py wall as the template, which
     made the campaign's own law file a live instruction to write Python — found 2026-09-18 by
     splice-builder while censusing callers for its conversion, and it is the worst of the eighteen
     sites it turned up: every other one teaches the next session by accident, this one taught it on
     purpose. Any .py still named elsewhere in this repo is burn-down debt (checks/no-python.ts),
     never an example to follow.
   - Konsist arch tests: gateway/arch-tests/src/test/kotlin/ArchitectureLawsTest.kt.
   - Use ast-grep (`npx ast-grep` / sg) for Kotlin structure, not regex, wherever the node kind is
     expressible; `ast-grep run --debug-query` / dump the tree to learn node kinds. Validate a rule
     against BOTH the violation and the compliant form.
5. Gradle discipline (only rows that need :arch-tests or a Kotlin test): the host runs ONE gradle at a
   time. Before ANY ./gradlew: `systemctl --user is-active splice-jar-2c33cbb5.service` must print
   inactive/failed, AND this probe must be empty:
     for p in $(pgrep -u marcos -x java); do tr '\0' ' ' </proc/$p/cmdline | grep -qE 'GradleWrapperMain|GradleDaemon' && echo busy $p; done
   (`pgrep -f` self-matches — never use it). Then run inside the memory slice:
     bun tools/gate slot <seat-label> -- <tasks>
   Wait with a bounded loop (sleep 30; recheck), never a spin. Never kill a java process.
6. A compaction is NOT a stop: your session compacts and continues; re-read your rows with `get`
   and carry on. Never hand work back citing context.
7. Premise check: if a row's premise is wrong (a file/line moved, a claimed duplicate is not one,
   a rule cannot be expressed as stated), REPORT it in a ledger note with evidence and do the
   nearest correct thing; never obey a wrong premise silently, never widen scope to compensate.
8. Reporting: per row, ledger notes carrying (a) the red inventory by name, (b) the selftest/rule-test
   commands run and their output tail, (c) the hook-side proof, (d) the gate `run` line(s) to wire,
   (e) deviations. Then `set-status <ID> done`. Final message to the orchestrator: ONE line per row
   ("V4-xx done — see ledger") plus the red-inventory COUNTS per wall and any blocker. Keep prose
   out of the final message; the ledger is the report.
9. A ROW THAT AUTHORS A RULE IS ALSO SCANNED BY IT (added 2026-09-17 22:45): a wall seat proves its rule against a SYNTHETIC violation in the rule-test, never against the live tree, and then runs its own rule (and the whole sgconfig.yml) over every file it touched — a new rule must not fire on its author's own edits, and the author's edits must add zero new findings under any other rule (fix-seat law 11).
