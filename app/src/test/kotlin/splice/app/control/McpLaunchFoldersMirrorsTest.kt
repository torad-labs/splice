// NEW: LAN share, round 11 of PR #419's review. The goal: the MCP status counts the Claude Code launches on this machine,
// where they started and how many sessions each covers. A session another machine shares is listed here as a mirror
// (`<machine>/<name>`, its record's kind "splice-mirror", its folder "/"), and it is no launch here: it is left out.
// McpLaunchFolders over registry records as the registry reads them, both with this test's own pid, whose
// environment is real.
package splice.app.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.client.mcp.McpLaunchPlanner
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionClient
import splice.sessions.registry.SessionListing
import splice.sessions.registry.SessionProcess
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionSource
import splice.sessions.registry.SessionStatus
import java.nio.file.Files
import java.nio.file.Path

class McpLaunchFoldersMirrorsTest {
    @TempDir
    lateinit var tmp: Path

    private fun record(name: String, cwd: String, kind: String) = SessionRecord(
        sessionId = "$name-id",
        name = name,
        status = SessionStatus("idle"),
        route = SessionRoute.Unknown,
        availability = SessionAvailability.LIVE,
        process = SessionProcess(ProcessHandle.current().pid(), cwd, 1L, 1L, null),
        client = SessionClient(kind, "2.1.309"),
    )

    @Test
    fun `a session another machine shares is no launch here, so the MCP status leaves it out`() {
        val project = Files.createDirectories(tmp.resolve("project")).toRealPath()
        val records = listOf(
            record("splice", project.toString(), "interactive"),
            record("torad-server/rig-lead", "/", "splice-mirror"),
            record("torad-server/marlin", "/", "splice-mirror"),
        )
        val sessions = object : SessionSource {
            override fun read() = records

            override fun list() = SessionListing(records)
        }

        val plans = McpLaunchFolders(sessions, planner = McpLaunchPlanner(tmp)).read()

        assertEquals(listOf(project), plans.map { it.plan.folder }, "the one launch here: $plans")
        assertEquals(1, plans.sumOf { it.sessions }, "covering the one session that runs here")
    }
}
