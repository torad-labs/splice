package splice.provider.codex

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText
import splice.upstream.RoundResult
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

@Timeout(25)
class CodeModeTerminalReasoningTest : CodeModeSealedItemSupport() {
    @ParameterizedTest
    @CsvSource("false,true", "true,true", "false,false", "true,false")
    fun `terminal-only reasoning joins the native tail only behind a matching exec anchor`(
        restart: Boolean,
        terminalExecAnchor: Boolean,
    ) = runTest(timeout = 20.seconds) {
        val effects = tempDir.resolve("effects")
        val attach = CompletableDeferred<Unit>()
        var manager = bridge(EffectRuntime(effects, attach))
        val post = SealedPost(
            status = "completed",
            followingReasoning = true,
            terminalReasoningOnly = true,
            terminalExecAnchor = terminalExecAnchor,
        )
        try {
            val source = SourceSink()
            val first = async {
                manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, source, post).turn()
            }
            post.itemRelease.complete(Unit)
            post.tailSent.await()
            assertEquals("A", source.text.toString(), "opaque terminal state is never client prose")
            assertEquals(0, source.opaqueBlocks)
            assertFalse(source.callback.isCompleted)
            attach.complete(Unit)
            assertTrue(checkNotNull(first.await() as? TurnOutcome.Success).hasToolUse)
            val read = source.callback.await()
            post.terminalRelease.complete(Unit)
            val finished = finishSource(manager, post, read)
            assertTrue(finished.outcome is TurnOutcome.Success)
            val input = if (restart) {
                manager.onHeadStop()
                manager = bridge(EffectRuntime(effects))
                restoredInput(manager, transcript(finished.calls, "A"))
            } else {
                Json.parseToJsonElement(post.continuation.await()).jsonObject.getValue("input").jsonArray
            }
            assertNativeOrder(input, post, terminalExecAnchor)
            assertEquals(listOf("executed"), Files.readAllLines(effects), "replay never repeats the source effect")
        } finally {
            attach.complete(Unit)
            post.terminalRelease.complete(Unit)
            manager.onHeadStop()
        }
    }

    private suspend fun restoredInput(manager: CodexCodeModeBridge, client: String): JsonArray {
        var posted: JsonObject? = null
        manager.interceptor(turn(), disableParallel = false).intercept(client, SourceSink()) {
            posted = Json.parseToJsonElement(it).jsonObject
            RoundResult.Outcome(completedOutcome())
        }
        return checkNotNull(posted).getValue("input").jsonArray
    }

    private fun assertNativeOrder(input: JsonArray, post: SealedPost, terminalExecAnchor: Boolean) {
        val calls = input.map { it.jsonObject }.filter { JsonScalars.str(it, "call_id") == post.completed.callId }
        assertEquals(listOf("custom_tool_call", "custom_tool_call_output"), calls.map { JsonScalars.str(it, "type") })
        val callAt = input.indexOf(calls.first())
        assertTrue(callAt >= 2)
        val tail = listOfNotNull(post.followingReason.takeIf { terminalExecAnchor })
        val expected = listOf(
            post.reason,
            ResponsesAssistantText.item("A", AssistantPhase.COMMENTARY),
            post.completed.raw,
        ) + tail + calls.last()
        assertEquals(JsonArray(expected), JsonArray(input.drop(callAt - 2)))
        assertEquals(1, input.count { it == post.reason })
        assertEquals(if (terminalExecAnchor) 1 else 0, input.count { it == post.followingReason })
        assertTrue(JsonScalars.str(calls.last(), "output").orEmpty().trimEnd().endsWith("saved-result:result-1"))
    }
}
