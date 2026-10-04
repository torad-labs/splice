package splice.head.trace.body

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TracePackBudgetTest {
    @Test
    fun `capacity retains metadata marks new bodies unavailable and still reuses stored bodies`(@TempDir dir: Path) {
        val day = dir.resolve("synthetic-2026-09-18.jsonl")
        val text = "synthetic first"
        val capacity = TRACE_PACK_START_BYTES + TRACE_PACK_HEADER_BYTES + text.toByteArray().size + 2L
        val bodies = TraceBodies(capacity)
        val first = encoded(bodies, day, text)
        val full = Files.size(dir.resolve("${day.fileName}.bodies"))
        assertEquals(capacity, full, "the positive control fills the pack exactly")
        val omitted = encoded(bodies, day, "synthetic other")
        assertEquals(full, Files.size(dir.resolve("${day.fileName}.bodies")))
        val reference = omitted.getValue("client").jsonObject.getValue("body").jsonObject
        assertEquals("true", reference["unavailable"]?.jsonPrimitive?.content)
        assertEquals("true", reference["truncated"]?.jsonPrimitive?.content)
        assertEquals("m", omitted["model"]?.jsonPrimitive?.content)
        assertEquals(omitted, bodies.hydrate(omitted, day), "capacity markers remain explicit on reads")
        assertEquals(first, encoded(bodies, day, text), "full packs still admit deduplicated bodies")
        assertEquals(record(text), bodies.hydrate(first, day))
    }

    @Test
    fun `a multi chunk body cannot cross the daily cap or return a successful partial literal`(@TempDir dir: Path) {
        val day = dir.resolve("synthetic-2026-09-18.jsonl")
        val capacity = TRACE_PACK_START_BYTES + TRACE_PACK_HEADER_BYTES + CHUNK_MAX.toLong()
        val bodies = TraceBodies(capacity)
        val text = buildString { repeat(CHUNK_MAX) { append("synthetic-$it λ") } }
        val encoded = encoded(bodies, day, text)
        assertTrue(Files.size(dir.resolve("${day.fileName}.bodies")) <= capacity)
        val body = encoded.getValue("client").jsonObject.getValue("body").jsonObject
        assertEquals("true", body["unavailable"]?.jsonPrimitive?.content)
        assertEquals("true", body["truncated"]?.jsonPrimitive?.content)
        assertTrue(body["parts"] == null, "an incomplete JSON literal cannot be published as readable parts")
        assertEquals(encoded, bodies.hydrate(encoded, day))
    }

    private fun encoded(bodies: TraceBodies, day: Path, text: String): JsonObject =
        Json.parseToJsonElement(bodies.encode(record(text), day).decodeToString()).jsonObject

    private fun record(text: String): JsonObject = buildJsonObject {
        put("model", "m")
        put("client", buildJsonObject { put("body", text) })
    }
}
