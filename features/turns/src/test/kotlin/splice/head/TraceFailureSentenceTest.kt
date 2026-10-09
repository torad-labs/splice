package splice.head

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.PerfSnapshot
import splice.core.storage.ActivityDays
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.TurnReasoning
import splice.core.turn.TurnRoute
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.trace.TraceQuery
import splice.head.trace.TraceRoute
import splice.head.wire.ClientInbound
import splice.head.wire.TurnIdMint
import java.nio.file.Path

private const val TRACE_DAY = 1_789_725_600_000L
private const val FAILURE = "the connection to chatgpt.com:443 closed mid-request"

class TraceFailureSentenceTest {
    @Test
    fun `one failed turn serves its spoken failure under the outcome`(@TempDir root: Path) = runBlocking {
        val store = splice.head.syntheticTraceStore(
            ActivityDays(root, "codex", 7, WallClock { TRACE_DAY }, ownerOnly = true),
            "codex",
            maxBodyChars = 8, // body limits must not cut the human failure sentence
            now = WallClock { TRACE_DAY },
            ids = TurnIdMint { "turn-1" },
        )
        val meta = TurnMeta(
            compact = false,
            reasoning = TurnReasoning(
                showReasoning = ReasoningDisplay.TEXT,
                effort = "high",
                summary = "detailed",
                budgetTokens = null,
            ),
            route = TurnRoute(
                stream = true,
                originalModel = "claude-codex--m",
                upstreamModel = "m",
                clientMaxTokens = 16,
            ),
        )
        val trace = store.begin(meta, ClientInbound("POST", "/v1/messages", emptyMap(), "synthetic request"))
        trace.failureSentence(FAILURE)
        trace.finish("error:conn-reset", PerfSnapshot(emptyMap(), emptyMap()))
        assertTrue(AsyncFileIo.drain())
        val compact = object : HeadCompactSource {
            override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
        }
        val heads = TurnsHeadLookup { if (it == "codex") listOf(TurnsHead("codex", compact)) else emptyList() }
        val route = TraceRoute(heads, { root }, Dispatchers.Unconfined)
        val response = route.read("codex", TraceQuery(null, null, "turn-1"))
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.body).jsonObject
        assertEquals(FAILURE, body.getValue("turn").jsonObject["failure_sentence"]?.jsonPrimitive?.content)
        assertEquals(
            FAILURE,
            body.getValue("records").jsonArray.last().jsonObject["failure_sentence"]?.jsonPrimitive?.content,
        )
    }
}
