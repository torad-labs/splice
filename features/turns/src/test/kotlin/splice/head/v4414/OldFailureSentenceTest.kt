// NEW: V4-414 — a failure recorded before V4-404 still explains itself. V4-404 writes the sentence when a turn
// is recorded (TurnTelemetry.closeTrace), so every turn on disk from before it has none, and the trace route
// passed the stored field through: all 23 upstream-failed turns on the everyday Turns page showed the badge and
// then Timing, with no words (Marlin, 3839fcc06). A row with no stored sentence now answers the same table's
// sentence for its outcome, so a user's whole failure history is explained. The record on disk is not touched.
package splice.head.v4414

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.OutcomeTag
import splice.core.perf.OutcomeTags
import splice.core.perf.PerfSnapshot
import splice.core.storage.ActivityDays
import splice.core.turn.CONN_RESET_OUTCOME
import splice.core.turn.ErrorType
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import splice.head.TurnsHead
import splice.head.TurnsHeadLookup
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.trace.TraceQuery
import splice.head.trace.TraceRoute
import splice.head.turn.OutcomeSentences
import splice.head.wire.ClientInbound
import splice.head.wire.TraceStore
import splice.head.wire.TurnIdMint
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

private const val DAY = 1_789_725_600_000L
private const val SPOKEN = "the connection to chatgpt.com:443 closed mid-request"

class OldFailureSentenceTest {
    private val meta = TurnMeta(
        compact = false,
        showReasoning = ReasoningDisplay.TEXT,
        stream = true,
        originalModel = "claude-codex--m",
        upstreamModel = "m",
        clientMaxTokens = 16,
        effort = "high",
        summary = "detailed",
        budgetTokens = null,
    )

    /** Every tag a turn of this module can end on, from the tag sources: the fixed tags, the typed failures,
     *  and the two `error:<kind>` endings that are not fixed constants. */
    private fun everyTag(): List<String> =
        (
            OutcomeTag.entries.map { it.wire } +
                ErrorType.entries.map { OutcomeTags.failure(it) } +
                listOf(CONN_RESET_OUTCOME, OutcomeTags.error("stopped"))
            ).distinct()

    private fun store(root: Path): TraceStore {
        val ids = AtomicInteger()
        return TraceStore(
            ActivityDays(root, "codex", 7, WallClock { DAY }, ownerOnly = true),
            "codex",
            maxBodyChars = 8,
            now = WallClock { DAY },
            ids = TurnIdMint { "turn-${ids.incrementAndGet()}" },
        )
    }

    /** A turn ended on [tag] the way a daemon before V4-404 recorded it: no sentence unless it [spoke] one. */
    private fun TraceStore.end(tag: String, spoke: String? = null) {
        val trace = begin(meta, ClientInbound("POST", "/v1/messages", emptyMap(), "synthetic request"))
        spoke?.let(trace::failureSentence)
        trace.finish(tag, PerfSnapshot(emptyMap(), emptyMap()))
    }

    private fun route(root: Path): TraceRoute {
        val compact = object : HeadCompactSource {
            override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
        }
        val heads = TurnsHeadLookup { if (it == "codex") listOf(TurnsHead("codex", compact)) else emptyList() }
        return TraceRoute(heads, { root }, Dispatchers.Unconfined)
    }

    private suspend fun listed(root: Path): List<JsonObject> {
        val body = Json.parseToJsonElement(route(root).read("codex", TraceQuery("100", null, null)).body).jsonObject
        return body.getValue("turns").jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.sentence(): String? = getValue("failure_sentence").jsonPrimitive.contentOrNull

    private suspend fun opened(root: Path, id: String): JsonObject =
        Json.parseToJsonElement(route(root).read("codex", TraceQuery(null, null, id)).body).jsonObject

    @Test
    fun `every outcome a turn can end on answers its table sentence when none was stored`(@TempDir root: Path) =
        runBlocking {
            val tags = everyTag()
            val store = store(root)
            tags.forEach { store.end(it) }
            assertTrue(AsyncFileIo.drain())

            val served = listed(root).associate { it.getValue("outcome").jsonPrimitive.content to it.sentence() }

            assertEquals(tags.toSet(), served.keys)
            val silent = listOf(OutcomeTag.OK, OutcomeTag.CLIENT_ABORT, OutcomeTag.EMPTY_MESSAGE).map { it.wire }
            tags.forEach { tag ->
                if (tag in silent) {
                    assertNull(served.getValue(tag), "$tag is not a failure")
                } else {
                    val expected = requireNotNull(OutcomeSentences.of(tag)) { "the table has no sentence for $tag" }
                    assertEquals(expected, served.getValue(tag), tag)
                }
            }
        }

    @Test
    fun `one turn opened by id answers the table sentence and leaves its record as written`(@TempDir root: Path) =
        runBlocking {
            val store = store(root)
            store.end(OutcomeTag.UPSTREAM_FAILED.wire)
            assertTrue(AsyncFileIo.drain())

            val body = opened(root, "turn-1")

            val sentence = body.getValue("turn").jsonObject.sentence()
            assertEquals(OutcomeSentences.of(OutcomeTag.UPSTREAM_FAILED.wire), sentence)
            assertTrue(sentence.orEmpty().contains("retry"), sentence)
            val closing = body.getValue("records").jsonArray.last().jsonObject
            assertFalse(closing.containsKey("failure_sentence"), "the summary is derived; the disk record is not")
        }

    @Test
    fun `a sentence a surface stored keeps its place over the table`(@TempDir root: Path) = runBlocking {
        val store = store(root)
        store.end(CONN_RESET_OUTCOME, spoke = SPOKEN)
        assertTrue(AsyncFileIo.drain())

        assertEquals(listOf(SPOKEN), listed(root).map { it.sentence() })
    }

    @Test
    fun `a turn still open and an ending nobody wrote a sentence for answer none`(@TempDir root: Path) = runBlocking {
        val store = store(root)
        val open = store.begin(meta, ClientInbound("POST", "/v1/messages", emptyMap(), "synthetic request"))
        open.wsAttempt("{}", emptyMap(), null)
        store.end("error:no-such-kind")
        assertTrue(AsyncFileIo.drain())

        val turns = listed(root)

        assertEquals(listOf(true, false), turns.map { it.getValue("open").jsonPrimitive.content.toBoolean() })
        assertEquals(listOf<String?>(null, null), turns.map { it.sentence() })
    }
}
