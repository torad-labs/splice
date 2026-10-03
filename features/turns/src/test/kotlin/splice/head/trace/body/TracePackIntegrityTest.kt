package splice.head.trace.body

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
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
        val pack = dir.resolve("${day.fileName}.bodies")
        val first = TraceBodies()
        first.encode(record("alpha"), day)
        first.encode(record("omega"), day)
        val size = Files.size(pack)
        FileChannel.open(pack, StandardOpenOption.WRITE).use { it.truncate(0) }
        val second = TraceBodies()
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
        val bodies = TraceBodies()
        bodies.encode(record("abc\"def"), day)
        val fragment = "\"def\"".toByteArray()
        val hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fragment))
        val part = buildJsonObject {
            put("offset", TRACE_PACK_START_BYTES + TRACE_PACK_HEADER_BYTES + 5)
            put("bytes", fragment.size)
            put("hash", hash)
        }
        val reference = buildJsonObject {
            put("trace_chunks", 1)
            put("parts", JsonArray(listOf(part)))
        }
        val client = buildJsonObject { put("body", reference) }
        val forged = buildJsonObject { put("client", client) }
        assertThrows(IOException::class.java) { bodies.hydrate(forged, day) }
    }

    private fun record(text: String): JsonObject = buildJsonObject {
        put("client", buildJsonObject { put("body", text) })
    }
}
