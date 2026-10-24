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
import org.junit.jupiter.params.provider.ValueSource
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

// why: callback issuance, terminal accounting and completion can each persist a tail; repeated growing prefixes cannot.
private const val TAIL_JOURNAL_FACTOR = 8L

// why: admission, source, callback identities and usage metadata are independent of the model's native tail bytes.
private const val TAIL_JOURNAL_METADATA_BYTES = 64L * 1024

@Timeout(45)
class CodeModeTailJournalTest : CodeModeTailJournalSupport() {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `native tail journal bytes stay bounded and complete history survives warm continuation and restart`(
        restart: Boolean,
    ) = runTest(timeout = 40.seconds) {
        val effects = tempDir.resolve("effects")
        val attach = CompletableDeferred<Unit>()
        val runtime = EffectRuntime(effects, attach)
        var manager = bridge(runtime)
        val post = TailPost()
        val sink = SourceSink()
        try {
            val first = async {
                manager.interceptor(turn(), disableParallel = false).intercept(BASE_REQUEST, sink, post).turn()
            }
            post.tailSent.await()
            runtime.evaluated.await()
            assertEquals(post.text, sink.text.toString())
            assertFalse(sink.callback.isCompleted, "the reader finishes all tail items before the runtime may detach")
            attach.complete(Unit)
            assertTrue((first.await() as TurnOutcome.Success).hasToolUse)
            post.journal.observe()
            val read = sink.callback.await()
            post.terminalRelease.complete(Unit)
            val finished = completeTailSource(manager, post, read)
            assertTrue(finished.outcome is TurnOutcome.Success)
            val input = if (restart) {
                manager.onHeadStop()
                manager = bridge(EffectRuntime(effects))
                restoredTailInput(manager, transcript(finished.calls, post.text))
            } else {
                Json.parseToJsonElement(post.continuation.await()).jsonObject.getValue("input").jsonArray
            }
            post.journal.observe()
            assertNativeTail(input, post)
            assertEquals(listOf("executed"), Files.readAllLines(effects), "history recovery never reruns source")
            assertJournalBound(post)
        } finally {
            attach.complete(Unit)
            post.terminalRelease.complete(Unit)
            manager.onHeadStop()
        }
    }

    private fun assertNativeTail(input: JsonArray, post: TailPost) {
        val at = input.indexOf(post.completed.raw)
        assertTrue(at >= 2, "the original raw exec remains after its native preface")
        val output = input.filterIsInstance<JsonObject>().single {
            JsonScalars.str(it, "type") == "custom_tool_call_output" &&
                JsonScalars.str(it, "call_id") == post.completed.callId
        }
        val expected = listOf(post.reason, canonicalPreface(), post.completed.raw) + post.tail + output
        assertEquals(JsonArray(expected), JsonArray(input.drop(at - 2).take(expected.size)))
        assertEquals(1, input.count { it == post.completed.raw })
        post.tail.forEach {
            assertEquals(
                1,
                input.count { item -> item == it },
                "each raw tail item survives exactly once",
            )
        }
        assertTrue(JsonScalars.str(output, "output").orEmpty().trimEnd().endsWith("saved-result:result-1"))
    }

    private fun assertJournalBound(post: TailPost) {
        val tailBytes = JsonWire.byteSize(JsonArray(post.tail))
        val limit = tailBytes * TAIL_JOURNAL_FACTOR + TAIL_JOURNAL_METADATA_BYTES
        assertTrue(
            post.journal.persisted <= limit,
            "persisted=${post.journal.persisted} bytes for a $tailBytes-byte native tail; bound=$limit",
        )
    }
}
