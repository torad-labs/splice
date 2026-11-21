// door-sessions: `claude` started on a door, this machine's or another machine's, is recorded as a launch, so the
// launcher prints no "launch ownership could not be recorded" warning and the session has its record. Driven as the
// launcher runs it, `splice record-launch <pid> <command> <base url> session other`, where the base URL is the one the
// launch's Claude Code sends its turns to (the Mantle base URL on the door). This machine's door is the control port at
// /door or /desktop; another machine's is a `[remote.<command>]` whose address names that machine's door.
package splice.app.cli.share

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.CommandParser
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.process.LaunchOwners
import java.nio.file.Files
import java.nio.file.Path

class DoorLaunchOwnerTest {
    @Test
    fun `claude on this machine's door is recorded at either path the door is answered at`(@TempDir home: Path) {
        within(home) { launcher, owners ->
            for (path in listOf("/door", "/desktop")) {
                val door = "http://127.0.0.1:$CONTROL_PORT$path"
                assertEquals(0, recordLaunch(launcher, "claude", door), "a launch on $door is recorded")
                val owner = owners.read(launcher)
                assertEquals("claude", owner?.head, "the record names the command the session was started as")
                assertEquals(door, owner?.baseUrl)
                assertEquals("session", owner?.kind)
            }
        }
    }

    @Test
    fun `claude on another machine's door is recorded at that door's address`(@TempDir home: Path) {
        within(home) { launcher, owners ->
            assertEquals(0, recordLaunch(launcher, "boxdoor", "http://devbox.lan:3097/door"))
            val owner = owners.read(launcher)
            assertEquals("boxdoor", owner?.head)
            assertEquals("http://devbox.lan:3097/door", owner?.baseUrl)
        }
    }

    @Test
    fun `an address that is no door, no head and no remote command is still refused`(@TempDir home: Path) {
        within(home) { launcher, owners ->
            assertEquals(1, recordLaunch(launcher, "claude", "http://127.0.0.1:${CONTROL_PORT + 1}/door"))
            assertEquals(1, recordLaunch(launcher, "claude", "http://devbox.lan:3097/door"))
            assertEquals(1, recordLaunch(launcher, "boxdoor", "http://otherbox.lan:3097/door"))
            assertEquals(1, recordLaunch(launcher, "claude", ""))
            assertNull(owners.read(launcher), "nothing is recorded for an address splice did not plant")
        }
    }

    @Test
    fun `a head's own command is recorded as before`(@TempDir home: Path) {
        within(home) { launcher, owners ->
            assertEquals(0, recordLaunch(launcher, "claude-plain", "http://127.0.0.1:3104"))
            assertEquals("plain", owners.read(launcher)?.head)
        }
    }

    /** [block] run over a machine whose splice.toml is [MACHINE_TOML], with the pid record-launch records (this test's
     *  own parent, which is the process that started it) and the launch records it writes. */
    private fun within(home: Path, block: (Long, LaunchOwners) -> Unit) {
        val config = home.resolve(".config/splice/splice.toml")
        Files.createDirectories(config.parent)
        Files.writeString(config, MACHINE_TOML)
        val launcher = ProcessHandle.current().parent().get().pid()
        UserHome.within(home) { block(launcher, LaunchOwners(StatePaths().stateDir)) }
    }

    private fun recordLaunch(pid: Long, command: String, baseUrl: String): Int? = runBlocking {
        CommandParser().parse(arrayOf("record-launch", "$pid", command, baseUrl, "session", "other"))?.run()
    }
}

private const val CONTROL_PORT = 3096

private const val MACHINE_TOML = """
[daemon]
control_port = $CONTROL_PORT

[providers.anthropic]
dialect = "anthropic-passthrough"
base_url = "https://api.anthropic.com"
auth = { kind = "client" }

[heads.plain]
provider = "anthropic"
port = 3104
discovery_prefix = "claude-plain--"

[remote.boxdoor]
url = "http://devbox.lan:3097/door"
"""
