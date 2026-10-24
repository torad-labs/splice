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
class CodeModePostCallProseTest : CodeModeSealedItemSupport() {
    @ParameterizedTest
    @CsvSource("false,false", "true,false", "false,true", "true,true")
    fun `prose delivered after exec stays between its raw call and output warm and after restart`(
        restart: Boolean,
        nativeFollowing: Boolean,
    ) = runTest(timeout = 20.seconds) {
        val effects = tempDir.resolve("effects")
        val attach = CompletableDeferred<Unit>()
        var manager = bridge(EffectRuntime(effects, attach))
        val post = SealedPost("completed", suffix = "B", followingReasoning = nativeFollowing)
        val sink = SourceSink()
        try {
            val first = async {
                manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post).turn()
            }
            post.itemRelease.complete(Unit)
            post.tailSent.await()
            assertEquals("AB", sink.text.toString(), "both native message fragments reached the same client step")
            assertFalse(
                sink.callback.isCompleted,
                "the runtime cannot detach the client before the suffix is delivered",
            )
            attach.complete(Unit)
            assertTrue((first.await() as TurnOutcome.Success).hasToolUse)
            val read = sink.callback.await()
            post.terminalRelease.complete(Unit)
            val finished = finishSource(manager, post, read)
            assertTrue(finished.outcome is TurnOutcome.Success)
            val input = if (restart) {
                manager.onHeadStop()
                manager = bridge(EffectRuntime(effects))
                restoredInput(manager, transcript(finished.calls, "AB"))
            } else {
                Json.parseToJsonElement(post.continuation.await()).jsonObject.getValue("input").jsonArray
            }
            assertNativeSource(input, post)
            assertEquals(
                listOf("executed"),
                Files.readAllLines(effects),
                "history recovery never reruns source effects",
            )
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

    private fun assertNativeSource(input: JsonArray, post: SealedPost) {
        val calls = input.map { it.jsonObject }.filter { JsonScalars.str(it, "call_id") == post.completed.callId }
        assertEquals(listOf("custom_tool_call", "custom_tool_call_output"), calls.map { JsonScalars.str(it, "type") })
        val at = input.indexOf(calls.first())
        assertTrue(at >= 2, input.toString())
        val following = if (post.followingReasoning) {
            listOf(post.followingReason, post.followingMessage)
        } else {
            listOf(ResponsesAssistantText.item("B", AssistantPhase.COMMENTARY))
        }
        val expected = listOf(
            post.reason,
            ResponsesAssistantText.item("A", AssistantPhase.COMMENTARY),
            post.completed.raw,
        ) + following + calls.last()
        assertEquals(
            JsonArray(expected),
            JsonArray(input.drop(at - 2).take(expected.size)),
            "native order is A,exec,B,output",
        )
        assertTrue(JsonScalars.str(calls.last(), "output").orEmpty().trimEnd().endsWith("saved-result:result-1"))
        assertEquals(1, input.count { it == post.reason })
        if (post.followingReasoning) assertEquals(1, input.count { it == post.followingReason })
        assertEquals(listOf("A", "B"), input.mapNotNull(::assistantText), "every delivered prose byte appears once")
    }

    private fun assistantText(item: kotlinx.serialization.json.JsonElement): String? {
        val message = item as? JsonObject ?: return null
        if (JsonScalars.str(message, "role") != "assistant") return null
        return JsonScalars.str(message, "content") ?: (message["content"] as? JsonArray).orEmpty().joinToString("") {
            JsonScalars.str(it as? JsonObject, "text").orEmpty()
        }
    }
}
