// NEW: Oct 22, 2026 — End all on a team, judged by what it answers and what each member's terminal is given. A member
// that starts a turn while the members before it close is listed with what it would lose, never ended unasked; the
// members already ended stay ended, and his confirm ends the rest.
package splice.sessions.end

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.session.SessionPane
import splice.sessions.http.BUILDER
import splice.sessions.http.FakePanes
import splice.sessions.http.FakeTerminal
import splice.sessions.http.LEAD
import splice.sessions.http.SessionDrive
import splice.sessions.http.SessionDriver
import splice.sessions.http.SessionHandover
import splice.sessions.http.TeamRig
import splice.sessions.http.TeamSource
import splice.sessions.http.TerminalSource
import java.nio.file.Files
import java.nio.file.Path

class TeamEndTest {
    @TempDir
    lateinit var tmp: Path

    private val rig by lazy { TeamRig(tmp) }
    private val terminal = FakeTerminal()
    private val panes = FakePanes()

    /** What happens while the lead's client exits, before End all reaches the next member. */
    private var whileLeadCloses: () -> Unit = {}

    private val teamEnd by lazy {
        TeamEnd(
            teams = TeamSource { rig.store },
            end = SessionEnd(
                drive = SessionDrive(TerminalSource { SessionDriver(terminal, panes) }),
                handover = object : SessionHandover {
                    override suspend fun left(session: String, pid: Long, seconds: Long): Boolean {
                        // its client went, so Claude Code's registry no longer lists it
                        Files.delete(tmp.resolve("sessions/$pid.json"))
                        if (session == LEAD) whileLeadCloses()
                        return true
                    }

                    override suspend fun stopped(session: String, seconds: Long): Boolean = true

                    override suspend fun cameOn(session: String, head: String, seconds: Long): Boolean = true
                },
                registry = rig.registry,
            ),
        )
    }

    private val leadPane = SessionPane("%lead")
    private val builderPane = SessionPane("%b1")

    /** The builder's client reports a turn in flight, as Claude Code writes it to its registry file. */
    private fun builderWorking() {
        val file = tmp.resolve("sessions/2.json")
        Files.writeString(file, Files.readString(file).replace("{", """{"status":"busy","""))
    }

    @Test
    fun `a member that starts a turn while the ones before it close is listed, and ends only on his confirm`() {
        val team = rig.team()
        panes.remember(LEAD, leadPane)
        panes.remember(BUILDER, builderPane)
        whileLeadCloses = ::builderWorking

        val asked = runBlocking { teamEnd.endAll(team.id, confirm = false) }

        assertEquals(HttpStatusCode.Conflict, asked.status)
        val listed = rig.json(asked.body)
        assertEquals("costs", listed.getValue("reason").jsonPrimitive.content)
        assertEquals(listOf("b1"), rig.column(listed, "members", "slot"))
        assertEquals(listOf("working"), rig.column(listed, "members", "reason"))
        assertEquals(listOf(leadPane to "/exit"), terminal.sent, "the lead ended, and nothing was typed to the builder")

        val confirmed = runBlocking { teamEnd.endAll(team.id, confirm = true) }

        assertEquals(HttpStatusCode.OK, confirmed.status)
        val each = rig.json(confirmed.body)
        assertEquals("ended", rig.find(each, "members", "slot", "lead").getValue("reason").jsonPrimitive.content)
        assertEquals("200", rig.find(each, "members", "slot", "b1").getValue("status").jsonPrimitive.content)
        assertEquals(listOf(leadPane to "/exit", builderPane to "/exit"), terminal.sent)
    }
}
