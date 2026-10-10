// NEW: Oct 10, 2026 — the choices a team member's screen is offering, as the card draws them. The parse is
// ScreenChoices'; what is tested here is the route around it: whose screen is read, which pane splice may read
// it in, and that a screen with nothing numbered on it answers an empty list rather than a refusal.
package splice.sessions.http

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.session.SessionPane
import splice.sessions.teams.Team
import splice.sessions.teams.TeamSlot
import java.nio.file.Path

/** The screen read: what a member is showing, as choices a card can draw. */
class TeamScreenTest {

    @TempDir
    lateinit var tmp: Path

    private val rig by lazy { TeamRig(tmp) }
    private val terminal = FakeTerminal()
    private val panes = FakePanes()

    private fun screen(wired: Boolean = true) =
        TeamScreen(TeamSource { rig.store }, TerminalSource { SessionDriver(terminal, panes).takeIf { wired } })

    private fun team(): Team = rig.store.upsert(
        Team(
            name = "atlas",
            goal = "ship",
            repo = rig.repo.toString(),
            slots = listOf(TeamSlot(id = "b1", role = "builder", head = "codex")),
        ),
    )

    @Test
    fun `the choices come off the live prompt, each with the digit that answers it`() {
        val id = team().id
        rig.store.bind(id, mapOf("b1" to "s-1"))
        panes.remember("s-1", SessionPane("%s-1"))
        terminal.screenText = """
            1. An older prompt
            2. from further up the pane
            3. with a third nobody can press now

            Bash(npm test)
            Run the tax tests?
            ❯ 1. Yes
              2. Yes, and don't ask again
              3. No, tell Claude what to do differently
        """.trimIndent()

        val reply = screen().offer(id, "b1")
        assertEquals(HttpStatusCode.OK, reply.status, reply.body)
        val body = rig.json(reply.body)
        assertEquals("s-1", body.getValue("session_id").jsonPrimitive.content)
        assertTrue(body.getValue("asked").jsonPrimitive.content.endsWith("Run the tax tests?"), reply.body)
        val choices = body.getValue("choices").jsonArray.map {
            it.jsonObject.getValue("choice").jsonPrimitive.int to it.jsonObject.getValue("label").jsonPrimitive.content
        }
        assertEquals(
            listOf(1 to "Yes", 2 to "Yes, and don't ask again", 3 to "No, tell Claude what to do differently"),
            choices,
        )
        assertTrue(body.getValue("choices").jsonArray.first().jsonObject.getValue("here").jsonPrimitive.boolean)
    }

    @Test
    fun `a screen with nothing numbered on it offers nothing, which is not an empty card`() {
        val id = team().id
        rig.store.bind(id, mapOf("b1" to "s-1"))
        panes.remember("s-1", SessionPane("%s-1"))
        terminal.screenText = "2 files changed, 7 insertions(+)\nRunning the tests…"

        val reply = screen().offer(id, "b1")
        assertEquals(HttpStatusCode.OK, reply.status, reply.body)
        assertTrue(rig.json(reply.body).getValue("choices").jsonArray.isEmpty(), reply.body)
    }

    @Test
    fun `a pane splice did not open, a slot with no session and no terminal are each refused by name`() {
        val id = team().id
        rig.store.bind(id, mapOf("b1" to "s-1"))
        val foreign = screen().offer(id, "b1")
        assertEquals(HttpStatusCode.Conflict, foreign.status)
        assertTrue(
            rig.json(foreign.body).getValue("error").jsonPrimitive.content.startsWith("splice did not start"),
            foreign.body,
        )

        rig.store.bind(id, mapOf("b1" to null))
        assertEquals(HttpStatusCode.Conflict, screen().offer(id, "b1").status, "a slot with no session")

        // A slot with no session is answered before the terminal is asked for, so this arm needs one bound.
        rig.store.bind(id, mapOf("b1" to "s-1"))
        assertEquals(
            HttpStatusCode.ServiceUnavailable,
            screen(wired = false).offer(id, "b1").status,
            "no terminal at all",
        )
        assertEquals(HttpStatusCode.NotFound, screen().offer(id, "nobody").status)
        assertEquals(HttpStatusCode.NotFound, screen().offer("no-team", "b1").status)
    }
}
