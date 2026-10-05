package splice.head.v4349

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.perf.PerfSnapshot
import splice.core.storage.ActivityDays
import splice.core.turn.FailureCause
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import splice.head.TurnsHead
import splice.head.TurnsHeadLookup
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.trace.TraceFailureCause
import splice.head.trace.TraceQuery
import splice.head.trace.TraceRoute
import splice.head.wire.ClientInbound
import splice.head.wire.TraceStore
import splice.head.wire.TurnIdMint
import java.nio.file.Files
import java.nio.file.Path

private const val TRACE_DAY = 1_789_725_600_000L
private const val FAILURE = "the connection to chatgpt.com:443 closed mid-request"

class TraceFailureSentenceTest {
    @Test
    fun `old refusal bodies follow the decoded cause with and without retained provider text`(@TempDir root: Path) =
        runBlocking {
            val stale = "the provider failed on its side; retry in a moment"
            val cyber = """{"type":"response.failed","response":{"error":{"code":"cyber_policy","message":"this synthetic request was flagged. Try rephrasing."}}}"""
            val words = "OpenAI refused the request under its cybersecurity check. " +
                "This synthetic request was flagged. Try rephrasing."
            val cases = listOf(
                Triple(FailureCause.CONTENT_FILTERED, cyber, words),
                Triple(FailureCause.CONTENT_FILTERED, "data: $cyber\n\n", words),
                Triple(
                    FailureCause.CONTENT_FILTERED,
                    """{"type":"error","code":"bio_policy","message":"this synthetic request was refused"}""",
                    "the provider stopped the answer under its content check; ask for a different task",
                ),
                Triple(
                    FailureCause.CONTENT_FILTERED,
                    cyber + "\n" + """{"type":"response.completed","response":{"status":"completed"}}""",
                    "the provider stopped the answer under its content check; ask for a different task",
                ),
                Triple(
                    FailureCause.CONTENT_FILTERED,
                    null,
                    "the provider stopped the answer under its content check; ask for a different task",
                ),
                Triple(FailureCause.MODEL_REFUSED, null, "the model declined to answer; ask for a different task"),
                Triple(FailureCause.UPSTREAM_STATUS_5XX, null, stale),
            )
            cases.forEachIndexed { index, (cause, response, expected) ->
                val directory = Files.createDirectory(root.resolve("case-$index"))
                val body = oldRead(directory, cause, response, stale)
                assertEquals(expected, body.getValue("turn").jsonObject["failure_sentence"]?.jsonPrimitive?.content)
                assertEquals(cause.name, body.getValue("turn").jsonObject["cause"]?.jsonPrimitive?.content)
                assertEquals(
                    stale,
                    body.getValue("records").jsonArray.last().jsonObject["failure_sentence"]?.jsonPrimitive?.content,
                )
            }
        }

    private suspend fun oldRead(root: Path, cause: FailureCause, response: String?, sentence: String): JsonObject {
        val attempt = buildJsonObject {
            put("kind", "attempt")
            put("turn", "old-refusal")
            put("ts", TRACE_DAY)
            put("model", "m")
            put("attempt", 1)
            put("durationMs", 20)
            put("transport", "ws")
            putJsonObject("response") {
                if (response == null) putJsonObject("text") { put("unavailable", true) } else put("text", response)
            }
        }
        val ending = buildJsonObject {
            put("kind", "turn")
            put("turn", "old-refusal")
            put("ts", TRACE_DAY)
            put("model", "m")
            put("outcome", "failure:api_error")
            put("failure_sentence", sentence)
            put("rounds", 1)
            put("attempts", 1)
        }
        val stored = "$attempt\n$ending\n"
        val file = root.resolve("codex-2026-09-18.jsonl")
        Files.writeString(file, stored)
        val compact = object : HeadCompactSource {
            override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
        }
        val heads = TurnsHeadLookup { listOf(TurnsHead("codex", compact)) }
        val route = TraceRoute(
            heads,
            { root },
            Dispatchers.Unconfined,
            TraceFailureCause { head, turn, since ->
                assertEquals("codex", head)
                assertEquals("old-refusal", turn)
                assertEquals(TRACE_DAY - 20, since)
                cause
            },
        )
        val reply = route.read("codex", TraceQuery(null, null, "old-refusal"))
        assertEquals(HttpStatusCode.OK, reply.status)
        assertEquals(stored, Files.readString(file), "read-time corrections never rewrite the trace")
        return Json.parseToJsonElement(reply.body).jsonObject
    }

    @Test
    fun `one failed turn serves its spoken failure under the outcome`(@TempDir root: Path) = runBlocking {
        val store = TraceStore(
            ActivityDays(root, "codex", 7, WallClock { TRACE_DAY }, ownerOnly = true),
            "codex",
            maxBodyChars = 8, // body limits must not cut the human failure sentence
            now = WallClock { TRACE_DAY },
            ids = TurnIdMint { "turn-1" },
        )
        val meta = TurnMeta(
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
