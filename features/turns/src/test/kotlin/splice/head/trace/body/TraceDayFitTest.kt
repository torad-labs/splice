// NEW: a day of Claude Code bodies fits the daily trace body budget, every body reads back byte-identical, a v1
// pack written before the v2 store still reads, and a missing, corrupt or torn v2 entry fails by name. On
// 2026-10-05 claudex and claude-splice filled their 1 GiB packs by 1:22 and 1:02 AM CT, six hours into the day,
// and every body after that was dropped. The Gear chunks already shared each body's unchanged history; what
// filled the pack was the chunk around each place the client edited its body, stored again whole every request.
package splice.head.trace.body

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.LogSink
import splice.head.trace.TraceAsk
import splice.head.trace.TraceRows
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat

private const val FIT_HEAD = "synthetic"
private const val FIT_DAY = "$FIT_HEAD-2026-10-05.jsonl"
private const val V1_DAY = "$FIT_HEAD-2026-10-04.jsonl"

// why: measured 2026-10-05, the synthetic day stores 21.1 MiB through 16/32 KiB raw chunks (the v1 shape), 7.8 MiB
// with zstd alone, 7.0 MiB with 4/8 KiB chunks alone and 3.0 MiB through the v2 store; only the v2 store fits 4 MiB.
private const val FIT_BUDGET = 4L shl 20

// why: an interrupted append wrote a whole header for a 5000-byte entry, longer than the next entry it is cut for.
private const val TORN_STORED = 5000

// why: only 3000 of the interrupted entry's 5000 bytes reached the disk.
private const val TORN_WRITTEN = 3000

// why: a v2 entry header is its stored length, its raw length and the raw content's 32-byte SHA-256.
private const val V2_HEADER = Int.SIZE_BYTES * 2 + 32

class TraceDayFitTest {
    @Test
    fun `a day of bodies edited in several places fits the daily budget with every body readable`(@TempDir dir: Path) {
        val day = dir.resolve(FIT_DAY)
        val rows = SyntheticDay().records()
        val bodies = TraceBodies(FIT_BUDGET, heap = splice.head.syntheticHeapBudget())
        val encoded = rows.map { bodies.encode(it, day) }
        val stored = packs(dir).sumOf { Files.size(it) }
        val unavailable = encoded.count { "\"body_unavailable\":true" in it.decodeToString() }
        assertEquals(0, unavailable, "every body of the day is kept; the packs hold $stored bytes of $FIT_BUDGET")
        assertTrue(stored <= FIT_BUDGET, "the day stays inside its budget: $stored > $FIT_BUDGET")
        println("TRACE_DAY_FIT records=${rows.size} stored=$stored budget=$FIT_BUDGET")
    }

    @Test
    fun `every body reads back byte identical through the trace API from the v2 store`(@TempDir dir: Path) {
        val day = dir.resolve(FIT_DAY)
        val records = SyntheticDay(steps = 8).records()
        val bodies = TraceBodies(heap = splice.head.syntheticHeapBudget())
        Files.write(day, lines(records.map { bodies.encode(it, day) }))
        assertTrue(Files.exists(dir.resolve("$FIT_DAY.bodies2")), "new bodies go to the v2 store")
        assertFalse(Files.exists(dir.resolve("$FIT_DAY.bodies")), "nothing new is written to the v1 store")
        val rows = TraceRows(heap = splice.head.syntheticHeapBudget())
        val read = rows.turns(dir, FIT_HEAD, TraceAsk(last = records.size))
        val back = read.flatMap { it.attempts + listOfNotNull(it.turn) }
        assertEquals(records.map(::digests), back.map(::digests))
    }

