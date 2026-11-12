package splice.app.control.codemode

import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.JsonScalars
import java.nio.file.Path

class CodeModeInterruptRouteTest {
    @Test
    fun `management interruption terminates exact native cells while completed state and siblings survive`(
        @TempDir dir: Path,
    ) =
        runBlocking {
            withTimeout(60_000) {
                val fixture = InterruptControlFixture(dir)
                fixture.start()
                try {
                    val owner = fixture.conversation("owner")
                    val started = owner.execute(
                        "store('committed','kept'); text('seeded');",
                        held("draft-one"),
                        held("draft-two"),
                    )
                    assertTrue(started.drop(1).all { it.contains("parked") }, started.toString())
                    val ownedCells = started.drop(1).map(owner::cell).toSet()
                    val sibling = fixture.conversation("sibling")
                    val siblingCell = sibling.cell(sibling.execute(held("sibling-draft")).single())
                    refusedControls(fixture)
                    val response = fixture.interrupt("owner", head = "claude-owned")
                    val text = response.bodyAsText()
                    assertEquals(200, response.status.value, text)
                    val body = Json.parseToJsonElement(text).jsonObject
                    assertEquals("owned", JsonScalars.str(body, "head"))
                    assertEquals("client", JsonScalars.str(body, "session"))
                    assertEquals("owner", JsonScalars.str(body, "conversation_key"))
                    val cells = body.getValue("cells").jsonArray.map { it.jsonObject }
                    assertEquals(ownedCells, cells.map { JsonScalars.str(it, "cell_id") }.toSet())
                    assertTrue(cells.all { it["terminated"].toString() == "true" && it["failure"] == JsonNull }, text)
                    assertTrue(sibling.wait(siblingCell).contains("Script running with cell ID $siblingCell"))
                    val kept = owner.execute(
                        "text(load('committed')+'|'+(load('draft-one')==null)+'|'+(load('draft-two')==null));",
                    )
                    assertTrue(kept.single().contains("kept|true|true"), kept.toString())
                    val idle = fixture.interrupt("owner")
                    assertEquals(200, idle.status.value)
                    assertTrue(
                        Json.parseToJsonElement(idle.bodyAsText()).jsonObject.getValue("cells").jsonArray.isEmpty(),
                    )
                    assertEquals(200, fixture.interrupt("sibling").status.value)
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun `invalid unavailable and inactive controls report their actual boundary`(@TempDir dir: Path) = runBlocking {
        val fixture = InterruptControlFixture(dir)
        fixture.start()
        try {
            listOf(
                "{}",
                "[]",
                "{\"conversation_key\":1}",
                "{\"conversation_key\":null}",
                "{\"conversation_key\":\"x\",\"extra\":1}",
            )
                .forEach { assertEquals(400, fixture.post(it).status.value, it) }
            assertEquals(404, fixture.interrupt("never-opened").status.value)
            assertEquals(404, fixture.interrupt("").status.value)
            fixture.conversation("").execute("text('empty-conversation-still-owned');")
            assertEquals(200, fixture.interrupt("").status.value)
            assertEquals(404, fixture.interrupt("owner", head = "unknown").status.value)
            assertEquals(409, fixture.interrupt("owner", head = "disabled").status.value)
            assertEquals(409, fixture.interrupt("owner", head = "stopped").status.value)
        } finally {
            fixture.close()
        }
    }

    private suspend fun refusedControls(fixture: InterruptControlFixture) {
        assertEquals(401, fixture.interrupt("owner", bearer = null).status.value)
        assertEquals(401, fixture.interrupt("owner", bearer = fixture.sessionKey).status.value)
        assertEquals(404, fixture.interrupt("owner", session = "foreign-client").status.value)
        assertEquals(404, fixture.interrupt("foreign-conversation").status.value)
    }

    @Test
    fun `partial native termination returns every outcome and leaves the failed cell retryable`(@TempDir dir: Path) =
        runBlocking {
            withTimeout(60_000) {
                val fixture = InterruptControlFixture(dir, FailedInterruptNativeRuntime())
                fixture.start()
                try {
                    val conversation = fixture.conversation("partial")
                    val outputs = conversation.execute(held("failed-draft"), held("healthy-draft"))
                    val ids = outputs.map(conversation::cell)
                    val response = fixture.interrupt("partial")
                    val text = response.bodyAsText()
                    assertEquals(409, response.status.value, text)
                    val cells = Json.parseToJsonElement(text).jsonObject.getValue("cells").jsonArray
                        .map { it.jsonObject }.associateBy { JsonScalars.str(it, "cell_id") }
                    assertEquals(ids.toSet(), cells.keys)
                    assertEquals(
                        "synthetic termination transport unavailable",
                        JsonScalars.str(cells.getValue(ids.first()), "failure"),
                    )
                    assertEquals("false", cells.getValue(ids.first())["terminated"].toString())
                    assertEquals("true", cells.getValue(ids.last())["terminated"].toString())
                    assertTrue(conversation.wait(ids.first()).contains("Script running with cell ID ${ids.first()}"))
                    val retried = fixture.interrupt("partial")
                    val retriedText = retried.bodyAsText()
                    assertEquals(200, retried.status.value, retriedText)
                    val remaining = Json.parseToJsonElement(retriedText).jsonObject.getValue("cells").jsonArray
                        .map { it.jsonObject }
                    assertEquals(listOf(ids.first()), remaining.map { JsonScalars.str(it, "cell_id") })
                    assertEquals("true", remaining.single()["terminated"].toString())
                } finally {
                    fixture.close()
                }
            }
        }

    private fun held(key: String): String =
        "// @exec: {\"yield_time_ms\":1000}\n" +
            "store('$key','uncommitted'); text('parked'); yield_control(); await new Promise(()=>{});"
}
