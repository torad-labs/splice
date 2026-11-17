// NEW: a capability's text stays in the first user message on every later request, and each turn's perf row says
// what the capabilities did (docs/specs/harness.md, decisions 5 and 10), driven through a head with real requests.
package splice.head.harness

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CapabilityLaterTurnsTest(@param:TempDir private val tmp: Path) {
    private val rig = CapabilityRig(tmp)

    @AfterEach
    fun close() = rig.close()

    @Test
    fun `the text stays on every later request, across a restart, with the worker asked once`() = runBlocking {
        val brief = rig.capability("brief")
        rig.reply = { result("src/main.ts:10-42 holds the entry point.") }
        val before = rig.head(listOf(brief))
        rig.send(before, Turn("s1", opening("Fix the bug")))
        rig.send(before, Turn("s1", later("Fix the bug", "go on")))

        val restarted = rig.head(listOf(brief))
        rig.send(restarted, Turn("s1", later("Fix the bug", "and test it")))

        assertEquals(1, rig.asked.size, "asked for the first turn alone")
        rig.provider.forEach { body ->
            assertEquals(listOf("Fix the bug", "src/main.ts:10-42 holds the entry point."), firstUserTexts(body))
        }
        rig.awaitUntil("both turns' rows are written") { rig.perfRows(before).size == 2 }
        val heldOn = rig.perfRows(before).map { row ->
            (row["held_on"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        }
        assertEquals(listOf("brief", null), heldOn, "the first turn was held on brief, the next one was not held")
    }

    @Test
    fun `a turn the client leaves while it is held says so, with what it was held on and for how long`() =
        runBlocking {
            rig.reply = { WorkerReply(listOf("""{"type":"result","text":"late"}"""), held = true) }
            val head = rig.head(listOf(rig.capability("cap-a")))

            val turn = launch(Dispatchers.IO) { rig.send(head, Turn("left", opening("Fix the bug"))) }
            rig.awaitUntil("the worker is asked") { rig.asked.isNotEmpty() }
            turn.cancelAndJoin()
            rig.awaitUntil("the left turn's row is written") { rig.perfRows(head).isNotEmpty() }

            val row = rig.perfRows(head).single()
            assertEquals("cap-a", row.getValue("held_on").jsonPrimitive.content, "$row")
            assertTrue(row.getValue("held_ms").jsonPrimitive.long > 0, "$row")
            assertEquals(JsonPrimitive(0), row["capability_text_chars"] ?: JsonNull, "$row")
            assertEquals(emptyList<String>(), rig.provider, "nothing went to the provider")
        }
}
