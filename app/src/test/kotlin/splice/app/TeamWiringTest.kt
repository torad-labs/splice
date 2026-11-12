// The daemon side of the teams: the one team store under the state dir, the session-address lookup over
// the registry /api/sessions reads, and ONE store shared by the routes and the heads.
package splice.app

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.control.SilentHeadProbes
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.sessions.teams.Team
import splice.sessions.teams.TeamSlot
import java.nio.file.Files
import java.nio.file.Path

private const val SESSION = "e5e5e5e5-0000-4000-8000-000000000005"

class TeamWiringTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `the team store is teams json under the state dir`() {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        ConsoleWiring.teamStore(paths).upsert(Team(name = "atlas"))
        assertTrue(Files.exists(paths.stateDir.resolve("teams.json")))
    }

    @Test
    fun `a session's address comes from the registry under the home the state dir lives in`() {
        val paths = StatePaths(baseOverride = tmp.resolve("home/.splice/state"))
        val sessions = Files.createDirectories(tmp.resolve("home/.claude/sessions"))
        val pid = ProcessHandle.current().pid()
        Files.writeString(
            sessions.resolve("$pid.json"),
            """{"pid":$pid,"sessionId":"$SESSION","messagingSocketPath":"/run/lead.sock"}""",
        )
        val address = ConsoleWiring.sessionAddress(paths)
        assertEquals("uds:/run/lead.sock", address(SESSION))
        assertNull(address("someone-else"))
    }

    @Test
    fun `the routes and the heads share one team store`() {
        val paths = StatePaths(baseOverride = tmp.resolve("plane-state"))
        val plane = ControlPlane(
            DaemonEnvironment(paths, ConfigService(paths), MgmtKey(paths), { }),
            { },
        )
        val srv = checkNotNull(
            runBlocking {
                plane.start(
                    controlPort = 0, // OS-assigned at bind: no leased port to lose before the bind
                    heads = emptyMap(),
                    failedHeads = { 0 },
                    headCount = 0,
                    probes = SilentHeadProbes,
                )
            },
        ) { "the control plane did not bind" }
        try {
            val store = checkNotNull(srv.ports.teams) { "the control plane must offer the team store" }
            val slots = checkNotNull(plane.console.slots) { "the control plane must give the heads a slot resolver" }
            val team = Team(name = "atlas", slots = listOf(TeamSlot(id = "b1", role = "builder", head = "h")))
            val id = store.upsert(team).id
            store.bind(id, mapOf("b1" to SESSION))
            assertEquals("slot:$id/b1", slots.forSession(SESSION)?.source, "a bind via the routes reaches the heads")
        } finally {
            srv.stop()
            plane.cancelProbes()
        }
    }
}
