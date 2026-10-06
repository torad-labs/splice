package splice.head.trace.body

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.terminal.TerminalOutput
import splice.head.TurnsHead
import splice.head.TurnsHeadLookup
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.trace.TraceAsk
import splice.head.trace.TraceDirPort
import splice.head.trace.TraceQuery
import splice.head.trace.TraceRoute
import splice.head.trace.TraceRows
import splice.head.trace.TraceView
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private const val RECOVERY_HEAD = "synthetic"
private const val RECOVERY_DAY = "synthetic-2026-09-18.jsonl"

class TraceRecoveryTest {
    @Test
    fun `one corrupt body keeps the other records readable and reports its unavailable count`(@TempDir dir: Path) {
        val day = dir.resolve(RECOVERY_DAY)
        val bodies = TraceBodies(heap = splice.head.syntheticHeapBudget())
        val good = bodies.encode(record("good", "synthetic good"), day)
        val bad = bodies.encode(record("bad", "synthetic damaged"), day)
        Files.write(day, good + bad)
        val damaged = Json.parseToJsonElement(bad.decodeToString()).jsonObject
        val reference = damaged.getValue("client").jsonObject.getValue("body").jsonObject
        val offset = reference.getValue("parts").jsonArray.single().jsonObject.getValue("offset").jsonPrimitive.long
        val pack = dir.resolve("$RECOVERY_DAY.bodies2")
        val bytes = Files.readAllBytes(pack)
        bytes[offset.toInt() + 1] = 'S'.code.toByte()
        Files.write(pack, bytes)

        val reply = runBlocking { route(dir).read(RECOVERY_HEAD, TraceQuery("2", null, null)) }
        assertEquals(HttpStatusCode.OK, reply.status, reply.body)
        assertEquals("1", Json.parseToJsonElement(reply.body).jsonObject["unavailable_records"]?.jsonPrimitive?.content)
        val turns = TraceRows(heap = splice.head.syntheticHeapBudget()).turns(dir, RECOVERY_HEAD, TraceAsk(last = 2))
        assertEquals(listOf("good", "bad"), turns.map { it.id })
        assertEquals(
            "synthetic good",
            turns.first().turn?.getValue("client")?.jsonObject?.get("body")?.jsonPrimitive?.content,
        )
        val unavailable = turns.last().turn?.getValue("client")?.jsonObject?.get("body")?.jsonObject
        assertEquals("true", unavailable?.get("unavailable")?.jsonPrimitive?.content)
    }

    @Test
    fun `a missing pack leaves legacy bodies and selected record metadata available`(@TempDir dir: Path) {
        val day = dir.resolve(RECOVERY_DAY)
        val old = record("legacy", "synthetic legacy").toString() + "\n"
        val bodies = TraceBodies(heap = splice.head.syntheticHeapBudget())
        val packed = bodies.encode(record("packed", "synthetic packed"), day)
        Files.write(day, old.toByteArray() + packed)
        Files.delete(dir.resolve("$RECOVERY_DAY.bodies2"))

        val turns = TraceRows(heap = splice.head.syntheticHeapBudget()).turns(dir, RECOVERY_HEAD, TraceAsk(last = 2))
        assertEquals(listOf("legacy", "packed"), turns.map { it.id })
        assertEquals(
            "synthetic legacy",
            turns.first().turn?.getValue("client")?.jsonObject?.get("body")?.jsonPrimitive?.content,
        )
        val client = turns.last().turn?.getValue("client")?.jsonObject
        assertEquals("true", client?.get("body")?.jsonObject?.get("unavailable")?.jsonPrimitive?.content)
        assertTrue(turns.last().turn?.get("model") != null, "the selected record's metadata survives body loss")
        val output = StringBuilder()
        TraceView(TerminalOutput { output.appendLine(it) }).printTurn(RECOVERY_HEAD, turns.last())
        assertTrue(output.contains("[trace body unavailable]"), output.toString())
    }

