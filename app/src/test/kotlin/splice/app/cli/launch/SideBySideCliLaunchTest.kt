package splice.app.cli.launch

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.Command
import splice.app.cli.CommandParser
import splice.app.desktop.catalog.CatalogBuild
import splice.app.desktop.catalog.CatalogFile
import splice.app.desktop.catalog.DesktopProviders
import splice.app.desktop.catalog.PublishedCatalog
import splice.app.desktop.catalog.SelectorAliases
import splice.app.desktop.lane.TerminalDoor
import splice.app.provider.ServingProviders
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path

class SideBySideCliLaunchTest {
    @Test
    fun `CommandParser parses splice launch claude and claude-desktop`() {
        val parser = CommandParser()
        val claudeArgs = arrayOf("launch", "claude", "-p", "hi")
        assertEquals(Command.Launch(listOf("claude", "-p", "hi")), parser.parse(claudeArgs))
        assertEquals(Command.Launch(listOf("claude-desktop")), parser.parse(arrayOf("launch", "claude-desktop")))
        assertTrue(parser.help(arrayOf("launch", "--help"))?.contains("claude-desktop") == true)
    }

    @Test
    fun `launchSideBySide always launches on the door and never writes into claude dir`(
        @TempDir home: Path,
    ) = runBlocking {
        UserHome.within(home) {
            val env = EnvReader { null }
            val log = LogSink {}
            val statePaths = StatePaths(envReader = env)
            val published = PublishedCatalog(
                CatalogBuild(),
                CatalogFile(statePaths.modelCatalogFile, log),
                kotlinx.coroutines.Dispatchers.IO,
            )
            val providers = DesktopProviders(
                serving = ServingProviders { emptyList() },
                desktopOnly = ServingProviders { emptyList() },
                aliases = SelectorAliases(emptyList()),
                published = published,
            )
            val terminal = TerminalDoor(Topology(), statePaths, log)
            terminal.open(providers, "http://127.0.0.1:3096/door")
            val spec = requireNotNull(terminal.launchSideBySide())
            assertNotNull(spec)
            assertEquals("http://127.0.0.1:3096/door", spec.env["ANTHROPIC_BASE_URL"])
            assertFalse(
                Files.exists(home.resolve(".claude/commands/login.md")),
                "side-by-side launch must never write into ~/.claude",
            )
        }
    }
}