    @Test
    fun `a v1 pack written before the v2 store still reads through the trace API`(@TempDir dir: Path) {
        listOf(V1_DAY, "$V1_DAY.bodies").forEach { name ->
            val fixture = checkNotNull(javaClass.getResourceAsStream("v1/$name")) { "fixture $name" }
            fixture.use { Files.copy(it, dir.resolve(name)) }
        }
        val read = TraceRows(heap = splice.head.syntheticHeapBudget()).read(dir, FIT_HEAD, TraceAsk(last = 2))
        assertEquals(0, read.unavailableRecords)
        assertEquals(
            listOf(
                mapOf("client" to V1_FIRST_CLIENT, "answer" to sha("synthetic answer one")),
                mapOf("client" to V1_SECOND_CLIENT, "answer" to sha("synthetic answer two")),
            ),
            read.turns.map { digests(checkNotNull(it.turn)) },
        )
    }

    @Test
    fun `a missing v2 pack fails by name`(@TempDir dir: Path) {
        val day = dir.resolve(FIT_DAY)
        val bodies = TraceBodies(heap = splice.head.syntheticHeapBudget())
        val encoded = parsed(bodies.encode(SyntheticDay(steps = 1).records().last(), day))
        Files.delete(dir.resolve("$FIT_DAY.bodies2"))
        val failure = assertThrows(IOException::class.java) { bodies.hydrate(encoded, day) }
        assertTrue(failure.message.orEmpty().contains("pack missing or unreadable"), failure.message)
    }

    @Test
    fun `a corrupt v2 entry fails by name and costs only its own body`(@TempDir dir: Path) {
        val day = dir.resolve(FIT_DAY)
        val bodies = TraceBodies(heap = splice.head.syntheticHeapBudget())
        val record = SyntheticDay(steps = 1).records().last()
        val encoded = parsed(bodies.encode(record, day))
        Files.write(day, lines(listOf(encoded.toString().toByteArray() + '\n'.code.toByte())))
        val part = encoded.getValue("client").jsonObject.getValue("body").jsonObject
            .getValue("parts").jsonArray.first().jsonObject
        val pack = dir.resolve("$FIT_DAY.bodies2")
        val bytes = Files.readAllBytes(pack)
        val at = part.getValue("offset").jsonPrimitive.long.toInt() + V2_HEADER
        bytes[at] = (bytes[at].toInt() xor 0x5a).toByte()
        Files.write(pack, bytes)
        val failure = assertThrows(IOException::class.java) { bodies.hydrate(encoded, day) }
        assertTrue(failure.message.orEmpty().startsWith("corrupt trace body chunk at byte"), failure.message)
        val read = TraceRows(heap = splice.head.syntheticHeapBudget()).read(dir, FIT_HEAD, TraceAsk(last = 1))
        val turn = checkNotNull(read.turns.single().turn)
        assertEquals(1, read.unavailableRecords)
        val client = turn["client"]?.jsonObject?.get("body")?.jsonObject
        assertEquals("true", client?.get("unavailable")?.jsonPrimitive?.content)
        assertEquals(digests(record)["answer"], digests(turn)["answer"], "the record's other body still reads")
    }

    @Test
    fun `a torn v2 tail heals before the next append and a reference into it fails by name`(@TempDir root: Path) {
        val control = Files.createDirectory(root.resolve("control"))
        TraceBodies(heap = splice.head.syntheticHeapBudget()).run {
            encode(record("first", "synthetic first"), control.resolve(FIT_DAY))
            encode(record("later", "synthetic later"), control.resolve(FIT_DAY))
        }
        val healed = Files.size(control.resolve("$FIT_DAY.bodies2"))
        val interrupted = ByteBuffer.allocate(V2_HEADER + TORN_WRITTEN).putInt(TORN_STORED).putInt(TORN_STORED).array()
        val tails = listOf(0, CHUNK_MAX + 1).map { ByteBuffer.allocate(V2_HEADER).putInt(it).putInt(it).array() }
        (tails + listOf(interrupted)).forEachIndexed { case, torn ->
            val dir = Files.createDirectory(root.resolve("torn-$case"))
            val day = dir.resolve(FIT_DAY)
            val bodies = TraceBodies(heap = splice.head.syntheticHeapBudget())
            val first = bodies.encode(record("first", "synthetic first"), day)
            val pack = dir.resolve("$FIT_DAY.bodies2")
            val intact = Files.size(pack)
            Files.write(pack, torn, StandardOpenOption.APPEND)
            val forged = record("forged", reference(intact + V2_HEADER))
            val later = bodies.encode(record("later", "synthetic later"), day)
            Files.write(day, lines(listOf(first, later)))
            assertEquals(healed, Files.size(pack), "the torn tail is cut before the next entry, never kept beside it")
            val turns = TraceRows(heap = splice.head.syntheticHeapBudget()).turns(dir, FIT_HEAD, TraceAsk(last = 2))
            assertEquals(listOf("synthetic first", "synthetic later"), turns.map { client(checkNotNull(it.turn)) })
            val failure = assertThrows(IOException::class.java) { bodies.hydrate(forged, day) }
            assertTrue(failure.message.orEmpty().startsWith("invalid trace body chunk"), failure.message)
        }
    }