    @Test
    fun `invalid trailing entry headers heal at the last complete entry before later appends`(@TempDir root: Path) {
        listOf(0, CHUNK_MAX + 1).forEach { length ->
            val dir = Files.createDirectory(root.resolve("length-$length"))
            val day = dir.resolve(RECOVERY_DAY)
            val bodies = TraceBodies(heap = splice.head.syntheticHeapBudget())
            val first = bodies.encode(record("first", "synthetic first"), day)
            val pack = dir.resolve("$RECOVERY_DAY.bodies2")
            val intact = Files.size(pack)
            val broken = ByteBuffer.allocate(TRACE_PACK_V2_HEADER_BYTES).putInt(length).putInt(length).array()
            Files.write(pack, broken, StandardOpenOption.APPEND)
            val later = bodies.encode(record("later", "synthetic later"), day)
            Files.write(day, first + later)
            assertTrue(Files.size(pack) > intact, "the new entry is appended after recovery")
            val rows = TraceRows(heap = splice.head.syntheticHeapBudget())
            val turns = rows.turns(dir, RECOVERY_HEAD, TraceAsk(last = 2))
            assertEquals(listOf("first", "later"), turns.map { it.id })
            assertEquals(
                "synthetic first",
                turns.first().turn?.get("client")?.jsonObject?.get("body")?.jsonPrimitive?.content,
            )
            assertEquals(
                "synthetic later",
                turns.last().turn?.get("client")?.jsonObject?.get("body")?.jsonPrimitive?.content,
            )
        }
    }

    @Test
    fun `a corrupt incarnation header lets new appends proceed without substituting old bodies`(@TempDir dir: Path) {
        val day = dir.resolve(RECOVERY_DAY)
        val bodies = TraceBodies(heap = splice.head.syntheticHeapBudget())
        val first = bodies.encode(record("first", "synthetic first"), day)
        val pack = dir.resolve("$RECOVERY_DAY.bodies2")
        val bytes = Files.readAllBytes(pack)
        bytes[0] = 0
        Files.write(pack, bytes)
        val later = bodies.encode(record("later", "synthetic later"), day)
        Files.write(day, first + later)
        val read = TraceRows(heap = splice.head.syntheticHeapBudget()).read(dir, RECOVERY_HEAD, TraceAsk(last = 2))
        assertEquals(1, read.unavailableRecords)
        assertEquals(
            "true",
            read.turns.first().turn?.get("client")?.jsonObject?.get("body")?.jsonObject
                ?.get("unavailable")?.jsonPrimitive?.content,
        )
        assertEquals(
            "synthetic later",
            read.turns.last().turn?.get("client")?.jsonObject?.get("body")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `one damaged answer cannot hide an intact client body in the same record`(@TempDir dir: Path) {
        val day = dir.resolve(RECOVERY_DAY)
        val answer = buildJsonObject { put("body", "synthetic answer") }
        val record = JsonObject(record("mixed", "synthetic client") + ("answer" to answer))
        val encoded = TraceBodies(heap = splice.head.syntheticHeapBudget()).encode(record, day)
        Files.write(day, encoded)
        val indexed = Json.parseToJsonElement(encoded.decodeToString()).jsonObject
        val offset = indexed.getValue("answer").jsonObject.getValue("body").jsonObject
            .getValue("parts").jsonArray.single().jsonObject.getValue("offset").jsonPrimitive.long.toInt()
        val pack = dir.resolve("$RECOVERY_DAY.bodies2")
        val bytes = Files.readAllBytes(pack)
        bytes[offset + 1] = 'S'.code.toByte()
        Files.write(pack, bytes)
        val read = TraceRows(heap = splice.head.syntheticHeapBudget()).read(dir, RECOVERY_HEAD, TraceAsk(last = 1))
        val selected = checkNotNull(read.turns.single().turn)
        assertEquals(1, read.unavailableRecords)
        assertEquals("synthetic client", selected["client"]?.jsonObject?.get("body")?.jsonPrimitive?.content)
        assertEquals(
            "true",
            selected["answer"]?.jsonObject?.get("body")?.jsonObject?.get("unavailable")?.jsonPrimitive?.content,
        )
    }

    private fun record(id: String, body: String): JsonObject = buildJsonObject {
        put("kind", "turn")
        put("turn", id)
        put("ts", 1_789_725_600_000L)
        put("head", RECOVERY_HEAD)
        put("model", "m")
        put("client", buildJsonObject { put("body", body) })
    }

    private fun route(dir: Path): TraceRoute {
        val compact = object : HeadCompactSource {
            override fun summary(tailN: Int) = CompactView(0, emptyMap(), emptyList())
        }
        return TraceRoute(
            TurnsHeadLookup { listOf(TurnsHead(RECOVERY_HEAD, compact)) },
            TraceDirPort { dir },
            Dispatchers.Unconfined,
            heap = splice.head.syntheticHeapBudget(),
        )
    }
}
