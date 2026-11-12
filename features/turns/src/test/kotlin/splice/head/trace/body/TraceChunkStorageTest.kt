package splice.head.trace.body

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
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
import splice.core.turn.TurnScope
import splice.core.util.AsyncFileIo
import splice.core.util.JsonlSink
import splice.core.util.WallClock
import splice.head.trace.TraceAsk
import splice.head.trace.TraceRows
import splice.head.wire.ClientInbound
import splice.head.wire.TraceStore
import splice.head.wire.TurnIdMint
import splice.upstream.sse.WireAttempt
import splice.upstream.sse.WireRequest
import splice.upstream.sse.WireResponse
import java.nio.file.Files
import java.nio.file.Path

private const val NOW = 1_789_725_600_000L
private const val HEAD = "synthetic"
private const val DAY = "synthetic-2026-09-18.jsonl"

class TraceChunkStorageTest {
    @Test
    fun `three growing turns round trip exactly while common bodies and prefixes occupy one daily pack`(
        @TempDir dir: Path,
    ) {
        val base = buildString {
            repeat(6_000) { append("synthetic-$it café 東京 λ 🧭 \" \\ \n") }
        }
        var prefix = base
        val store = store(dir, base.length + 1_000)
        val expected = (1..3).map { number ->
            prefix += "synthetic continuation $number\n"
            val answer = "synthetic answer $number Ω\n"
            write(store, prefix, answer)
            prefix to answer
        }
        assertTrue(AsyncFileIo.drain())
        val turns = TraceRows(heap = splice.head.syntheticHeapBudget()).turns(dir, HEAD, TraceAsk(last = 3))
        assertEquals(3, turns.size)
        turns.zip(expected).forEach { (turn, bodies) ->
            assertEquals(bodies.first, turn.attempts.single().obj("request").text("body"))
            assertEquals(bodies.first, turn.turn?.obj("client")?.text("body"))
            assertEquals(bodies.second, turn.turn?.obj("answer")?.text("body"))
            assertEquals(bodies.second, turn.attempts.single().obj("response").text("text"))
        }
        val raw = Files.readAllLines(dir.resolve(DAY)).map { Json.parseToJsonElement(it).jsonObject }
        val requests = raw.filter { it.text("kind") == "attempt" }.map { it.obj("request").obj("body") }
        val clients = raw.filter { it.text("kind") == "turn" }.map { it.obj("client").obj("body") }
        assertEquals(requests, clients, "identical client and upstream literals share references")
        requests.zipWithNext().forEach { (before, after) ->
            val prefixParts = before.getValue("parts").jsonArray.dropLast(1)
            assertEquals(prefixParts, after.getValue("parts").jsonArray.take(prefixParts.size))
        }
        val inline = dir.resolve("inline-baseline.jsonl")
        turns.flatMap { it.attempts + listOfNotNull(it.turn) }.forEach {
            JsonlSink.appendLine(inline, it.toString())
        }
        val inlineBytes = Files.size(inline)
        val storedBytes = Files.size(dir.resolve(DAY)) + Files.size(dir.resolve("$DAY.bodies2"))
        assertTrue(storedBytes < inlineBytes / 2, "stored=$storedBytes inline=$inlineBytes")
        println("TRACE_STORAGE turns=3 inline_bytes=$inlineBytes stored_bytes=$storedBytes")
    }

    @Test
    fun `a passthrough envelope edit stores unchanged client and upstream chunks once`(@TempDir dir: Path) {
        val messages = JsonArray(
            (0 until 24).map { number ->
                JsonObject(
                    mapOf(
                        "role" to JsonPrimitive(if (number % 2 == 0) "user" else "assistant"),
                        "content" to JsonPrimitive(
                            buildString {
                                repeat(300) { line -> append("synthetic-$number-$line café 東京 λ 🧭 \" \\ \n") }
                            },
                        ),
                    ),
                )
            },
        )
        val client = JsonObject(
            linkedMapOf(
                "model" to JsonPrimitive("claude-synthetic--model"),
                "messages" to messages,
                "system" to JsonPrimitive("synthetic client guidance"),
                "tools" to JsonArray(emptyList()),
            ),
        ).toString()
        val upstream = JsonObject(
            linkedMapOf(
                "model" to JsonPrimitive("model"),
                "stream" to JsonPrimitive(true),
                "system" to JsonPrimitive("synthetic client guidance with head instructions"),
                "messages" to messages,
                "tools" to JsonArray(listOf(JsonObject(mapOf("name" to JsonPrimitive("synthetic_tool"))))),
            ),
        ).toString()
        write(store(dir), client, "synthetic answer", upstream)
        assertTrue(AsyncFileIo.drain())
        val raw = Files.readAllLines(dir.resolve(DAY)).map { Json.parseToJsonElement(it).jsonObject }
        val clientParts = raw.single { it.text("kind") == "turn" }.obj("client").obj("body")
            .getValue("parts").jsonArray
        val requestParts = raw.single { it.text("kind") == "attempt" }.obj("request").obj("body")
            .getValue("parts").jsonArray
        val shared = clientParts.filter { it in requestParts }
        val sharedBytes = shared.sumOf { it.jsonObject.getValue("bytes").jsonPrimitive.int }
        val allBytes = clientParts.sumOf { it.jsonObject.getValue("bytes").jsonPrimitive.int }
        assertTrue(shared.size >= clientParts.size * 3 / 4, "unchanged chunks must reuse their actual pack offsets")
        assertTrue(sharedBytes >= allBytes * 3 / 4, "unchanged payload bytes must be stored once")
        val hydrated = TraceRows(heap = splice.head.syntheticHeapBudget()).turns(dir, HEAD, TraceAsk(last = 1)).single()
        assertEquals(client, hydrated.turn?.obj("client")?.text("body"), "client bytes round trip exactly")
        assertEquals(upstream, hydrated.attempts.single().obj("request").text("body"), "upstream bytes are unchanged")
        println("TRACE_PASSTHROUGH_STORAGE shared=${shared.size}/${clientParts.size} bytes=$sharedBytes")
    }

