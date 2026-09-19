// NEW: V4-131 — the daemon side of the teams: ConsoleWiring's one team store under the state dir and
// its session-address lookup over the registry /api/sessions reads, HeadServerFactory handing every
// head the publisher's slot resolver, and ControlPlane giving the routes and the heads ONE store.
//
// THE CONTROLPLANE PIN IS EXPECTED-RED until the committer applies V4-131's ControlPlane lines (the
// CONTROLSERVER RECONCILED law: ControlPlane is orchestrator-applied, so this row writes the pin and the
// commit records red before and green after). It fails on `srv.ports.teams` being null.
package console.v4131

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.ConsoleWiring
import splice.app.ControlPlane
import splice.control.DashboardPage
import splice.control.TurnPathStalled
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.teams.Team
import splice.core.teams.TeamSlot
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

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
    fun `every head gets the publisher's slot resolver`() {
        val factory = source("gateway/app/src/main/kotlin/splice/app/head/HeadServerFactory.kt")
        assertTrue(
            factory.contains("slotInstructions = console?.slots,"),
            "HeadServerFactory must hand each head the publisher's SlotInstructions, or no slot text is ever sent",
        )
    }

    @Test
    fun `the routes and the heads share one team store (EXPECTED-RED until ControlPlane is applied)`() {
        val paths = StatePaths(baseOverride = tmp.resolve("plane-state"))
        val plane = ControlPlane(
            paths,
            ConfigService(paths),
            MgmtKey(paths),
            DashboardPage { "<!doctype html>" },
            { },
            { },
        )
        val srv = checkNotNull(
            plane.start(
                controlPort = ServerSocket(0).use { it.localPort },
                heads = emptyMap(),
                failedHeads = { 0 },
                headCount = 0,
                turnPathStalled = TurnPathStalled { emptyList() },
            ),
        ) { "the control plane did not bind" }
        try {
            val store = checkNotNull(srv.ports.teams) { "ControlPlane must assign srv.ports.teams" }
            val slots = checkNotNull(plane.console.slots) { "ControlPlane must give the publisher a SlotInstructions" }
            val team = Team(name = "atlas", slots = listOf(TeamSlot(id = "b1", role = "builder", head = "h")))
            val id = store.upsert(team).id
            store.bind(id, mapOf("b1" to SESSION))
            assertEquals("slot:$id/b1", slots.forSession(SESSION)?.source, "a bind via the routes reaches the heads")
        } finally {
            srv.stop()
            plane.cancelProbes()
        }
    }

    private fun source(relative: String): String {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve(relative)
            if (Files.exists(candidate)) return Files.readString(candidate)
            dir = dir.parent
        }
        error("$relative not found above the working directory")
    }
}
