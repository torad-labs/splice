// NEW (V4-97): the daemon's TEARDOWN ORDER, pinned at the four call sites that write it.
//
// WHAT THE SIBLING TESTS DO NOT COVER. DaemonStopBudgetTest pins the stop ladder's NUMBERS — each
// budget inside the next — and HeadServerStopDrainTest proves one head drains a held turn instead
// of tearing its socket. Neither says anything about the ORDER of the phases around that drain, and
// the 2026-09-17 architecture audit found four places where the order is wrong while every budget
// is right:
//
//   (a) Daemon.stop() cancels the provider probe scope BEFORE the 45s head drain, so a SingleFlight
//       token refresh started by a live turn during the drain is cancelled by a job the turn does
//       not own — a FOREIGN CancellationException surfacing inside a turn that is still streaming.
//   (b) HeadServer.stopLocked drains in-flight turns (detached compactions included) BEFORE
//       driver.stopDetached(): a detached compaction OUTLIVES its client and its handed-off slot
//       travels with the drive, so the drain budget belongs to that feature — a detached compaction
//       that finishes inside the budget releases its slot and keeps its recording for the retry, and
//       stopDetached then ends only what is STILL running once the budget is spent. An earlier audit
//       premise (that one detached compaction BURNS the whole 45s budget) was wrong and is corrected
//       here: this arm previously asserted the opposite order.
//   (c) Main's teardown DISCARDS AsyncFileIo.drain()'s Boolean. A false there means the file lane
//       did not flush inside its timeout, i.e. daemon.log / usage / economics writes were lost on
//       the way out, and it is the one place a loss is still reportable before halt.
//   (d) TurnStreamer's `detachedScope.isActive` guard could never be false: stopDetached() calls
//       cancelChildren(), which never cancels the scope, and nothing else cancels it. The dead guard
//       and its header claim were removed — the detached scope OUTLIVES a head restart by design, so
//       every compaction records and the guard had no branch to guard.
//
// WHY THIS WALL READS THE SOURCE instead of observing teardown through fakes, which is what the row
// asked for. Recorded as a premise correction in the V4-97 ledger note, in short:
//   - The order in (a) is owned by Daemon.stop(), whose collaborators are PRIVATE fields built
//     inline (`private val controlPlane`, `private val headShutdown`, `private val heads`). A :app
//     test can inject a Topology, a StatePaths, a LogSink, a ShutdownDaemon and a
//     TokenUrlRefreshCall — and none of those reaches the probe scope or the head-stop phase. There
//     is no seam to observe the order through, and adding one means editing Daemon.kt, which is the
//     fix row's file, not this row's.
//   - (b) and (d) live INSIDE :daemon-head: stopLocked is private, and TurnDriver and TurnStreamer are
//     `internal class` in :daemon-head, so no test in this module can name them. Their behavioural
//     twins belong beside HeadServerStopDrainTest; the ledger note names the exact assertions.
//   - (c) is a DISCARDED RETURN VALUE. There is no runtime state to observe: forcing drain() to
//     return false means overflowing AsyncFileIo's process-wide 2048-task lane, which would poison
//     every other test in the JVM.
// So the observation is of the real artefact — the production files on disk, exactly as
// ExampleConfigTest reads the committed example TOML — rather than of a fake that mirrors the
// policy. The four sources are compile inputs of this module's test runtime classpath, so editing
// any of them re-runs this test; only a non-compiled input (the example TOML) needs the explicit
// `inputs.file` declaration in build.gradle.kts.
//
// THE READER IS ITSELF RED-GREEN PROVEN. A source-reading assertion that stopped finding its anchor
// would pass forever, which is the §24 failure this whole campaign is about. So every anchor is
// REQUIRED to be present (an absent anchor fails by name, it does not skip), and the last test
// applies the same two predicates to synthetic compliant and violating strings — the proof that a
// green here means the order is right and not that the reader went blind.
//
// NO WALL CLOCK: there is nothing to wait for. Every assertion is a comparison of two positions in
// a string.
package splice.app.cli.daemon

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private const val DAEMON_REL = "app/src/main/kotlin/splice/app/Daemon.kt"
private const val HEAD_SERVER_REL = "daemon/head/src/main/kotlin/splice/head/HeadServer.kt"
private const val MAIN_REL = "app/src/main/kotlin/splice/app/Main.kt"
private const val TURN_STREAMER_REL = "daemon/head/src/main/kotlin/splice/head/turn/TurnStreamer.kt"

class DaemonStopOrderTest {

