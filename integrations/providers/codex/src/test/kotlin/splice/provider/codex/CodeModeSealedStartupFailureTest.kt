package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import splice.core.turn.TurnOutcome
import splice.provider.codex.stream.CodeModeSourceBuffer
import splice.provider.codex.stream.CodeModeSourceState
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

/** Item acceptance precedes worker scheduling; late accounting failure cannot revoke that acceptance. */
class CodeModeSealedStartupFailureTest : CodeModeItemCompletionSupport() {
    @Test
    @Timeout(25)
    fun `a sealed item retains its effect and owned callbacks when provider failure wins worker scheduling`() =
        runTest(timeout = 20.seconds) {
            val effects = tempDir.resolve("effects-before-claim")
            val evaluated = EffectRuntime(effects)
            val entered = CompletableDeferred<Unit>()
            val claim = CompletableDeferred<Unit>()
            val runtime = object : CodeModeRuntime by evaluated {
                override suspend fun startStreamingSession(
                    sessionKey: String,
                    source: CodeModeSource,
                    tools: Set<String>,
                    descriptions: Map<String, String>,
                ): CodeModeCell {
                    entered.complete(Unit)
                    claim.await()
                    return evaluated.startStreamingSession(sessionKey, source, tools, descriptions)
                }
            }
            val manager = bridge(runtime)
            val post = ItemPost("failure")
            val firstSink = StepSink()
            val first = async {
                manager.interceptor(turn(), disableParallel = false)
                    .intercept(BASE_REQUEST, firstSink, post).turn()
            }
            try {
                post.prefixSent.await()
                entered.await()
                assertFalse(Files.exists(effects), "a prefix cannot execute while startup has no whole item")
                assertFalse(firstSink.callback.isCompleted)

                post.itemRelease.complete(Unit)
                post.itemSealed.await()
                post.terminalRelease.complete(Unit)
                post.stopped.await()
                awaitSettledItem()
                assertFalse(Files.exists(effects), "the worker is still gated before its source claim")
                assertFalse(firstSink.callback.isCompleted)

                claim.complete(Unit)
                assertTrue(
                    first.await() is TurnOutcome.Success,
                    "the accepted item must expose its callback even when failure preceded the worker claim",
                )
                assertTrue(firstSink.callback.isCompleted, "one owned callback must reach the client")
                val read = firstSink.callback.await()
                assertEquals("Read", read.name)
                assertEquals(listOf("executed"), Files.readAllLines(effects))

                assertOwnedRetryAndResume(manager, post, read)
                assertEquals(listOf("executed"), Files.readAllLines(effects), "retry and resume cannot rerun effects")
                assertFalse(
                    post.continuation.isCompleted,
                    "owned callbacks do not replace the failed response terminal",
                )
            } finally {
                manager.onHeadStop()
                first.cancelAndJoin()
            }
        }

    private suspend fun assertOwnedRetryAndResume(
        manager: CodexCodeModeBridge,
        post: ItemPost,
        read: SeenTool,
    ) {
        val retry = StepSink()
        manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, retry, post)
        val replayed = retry.callback.await()
        assertEquals(read.id, replayed.id, "an exact retry keeps the owned callback id")
        assertEquals(read.name, replayed.name)
        assertEquals(read.args.toString(), replayed.args.toString())

        val secondSink = StepSink()
        manager.interceptor(turn(read.id, "result-0"), disableParallel = false)
            .intercept(history(listOf(read)), secondSink, post)
        assertEquals(
            "Edit",
            secondSink.callback.await().name,
            "the saved callback result resumes the same script",
        )
    }

    @Test
    fun `failure after item sealing but before startup entry does not revoke the accepted whole source`() = runTest {
        val source = CodeModeSourceBuffer()
        source.publish("accepted prefix")
        source.complete("accepted prefix and suffix")
        source.fail("synthetic late provider failure before startup entry")
        var delivered: CodeModeSourcePart? = null
        val cell = ScriptedCell(ArrayDeque())
        val started = source.whileStarting {
            delivered = source.view().read()
            cell
        }
        try {
            assertTrue(started === cell)
            assertEquals(CodeModeSourcePart.Complete("accepted prefix and suffix"), delivered)
            assertFalse(source.startupRejected)
        } finally {
            cell.close()
        }
    }

    /** Wait for the production reader's durable settlement, not merely the fixture's return from into(). */
    private suspend fun awaitSettledItem() = withTimeout(5.seconds) {
        while (true) {
            val source = Json.decodeFromJsonElement(
                CodeModeSourceState.serializer(),
                stateFiles.records().single().getValue("sourceState"),
            )
            if (source.complete) {
                assertTrue(source.itemComplete, "durable settlement must retain the accepted whole item")
                break
            }
            yield()
        }
    }
}
