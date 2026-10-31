// NEW: Oct 22, 2026 — End on one idle session whose screen splice cannot read (PR #247 review 2): a capture
// that failed is not an empty prompt, so an End he did not confirm empties nothing and types no `/exit`; his
// confirm ends it.
package splice.sessions.end

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.session.SessionPane
import splice.core.session.SessionTerminal
import splice.sessions.http.FakePanes
import splice.sessions.http.FakeTerminal
import splice.sessions.http.LEAD
import splice.sessions.http.SessionDrive
import splice.sessions.http.SessionDriver
import splice.sessions.http.SessionHandover
import splice.sessions.http.TeamRig
import splice.sessions.http.TerminalSource
import java.io.IOException
import java.nio.file.Path

class SessionEndTest {
    @TempDir
    lateinit var tmp: Path

    private val terminal = FakeTerminal()
    private val panes = FakePanes()
    private val pane = SessionPane("%lead")

    private val handover = object : SessionHandover {
        override suspend fun left(session: String, pid: Long, seconds: Long): Boolean = true

        override suspend fun stopped(session: String, seconds: Long): Boolean = true

        override suspend fun cameOn(session: String, head: String, seconds: Long): Boolean = true
    }

    @Test
    fun `an End he did not confirm leaves a prompt splice cannot read as it is, and his confirm ends it`() {
        val unread = object : SessionTerminal by terminal {
            override fun screen(pane: SessionPane): String = throw IOException("tmux capture-pane did not answer")
        }
        val drive = SessionDrive(TerminalSource { SessionDriver(unread, panes) })
        val end = SessionEnd(drive, handover, TeamRig(tmp).registry)
        panes.remember(LEAD, pane)

        val asked = runBlocking { end.end(LEAD, confirm = false) }

        assertEquals(HttpStatusCode.Conflict, asked.status, asked.body)
        assertEquals("refused", Json.parseToJsonElement(asked.body).jsonObject["reason"]?.jsonPrimitive?.content)
        assertTrue(asked.body.contains("could not read its screen"), asked.body)
        assertTrue(terminal.sent.isEmpty() && terminal.pressed.isEmpty(), "nothing emptied and no /exit typed")

        val confirmed = runBlocking { end.end(LEAD, confirm = true) }

        assertEquals(HttpStatusCode.OK, confirmed.status, confirmed.body)
        assertEquals(listOf(pane to "/exit"), terminal.sent)
    }
}