    @Test
    fun `a full pack is logged once per head and day`(@TempDir dir: Path) {
        val log = ArrayList<String>()
        val bodies = TraceBodies(V2_HEADER + 1L, log = LogSink { log += it }, heap = splice.head.syntheticHeapBudget())
        listOf("first", "second", "third").forEach { bodies.encode(record(it, "synthetic $it"), dir.resolve(FIT_DAY)) }
        bodies.encode(record("next", "synthetic next"), dir.resolve("$FIT_HEAD-2026-10-06.jsonl"))
        assertEquals(2, log.size, log.joinToString("\n"))
        assertTrue(log[0].contains("$FIT_DAY.bodies2") && log[0].contains("budget"), log[0])
        assertTrue(log[1].contains("$FIT_HEAD-2026-10-06.jsonl.bodies2"), log[1])
    }

    private fun packs(dir: Path): List<Path> =
        listOf("$FIT_DAY.bodies", "$FIT_DAY.bodies2").map(dir::resolve).filter(Files::exists)

    private fun parsed(bytes: ByteArray): JsonObject = Json.parseToJsonElement(bytes.decodeToString()).jsonObject

    private fun lines(rows: List<ByteArray>): ByteArray =
        ByteArrayOutputStream().also { out -> rows.forEach(out::write) }.toByteArray()

    private fun client(record: JsonObject): String? =
        record["client"]?.jsonObject?.get("body")?.jsonPrimitive?.content

    /** A v2 reference to [offset], where no entry begins. */
    private fun reference(offset: Long): JsonObject = buildJsonObject {
        put("trace_chunks", 2)
        put(
            "parts",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("hash", sha("synthetic forged"))
                        put("offset", offset)
                        put("bytes", 1)
                    },
                ),
            ),
        )
    }

    private fun record(id: String, body: Any): JsonObject = buildJsonObject {
        put("kind", "turn")
        put("turn", id)
        put("ts", 1_791_158_400_000L)
        put("head", FIT_HEAD)
        put("model", "m")
        put(
            "client",
            buildJsonObject {
                if (body is JsonElement) put("body", body) else put("body", body.toString())
            },
        )
    }
}

private const val V1_FIRST_CLIENT = "f877a61bdc1f76b958b4bdbce020944ced669c4fe0da1a7c7a453035660649a4"
private const val V1_SECOND_CLIENT = "463abb11785c97ce2099456f8b1ba7a830d4dfb04eb2fa3da842b19df43e96ff"
private val BODY_FIELDS = mapOf("request" to "body", "response" to "text", "client" to "body", "answer" to "body")

/** Each body's SHA-256, by section. */
private fun digests(record: JsonObject): Map<String, String> = BODY_FIELDS.mapNotNull { (section, field) ->
    val value = (record[section] as? JsonObject)?.get(field) as? JsonPrimitive
    value?.takeIf { it.isString }?.let { section to sha(it.content) }
}.toMap()

