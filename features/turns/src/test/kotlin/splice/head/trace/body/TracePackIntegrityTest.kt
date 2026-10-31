package splice.head.trace.body

import com.github.luben.zstd.Zstd
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat

class TracePackIntegrityTest {
    @Test
    fun `same inode truncate and equal size regrowth invalidate both literal and chunk caches`(@TempDir dir: Path) {
        val day = dir.resolve("synthetic-2026-09-18.jsonl")
        val pack = dir.resolve("${day.fileName}.bodies2")
        val first = TraceBodies(heap = splice.head.syntheticHeapBudget())
        first.encode(record("alpha"), day)
        first.encode(record("omega"), day)
        val size = Files.size(pack)
        FileChannel.open(pack, StandardOpenOption.WRITE).use { it.truncate(0) }
        val second = TraceBodies(heap = splice.head.syntheticHeapBudget())
        second.encode(record("bravo"), day)
        second.encode(record("omega"), day)
        assertEquals(size, Files.size(pack))
        first.encode(record("bravo"), day)
        assertEquals(size, Files.size(pack), "a rebuilt chunk with an unchanged tail is not appended twice")
        val reused = Json.parseToJsonElement(first.encode(record("alpha"), day).decodeToString()) as JsonObject
        assertEquals(record("alpha"), first.hydrate(reused, day))
        val different = Json.parseToJsonElement(first.encode(record("alphabet"), day).decodeToString()) as JsonObject
        assertEquals(record("alphabet"), first.hydrate(different, day))
    }

    @Test
    fun `a valid digest of interior bytes is not a valid pack entry`(@TempDir dir: Path) {
        val day = dir.resolve("synthetic-2026-09-18.jsonl")
        val bodies = TraceBodies(heap = splice.head.syntheticHeapBudget())
        bodies.encode(record("abc\"def"), day)
        val fragment = "\"def\"".toByteArray()
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fragment))
        val part = buildJsonObject {
            put("offset", TRACE_PACK_START_BYTES + TRACE_PACK_V2_HEADER_BYTES + 5)
            put("bytes", fragment.size)
            put("hash", hash)
        }
        val reference = buildJsonObject {
            put("trace_chunks", TracePackFormat.V2.version)
            put("parts", JsonArray(listOf(part)))
        }
        val client = buildJsonObject { put("body", reference) }
        val forged = buildJsonObject { put("client", client) }
        assertThrows(IOException::class.java) { bodies.hydrate(forged, day) }
    }

    @Test
    fun `parseable interior payload corruption is rejected by its digest rather than JSON parsing`(@TempDir dir: Path) {
        val day = dir.resolve("synthetic-2026-09-18.jsonl")
        val bodies = TraceBodies(heap = splice.head.syntheticHeapBudget())
        val bytesOfRecord = bodies.encode(record("synthetic answer"), day)
        val encoded = Json.parseToJsonElement(bytesOfRecord.decodeToString()).jsonObject
        val part = encoded.getValue("client").jsonObject.getValue("body").jsonObject
            .getValue("parts").jsonArray.single().jsonObject
        val offset = part.getValue("offset").jsonPrimitive.long.toInt()
        val pack = dir.resolve("${day.fileName}.bodies2")
        val bytes = Files.readAllBytes(pack)
        // A small literal is a raw block inside its zstd frame, so the frame still decodes after this flip.
        val needle = "synthetic answer".toByteArray()
        val at = (offset until bytes.size - needle.size).first { from ->
            needle.indices.all { bytes[from + it] == needle[it] }
        }
        bytes[at] = 'S'.code.toByte()
        Files.write(pack, bytes)
        val decoded = Zstd.decompress(bytes.copyOfRange(offset, bytes.size), part.getValue("bytes").jsonPrimitive.int)
        assertEquals("Synthetic answer", Json.parseToJsonElement(decoded.decodeToString()).jsonPrimitive.content)
        val failure = assertThrows(IOException::class.java) { bodies.hydrate(encoded, day) }
        assertTrue(failure.message.orEmpty().startsWith("corrupt trace body chunk at byte"))
        assertTrue(!failure.message.orEmpty().contains("answer"))
    }

    @Test
    fun `strict hydration names missing packs while retaining inline compatibility`(@TempDir dir: Path) {
        val day = dir.resolve("synthetic-2026-09-18.jsonl")
        val bodies = TraceBodies(heap = splice.head.syntheticHeapBudget())
        val encoded = Json.parseToJsonElement(bodies.encode(record("synthetic body"), day).decodeToString()).jsonObject
        Files.delete(dir.resolve("${day.fileName}.bodies2"))
        val failure = assertThrows(IOException::class.java) { bodies.hydrate(encoded, day) }
        assertTrue(failure.message.orEmpty().contains("pack missing or unreadable"))
        assertEquals(record("synthetic legacy"), bodies.hydrate(record("synthetic legacy"), day))
    }

    @Test
    fun `header preserving truncate regrow resets a stale writer tail before appending`(@TempDir dir: Path) {
        val day = dir.resolve("synthetic-2026-09-18.jsonl")
        val pack = dir.resolve("${day.fileName}.bodies2")
        val first = TraceBodies(heap = splice.head.syntheticHeapBudget())
        first.encode(record("alpha"), day)
        FileChannel.open(pack, StandardOpenOption.WRITE).use { it.truncate(TRACE_PACK_START_BYTES.toLong()) }
        val fresh = TraceBodies(heap = splice.head.syntheticHeapBudget())
        val grown = Json.parseToJsonElement(fresh.encode(record("alphabet"), day).decodeToString()).jsonObject
        val later = Json.parseToJsonElement(first.encode(record("gamma"), day).decodeToString()).jsonObject
        assertEquals(record("alphabet"), first.hydrate(grown, day))
        assertEquals(record("gamma"), fresh.hydrate(later, day))
    }

    private fun record(text: String): JsonObject = buildJsonObject {
        put("client", buildJsonObject { put("body", text) })
    }
}
