// NEW: Oct 11, 2026 — the kept stores Your data counts and clears: a real transcript-copy store, and one that cannot be read.
package splice.app.control.kept

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.resume.originals.TranscriptOriginals
import splice.core.config.StatePaths
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private const val ROW = """{"type":"assistant","message":{"content":[{"type":"text","text":"original"}]}}"""

internal class StoreKeptRoutesTest {
    @TempDir lateinit var tmp: Path

    private val paths get() = StatePaths(baseOverride = tmp.resolve("state"))

    private fun keepACopy(id: String) {
        val live = tmp.resolve("project/$id.jsonl")
        Files.createDirectories(live.parent)
        Files.writeString(live, ROW + "\n")
        TranscriptOriginals(paths).preserve(live, listOf(live))
    }

    private fun body(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `transcript copies are counted, then deleted, then count none`() {
        keepACopy("aaaa")
        keepACopy("bbbb")
        val routes = StoreKeptRoutes(mapOf("transcript_copies" to TranscriptCopiesKept(paths)))

        val held = body(routes.kept().body).getValue("stores").jsonObject.getValue("transcript_copies").jsonObject
        assertTrue(held.getValue("files").jsonPrimitive.content.toLong() >= 2)
        assertTrue(held.getValue("bytes").jsonPrimitive.content.toLong() > 0)

        val deleted = routes.delete("transcript_copies")
        assertEquals(HttpStatusCode.OK, deleted.status)
        assertEquals(held.getValue("files"), body(deleted.body).getValue("files"), "it reports what it held")

        val after = body(routes.kept().body).getValue("stores").jsonObject.getValue("transcript_copies").jsonObject
        assertEquals("0", after.getValue("files").jsonPrimitive.content)
    }

    @Test
    fun `a store that cannot be read says so and does not hide the others`() {
        val broken = object : KeptStore {
            override fun held(): StoreHeld = throw IOException("the disk would not answer")
            override fun clear(): String? = null
        }
        val steady = object : KeptStore {
            override fun held() = StoreHeld(3, 30, 1_000)
            override fun clear(): String? = null
        }
        val routes = StoreKeptRoutes(mapOf("code_mode" to broken, "compaction_summaries" to steady))
        val stores = body(routes.kept().body).getValue("stores").jsonObject
        assertTrue("error" in stores.getValue("code_mode").jsonObject)
        assertEquals("3", stores.getValue("compaction_summaries").jsonObject.getValue("files").jsonPrimitive.content)
        assertEquals(HttpStatusCode.InternalServerError, routes.delete("code_mode").status)
    }

    @Test
    fun `deleting a store that does not exist is a 404`() {
        assertEquals(HttpStatusCode.NotFound, StoreKeptRoutes(emptyMap()).delete("nothing").status)
    }

    @Test
    fun `a delete that fails says why and does not claim a clean delete`() {
        val stuck = object : KeptStore {
            override fun held() = StoreHeld(1, 10, null)
            override fun clear(): String = "a file is held open"
        }
        val reply = StoreKeptRoutes(mapOf("code_mode" to stuck)).delete("code_mode")
        assertEquals(HttpStatusCode.InternalServerError, reply.status)
        assertEquals("a file is held open", body(reply.body).getValue("error").jsonPrimitive.content)
    }
}