    @Test
    fun `legacy and chunked records mix and truncation preserves exact non ASCII code units`(@TempDir dir: Path) {
        val legacy =
            """{"kind":"turn","turn":"legacy","ts":$NOW,"head":"synthetic","model":"m",""" +
                """"clientModel":"m","compact":false,"client":{"body":"old café"},"answer":{"body":"old λ"}}"""
        Files.writeString(dir.resolve(DAY), legacy + "\n")
        val text = "a😀🚀東京"
        write(store(dir, 4), text, text)
        assertTrue(AsyncFileIo.drain())
        val turns = TraceRows(heap = splice.head.syntheticHeapBudget()).turns(dir, HEAD, TraceAsk(last = 2))
        assertEquals("old café", turns.first().turn?.obj("client")?.text("body"))
        val recent = checkNotNull(turns.last().turn)
        assertEquals(text.take(4), recent.obj("client").text("body"))
        assertEquals(text.take(4), recent.obj("answer").text("body"))
        assertEquals("true", recent.obj("client").text("truncated"))
        assertEquals("true", recent.obj("answer").text("truncated"))
    }

    @Test
    fun `a missing body pack keeps metadata and explicit unavailable references`(@TempDir dir: Path) {
        write(store(dir), "synthetic request", "synthetic answer")
        assertTrue(AsyncFileIo.drain())
        assertTrue(Files.exists(dir.resolve("$DAY.bodies2")))
        Files.delete(dir.resolve("$DAY.bodies2"))
        val read = TraceRows(heap = splice.head.syntheticHeapBudget()).read(dir, HEAD, TraceAsk(last = 1))
        assertEquals(2, read.unavailableRecords)
        val body = read.turns.single().turn?.obj("client")?.obj("body")
        assertEquals("true", body?.text("unavailable"))
        assertEquals("m", read.turns.single().model)
    }

    @Test
    fun `a corrupt chunk keeps metadata and never substitutes empty successful text`(@TempDir dir: Path) {
        write(store(dir), "synthetic request", "synthetic answer")
        assertTrue(AsyncFileIo.drain())
        val pack = dir.resolve("$DAY.bodies2")
        assertTrue(Files.exists(pack))
        val bytes = Files.readAllBytes(pack)
        bytes[bytes.lastIndex - 1] = 'S'.code.toByte()
        Files.write(pack, bytes)
        val read = TraceRows(heap = splice.head.syntheticHeapBudget()).read(dir, HEAD, TraceAsk(last = 1))
        assertEquals(2, read.unavailableRecords)
        val body = read.turns.single().turn?.obj("answer")?.obj("body")
        assertEquals("true", body?.text("unavailable"))
        assertEquals("m", read.turns.single().model)
    }

    private fun store(dir: Path, max: Int = 1 shl 20): TraceStore {
        var id = 0
        return splice.head.syntheticTraceStore(
            ActivityDays(dir, HEAD, 7, WallClock { NOW }, true),
            HEAD,
            max,
            WallClock { NOW },
            TurnIdMint { "chunk-turn-${++id}" },
        )
    }

    private fun write(store: TraceStore, request: String, answer: String, upstream: String = request) {
        val meta = TurnMeta(
            compact = false,
            reasoning = TurnReasoning(
                showReasoning = ReasoningDisplay.OFF,
                effort = "high",
                summary = null,
                budgetTokens = null,
            ),
            route = TurnRoute(stream = true, originalModel = "m", upstreamModel = "m", clientMaxTokens = 100),
            scope = TurnScope(sessionId = "synthetic-session"),
        )
        val trace = store.begin(meta, ClientInbound("POST", "/v1/messages", emptyMap(), request))
        trace.responseText(answer)
        trace.attempted(
            WireAttempt(
                1,
                WireRequest("http://127.0.0.1:9", emptyMap(), upstream, null),
                WireResponse(200, emptyMap(), null),
                null,
                1,
            ),
        )
        trace.clientFrame(answer)
        trace.finish("ok", PerfSnapshot(emptyMap(), emptyMap()))
    }

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
}
