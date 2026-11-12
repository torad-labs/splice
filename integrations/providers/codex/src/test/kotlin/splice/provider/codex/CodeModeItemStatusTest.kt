package splice.provider.codex

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import splice.core.turn.TurnOutcome
import splice.upstream.RoundResult
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

@Timeout(25)
class CodeModeItemStatusTest : CodeModeSealedItemSupport() {
    @ParameterizedTest
    @CsvSource(
        "incomplete,live",
        "in_progress,live",
        "failed,live",
        "cancelled,live",
        "incomplete,terminal",
        "in_progress,terminal",
        "failed,terminal",
        "cancelled,terminal",
    )
    fun `an explicitly unfinished custom item never executes or exposes a callback`(
        status: String,
        delivery: String,
    ) = runTest(timeout = 20.seconds) {
        val effects = tempDir.resolve("effects")
        val manager = bridge(EffectRuntime(effects))
        val post = SealedPost(status, ending = "incomplete")
        val sink = SourceSink()
        try {
            val outcome = if (delivery == "live") {
                val running = async {
                    manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post).turn()
                }
                post.itemRelease.complete(Unit)
                post.itemHandled.await()
                post.terminalRelease.complete(Unit)
                running.await()
            } else {
                manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink) {
                    RoundResult.Outcome(post.terminalOutcome(incomplete = true))
                }.turn()
            }
            assertFalse(Files.exists(effects), "explicit status=$status cannot certify the syntactically valid source")
            assertFalse(sink.callback.isCompleted, "an unfinished source cannot issue client tools")
            if (outcome is TurnOutcome.Success) {
                assertTrue(
                    outcome.incomplete,
                )
            } else {
                assertTrue(outcome is TurnOutcome.Failure)
            }
        } finally {
            post.terminalRelease.complete(Unit)
            manager.onHeadStop()
        }
    }

    @ParameterizedTest
    @CsvSource("legacy,success", "completed,success", "legacy,io", "completed,io")
    fun `complete and legacy items execute eagerly once even if their reader later tears`(
        status: String,
        ending: String,
    ) = runTest(timeout = 20.seconds) {
        val effects = tempDir.resolve("effects")
        val manager = bridge(EffectRuntime(effects))
        val post = SealedPost(status.takeUnless { it == "legacy" }, ending = ending)
        val sink = SourceSink()
        try {
            post.itemRelease.complete(Unit)
            val first = manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post).turn()
            assertTrue((first as TurnOutcome.Success).hasToolUse)
            val read = sink.callback.await()
            assertFalse(post.stopped.isCompleted, "certified whole items execute before the response terminal")
            post.terminalRelease.complete(Unit)
            post.stopped.await()
            val retried = SourceSink()
            manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, retried, post)
            assertEquals(read.id, retried.callback.await().id, "reconnect retains the issued callback")
            val finished = finishSource(manager, post, read)
            if (ending == "io") {
                assertTrue(finished.outcome is TurnOutcome.Failure)
            } else {
                assertTrue(finished.outcome is TurnOutcome.Success)
            }
            assertEquals(listOf("executed"), Files.readAllLines(effects), "a late failure cannot repeat effects")
        } finally {
            post.terminalRelease.complete(Unit)
            manager.onHeadStop()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["legacy", "completed"])
    fun `complete and legacy terminal-only items retain whole-source admission`(status: String) =
        runTest(timeout = 20.seconds) {
            val effects = tempDir.resolve("effects")
            val manager = bridge(EffectRuntime(effects))
            val post = SealedPost(status.takeUnless { it == "legacy" })
            val sink = SourceSink()
            try {
                val outcome = manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink) {
                    RoundResult.Outcome(post.terminalOutcome())
                }.turn()
                assertTrue((outcome as TurnOutcome.Success).hasToolUse)
                assertTrue(sink.callback.isCompleted)
                assertEquals(listOf("executed"), Files.readAllLines(effects))
            } finally {
                manager.onHeadStop()
            }
        }
}