    /** The repo root, found by the file this wall is about — walking up from the module dir the way
     *  ExampleConfigTest does. Failing to find it is a failure, never a skip. */
    private fun repoRoot(): Path {
        var dir: Path? = Paths.get("").toAbsolutePath()
        repeat(5) {
            val here = dir ?: return@repeat
            if (Files.exists(here.resolve(DAEMON_REL))) return here
            dir = here.parent
        }
        error("$DAEMON_REL not found walking up from ${Paths.get("").toAbsolutePath()}")
    }

    /** One production file with its COMMENTS REMOVED. A law about what the code does must not be
     *  satisfiable — or broken — by prose: every anchor below names a call, and a KDoc line that
     *  mentions the call by name would otherwise count as the call itself. */
    private fun code(rel: String): String {
        val path = repoRoot().resolve(rel)
        assertTrue(Files.exists(path), "$rel is missing — this wall's subject is absent, which cannot pass")
        return stripComments(Files.readString(path))
    }

    @Test
    fun `Daemon stop cancels the provider probe scope AFTER the heads have drained`() {
        val source = code(DAEMON_REL)
        val drain = requireOnce(source, "headShutdown.stopHeads(", DAEMON_REL)
        val cancelProbes = requireOnce(source, "controlPlane.cancelProbes()", DAEMON_REL)
        assertTrue(
            drain < cancelProbes,
            "$DAEMON_REL: controlPlane.cancelProbes() runs at offset $cancelProbes, BEFORE the head " +
                "drain at $drain. The probe scope is the scope ProviderAssembly hands every provider, " +
                "so cancelling it first means a SingleFlight token refresh raised by a turn still " +
                "streaming inside the 45s drain is cancelled by a job that turn does not own — a " +
                "foreign CancellationException in a live turn. Cancel the probes AFTER stopHeads returns.",
        )
    }

    @Test
    fun `HeadServer stop drains detached compactions within the budget and ends only what is still running`() {
        val source = code(HEAD_SERVER_REL)
        val drainLoop = requireOnce(source, "while (inflight > 0", HEAD_SERVER_REL)
        val stopDetached = requireOnce(source, "driver.stopDetached()", HEAD_SERVER_REL)
        assertTrue(
            drainLoop < stopDetached,
            "$HEAD_SERVER_REL: driver.stopDetached() runs at offset $stopDetached, BEFORE the in-flight " +
                "drain loop at $drainLoop. A detached compaction OUTLIVES its client: its handed-off " +
                "slot travels with the drive and the drain budget belongs to that feature — a detached " +
                "compaction that finishes inside the budget releases its slot and keeps its recording " +
                "for the retry. Ending detached compactions first makes the client's retry start a " +
                "SECOND upstream turn. Drain first; stopDetached ends only what is STILL running once " +
                "the budget is spent.",
        )
    }

    @Test
    fun `the teardown does not discard whether the file lane actually flushed`() {
        val source = code(MAIN_REL)
        val statement = requireOnce(source, "AsyncFileIo.drain()", MAIN_REL)
        val line = source.lineContaining(statement)
        assertFalse(
            line.trim() == "AsyncFileIo.drain()",
            "$MAIN_REL: AsyncFileIo.drain()'s Boolean is DISCARDED — the call stands alone as a " +
                "statement. A false means the file lane did not flush inside its timeout, so " +
                "daemon.log, usage and economics writes were lost on the way out, and this is the last " +
                "point before lock.close() and the halt watchdog where that loss is still reportable. " +
                "Consume it: log the failure, do not drop it.",
        )
        val lockClose = requireOnce(source, "lock.close()", MAIN_REL)
        assertTrue(
            statement < lockClose,
            "$MAIN_REL: the file-lane drain must precede lock.close(), or the lock is released while " +
                "writes are still queued and a restarting daemon races them",
        )
    }

    @Test
    fun `TurnStreamer's detached-scope guard is either live or gone, never dead`() {
        val source = code(TURN_STREAMER_REL)
        val guard = "detachedScope.isActive"
        val cancelsScope = source.contains("detachedScope.cancel()") ||
            source.contains("detachedScope.coroutineContext.cancel()")
        val claim = "a cancelled scope launches nothing"
        val claimSurvives = Files.readString(repoRoot().resolve(TURN_STREAMER_REL)).contains(claim)
        if (cancelsScope) return // the guard is live: something really does cancel the scope.
        assertFalse(
            source.contains(guard),
            "$TURN_STREAMER_REL: `$guard` guards the FrameRecording, but nothing cancels that scope — " +
                "stopDetached() calls cancelChildren(), which cancels the children and leaves the scope " +
                "active, and no other caller touches it. The guard can never be false, so the branch " +
                "behind it is dead. Either cancel the scope from the head-stop path, or delete the " +
                "guard and the header claim with it.",
        )
        assertFalse(
            claimSurvives,
            "$TURN_STREAMER_REL: the guard is gone but the header still claims \"$claim\" as the reason " +
                "the scope survives a stop. A comment that describes a branch the code no longer has is " +
                "the next reader's wrong premise — remove the claim with the guard.",
        )
    }