private fun sha(text: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

/** Claude Code conversations as the client sends them: the whole history every step, the cache marker moved to the
 *  newest message, the system prompt and the tools after the messages, and the upstream request carrying the same
 *  conversation in its own layout. Seeded, so every run writes the same bytes. */
private class SyntheticDay(private val sessions: Int = 2, private val steps: Int = 60) {
    private var seed = 7L
    private val words = List(VOCABULARY) {
        buildString { repeat(2 + next(WORD_SPREAD)) { append(LETTERS[next(LETTERS.length)]) } }
    }
    private val system = prose(SYSTEM_CHARS)
    private val tools = prose(TOOLS_CHARS)
    private val ephemeral = buildJsonObject { put("type", "ephemeral") }

    fun records(): List<JsonObject> {
        val histories = List(sessions) { mutableListOf<JsonObject>() }
        return (0 until steps).flatMap { step ->
            histories.flatMapIndexed { session, history ->
                history += message("assistant", prose(REPLY_CHARS))
                history += message("user", prose(RESULT_CHARS))
                val id = "s$session-$step"
                val stream = prose(STREAM_CHARS)
                listOf(
                    record(
                        "attempt",
                        id,
                        section("request", "body", upstream(history)),
                        section("response", "text", stream),
                    ),
                    record(
                        "turn",
                        id,
                        section("client", "body", client(history)),
                        section("answer", "body", prose(ANSWER_CHARS)),
                    ),
                )
            }
        }
    }

    private fun client(history: List<JsonObject>): String = buildJsonObject {
        put("model", "m")
        val marked = history.mapIndexed { at, m ->
            if (at == history.lastIndex) JsonObject(m + ("cache_control" to ephemeral)) else m
        }
        put("messages", JsonArray(marked))
        put("system", system)
        put("tools", tools)
    }.toString()

    private fun upstream(history: List<JsonObject>): String = buildJsonObject {
        put("instructions", system)
        put(
            "input",
            JsonArray(
                history.map { m ->
                    buildJsonObject {
                        put("type", "message")
                        put("content", checkNotNull(m["content"]))
                        put("role", checkNotNull(m["role"]))
                    }
                },
            ),
        )
        put("tools", tools)
    }.toString()

    private fun record(kind: String, id: String, vararg sections: Pair<String, JsonObject>): JsonObject =
        buildJsonObject {
            put("kind", kind)
            put("turn", id)
            put("ts", 1_791_158_400_000L)
            put("head", FIT_HEAD)
            put("model", "m")
            sections.forEach { (name, content) -> put(name, content) }
        }

    private fun section(name: String, field: String, text: String): Pair<String, JsonObject> =
        name to buildJsonObject { put(field, text) }

    private fun message(role: String, text: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", text)
    }

    private fun prose(chars: Int): String = buildString {
        while (length < chars) append(words[next(words.size)]).append(SEPARATORS[next(SEPARATORS.size)])
    }

    private fun next(bound: Int): Int {
        seed = seed * LCG_MULTIPLIER + LCG_INCREMENT
        return ((seed ushr LCG_SHIFT) % bound).toInt()
    }
}

private const val LETTERS = "abcdefghijklmnopqrstuvwxyz"
private val SEPARATORS = listOf(" ", " ", " ", ". ", ", ", "\n", "\"", "\\", "\t", " λ ")

// why: a working vocabulary of 2 to 10 letter words, so the synthetic text compresses about as much as real
// conversation text does.
private const val VOCABULARY = 600
private const val WORD_SPREAD = 9

// why: sizes of one step's parts, near what one Claude Code tool round adds: a short reply, a tool result, the
// upstream's stream and the answer, after a system prompt and tool list that every request repeats.
private const val SYSTEM_CHARS = 12_000
private const val TOOLS_CHARS = 48_000
private const val REPLY_CHARS = 800
private const val RESULT_CHARS = 6_000
private const val STREAM_CHARS = 3_000
private const val ANSWER_CHARS = 600

// why: Knuth's MMIX linear congruential constants, and the high bits that carry its randomness.
private const val LCG_MULTIPLIER = 6364136223846793005L
private const val LCG_INCREMENT = 1442695040888963407L
private const val LCG_SHIFT = 33
