# Goal

Every remaining phase of the #924 enforcement plan is implemented and committed on branch
`gateway-stabilization-2026-07-18`, with `bash checks/gate.sh` printing `GATE: PASS`. Do not stop
until this is true.

# Context

- Plan: `/Users/marcos/.claude/plans/staged-hopping-lamport.md` (phases 0.5, 1, 2, 3-rest, 4, 5, 6, 0c).
- Branch: `gateway-stabilization-2026-07-18` — everything here, NO new branches.
- Build: `cd gateway && JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew check`.
- Already done + committed: Phase 0 (`1a88d71`, CI gradle gate), Phase 3-SecureFile (`43c7609`, kimi fix). Don't touch those or the earlier CPU/daemon commits.
- Grant: the operator gave broad approval to complete ALL phases including walls. `.rules/`, `.claude/hooks/`, settings, sgconfig are gated behind `SPLICE_WALLS_OK=1`, which the Write/Edit hook enforces but which does NOT fire on Bash-written files → edit those files via Bash (heredoc/python), noting it in the commit.
- `FormEncoding` copies (grok/codex/kimi) are confirmed byte-identical. `JsonScalars`: 2 of 5 `str` copies leak `"null"` — reconcile to JsonNull-safe. `InflightGateTest` is flaky under load.
- Slot-header law: every core/provider/etc production file's first line is `// PORT-OF: … ` or `// NEW: `. Import order: non-java/kotlin first, then java/javax/kotlin (splice before java).

# Constraints

- Same branch only; one commit per phase; each commit's tree is gate-green before moving on.
- NEVER trust a filtered `gradle | grep` exit — read the real `BUILD SUCCESSFUL` / exit code.
- Do NOT weaken or delete a test to make it pass. A proven-flaky test may be hardened (fix the race) or quarantined with a documented retry — never by deleting assertions.
- DTOs (`ResponsesRequest`/`ChatRequest`) are CLOSED — no `extras`. Prove each DTO's output is byte-identical to the current builder with a unit test before replacing the builder (this is the receipt substitute for the no-live-traffic path).
- Walls are default-deny (`gateway/*/src/main/**`) and single-source; every new wall gets a red/green rule-test.
- Don't break the previously-green suite; `./gradlew check` stays green.

# Done when

1. Phase 3-rest: `FormEncoding` + `JsonScalars` + `JsonlSink` extracted & routed; `./gradlew check` exits 0 — paste it. JsonScalars null-leak reconciled (test proves JsonNull → null).
2. Phase 2: `ResponsesRequest`/`ChatRequest` closed DTOs + value classes + `ReasoningDisplay` + `AuthKind` enums committed; a byte-identity test per DTO passes; `./gradlew check` exits 0 — paste it.
3. Phase 0.5: both incident walls widened to `gateway/*/src/main/**`; config-surface guard added; `npm run gate:rules` exits 0; a planted violation in a NEW module fires the wall.
4. Phase 4: Konsist `PORT_SCOPE_MODULES` includes passthrough+kimi; module-law covers test configs; detekt.yml Compose-vestige pruned; transport-shape assertion added; `InflightGateTest` green 10 consecutive runs — paste the loop output.
5. Phase 5: blocking `kt-no-runcatching-in-coroutine` wall added, red/green tested.
6. Phase 1: golden request-byte fixtures + contract test per dialect committed (byte-identity, the offline half); receipt-emission wired into `heads-e2e.sh` (live half runs when traffic exists).
7. Phase 6 + 0c: AGENTS.md maps each failure class to its tier; Stop-hook runs a fast gradle check on dirty Kotlin; tier-ladder persisted to brain.
8. Final: `bash checks/gate.sh` prints `GATE: PASS` — paste it; `git log --oneline` shows the phase commits on `gateway-stabilization-2026-07-18`.

# On block

Resolve autonomously — do NOT ask the user. Root-cause, then try up to 3 distinct approaches per
sub-problem; never repeat an approach that already failed verification. Edit grant-gated wall files
via Bash under the standing operator grant. If a single phase genuinely cannot complete after 3
distinct approaches, record it (attempts, evidence, blocker) in the FINAL report and CONTINUE to
the remaining phases — a stuck phase never halts the whole goal. Surface exactly once, at the end,
with: the commit chain, the `gate.sh` output, and any phase that hit a hard block with evidence.

Budget: large (multi-phase). Sandbox: workspace-write, autonomous.