    /** THE READER'S OWN RED-GREEN PROOF. Both predicates above reduce to "anchor A precedes anchor
     *  B in the comment-stripped source" and "a required anchor is present". A reader that stopped
     *  matching would report green forever, so the same two predicates are applied here to synthetic
     *  sources whose verdict is known — including the boring case, a source with nothing in it. */
    @Test
    fun `the order predicate itself fails on a synthetic violation and passes on its compliant twin`() {
        val compliant = "fun stop() {\n    headShutdown.stopHeads(a)\n    controlPlane.cancelProbes()\n}"
        val violating = "fun stop() {\n    controlPlane.cancelProbes()\n    headShutdown.stopHeads(a)\n}"
        assertTrue(
            precedes(compliant, "headShutdown.stopHeads(", "controlPlane.cancelProbes()"),
            "the predicate must hold on the compliant twin, or every green above is meaningless",
        )
        assertFalse(
            precedes(violating, "headShutdown.stopHeads(", "controlPlane.cancelProbes()"),
            "the predicate must FAIL on the synthetic violation — a wall that cannot fail is not a wall",
        )
        // The BORING case: an empty source, where a reader that treats "not found" as "in order"
        // passes vacuously. requireOnce must refuse it instead.
        assertFalse(
            runCatching { requireOnce("", "headShutdown.stopHeads(", "synthetic") }.isSuccess,
            "an absent anchor must FAIL by name, never count as satisfied — an empty source is the " +
                "case a source reader gets waved through on",
        )
        assertFalse(
            runCatching { requireOnce("a()\na()\n", "a()", "synthetic") }.isSuccess,
            "an anchor matching twice must FAIL: 'the first occurrence' is not an order law, and a " +
                "duplicated call site means the wall is pinning the wrong one",
        )
        // Comments must not satisfy an anchor, or the fix could be a rewritten KDoc.
        assertFalse(
            stripComments("// controlPlane.cancelProbes()\n/* headShutdown.stopHeads( */\n")
                .contains("cancelProbes"),
            "an anchor named only in a comment must not count as the call",
        )
    }
}

/** The offset of [anchor] in [source], asserting it appears EXACTLY once. Absence and duplication
 *  both fail by name: an order law over an anchor that is missing, or over one of several identical
 *  call sites, is not a law. */
private fun requireOnce(source: String, anchor: String, where: String): Int {
    val first = source.indexOf(anchor)
    check(first >= 0) {
        "$where: anchor `$anchor` not found — this wall's subject moved or was renamed, so its " +
            "silence means nothing. Re-point the anchor rather than deleting the law."
    }
    val second = source.indexOf(anchor, first + 1)
    check(second < 0) {
        "$where: anchor `$anchor` appears more than once (offsets $first and $second) — an order " +
            "assertion over one of several identical call sites pins whichever happens to be first"
    }
    return first
}

private fun precedes(source: String, first: String, second: String): Boolean =
    requireOnce(source, first, "predicate-proof") < requireOnce(source, second, "predicate-proof")

private fun String.lineContaining(offset: Int): String {
    val start = lastIndexOf('\n', offset).let { if (it < 0) 0 else it + 1 }
    val end = indexOf('\n', offset).let { if (it < 0) length else it }
    return substring(start, end)
}

private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
private val LINE_COMMENT = Regex("""//[^\n]*""")

/** Comment removal in two regexes, block-first. Each comment becomes an equal run of spaces,
 *  newlines kept, so every offset above still names the same position in the original file.
 *
 *  Sufficient here because the four observed files carry none of the constructs that would mis-strip
 *  under two regexes (a comment marker inside a string literal, a block marker inside a line comment,
 *  or a raw string). A strip that lost or kept an anchor fails [requireOnce] by name, so the reader
 *  stays honest without a character-state lexer. */
private fun stripComments(source: String): String {
    val withoutBlocks = BLOCK_COMMENT.replace(source) { blanked(it.value) }
    return LINE_COMMENT.replace(withoutBlocks) { blanked(it.value) }
}

private fun blanked(comment: String): String =
    String(CharArray(comment.length) { if (comment[it] == '\n') '\n' else ' ' })
