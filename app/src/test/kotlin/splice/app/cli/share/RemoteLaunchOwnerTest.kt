// LAN share (docs/specs/lan-share.md): a node's Claude Code session of a remote command is recorded as that command's
// launch, as a head's session is, so the launcher starts it without warning that its ownership could not be recorded.
// Driven as the launcher runs it, `splice record-launch <pid> <command> <base url> session other`, over a node's own
// splice.toml under a home of its own. Only the address the command's `[remote.<command>]` entry sends its turns to
// counts: any other base URL is no launch splice made, and is refused as before.
package splice.app.cli.share

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.CommandParser
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.process.LaunchOwners
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

class RemoteLaunchOwnerTest {
    @Test
    fun `a node's session of a remote command is recorded as that command's, and no other address is`(
        @TempDir home: Path,
    ) {
        val config = home.resolve(".config/splice/splice.toml")
        Files.createDirectories(config.parent)
        Files.writeString(config, NODE_TOML)
        // record-launch records the process that started it, which here is this test's own parent.
        val launcher = ProcessHandle.current().parent().get().pid()
        UserHome.within(home) {
            assertEquals(config, TopologyLoader.configPath(), "the node's splice.toml is the one record-launch reads")
            val state = StatePaths().stateDir
            assertTrue(state.startsWith(home), "the node's state is the one record-launch writes: $state")

            assertEquals(1, recordLaunch(launcher, "claudex-work", "http://elsewhere.lan:3097/claudex/@work"))
            assertEquals(1, recordLaunch(launcher, "claudex-work", "http://torad-server.lan:3097/claudex"))
            assertNull(LaunchOwners(state).read(launcher), "an address the entry doesn't name records nothing")

            assertEquals(0, recordLaunch(launcher, "claudex-work", "http://torad-server.lan:3097/claudex/@work"))
            val owner = LaunchOwners(state).read(launcher)
            assertEquals("claudex-work", owner?.head)
            assertEquals("http://torad-server.lan:3097/claudex/@work", owner?.baseUrl)
        }
    }

    private fun recordLaunch(pid: Long, command: String, baseUrl: String): Int? = runBlocking {
        CommandParser().parse(arrayOf("record-launch", "$pid", command, baseUrl, "session", "other"))?.run()
    }
}

private const val NODE_TOML = """
[daemon]
control_port = 3096

[providers.anthropic]
dialect = "anthropic-passthrough"
base_url = "https://api.anthropic.com"
auth = { kind = "client" }

[heads.plain]
provider = "anthropic"
port = 3104
discovery_prefix = "claude-plain--"

[remote.claudex]
url = "http://torad-server.lan:3097/claudex"

[remote.claudex-work]
url = "http://torad-server.lan:3097/claudex?auth=work"
"""
