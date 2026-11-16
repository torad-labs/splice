// A turn answered while splice's own head is down (docs/specs/server-mode.md, F5): the client is told the command is
// not running, the provider's server is asked nothing, and the turn's row in the ledger says it was splice's failure,
// on either route, never the upstream's.
package splice.server.ledger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.server.BoxServer
import splice.server.CHAT
import splice.server.ChatServer
import splice.server.HeadUpstreams
import splice.server.OpenAiRig
import splice.server.ScriptedBox
import java.nio.file.Files
import java.nio.file.Path

internal class HeadDownRowsTest {
    @TempDir lateinit var state: Path

    private val box = ScriptedBox("box")
    private var opened: OpenAiRig? = null

    @AfterEach
    fun stop() {
        opened?.close()
        box.stop()
    }

    /** Every row the ledger file holds, oldest first. */
    private fun rows(): List<JsonObject> {
        val file = state.resolve("server").resolve("ledger.jsonl")
        if (!Files.exists(file)) return emptyList()
        return Files.readAllLines(file).filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject }
    }

    private fun field(row: JsonObject, name: String): String = row.getValue(name).jsonPrimitive.content

    @Test
    fun `a turn on either route while its head is down is splice's error in the ledger, not the upstream's`() {
        val upstreams = object : HeadUpstreams {
            override fun chat(head: String): ChatServer = BoxServer(box.base)
        }
        val rig = OpenAiRig(state, upstreams, headUp = false).also { opened = it }
        val key = rig.keyFor("carol@example.com")

        assertEquals(503, rig.turn(key).statusCode())
        val chat = rig.chat(key, CHAT)
        assertEquals(503, chat.statusCode())
        assertTrue("not running" in chat.body(), chat.body())

        rig.await("a row for each turn") { rows().size >= 2 }
        val byRoute = rows().associateBy { field(it, "route") }
        assertEquals(setOf(ROUTE_MESSAGES, ROUTE_CHAT), byRoute.keys)
        for (row in byRoute.values) {
            assertEquals("splice_error", field(row, "outcome"), row.toString())
            assertEquals("500", field(row, "status"), row.toString())
            assertEquals("carol@example.com", field(row, "person"))
        }
        assertEquals(emptyList<ScriptedBox.Asked>(), box.asked.toList())
    }
}
