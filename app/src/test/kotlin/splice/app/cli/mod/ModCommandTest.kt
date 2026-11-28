package splice.app.cli.mod

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.control.ControlAuth
import splice.app.control.controlServerFor
import splice.app.mods.install.InstalledMods
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import splice.core.util.LogSink
import java.nio.file.Files
import java.nio.file.Path

class ModCommandTest {
    @Test
    fun `every command explains the next step and the lost session start`(@TempDir root: Path) = runTest {
        val state = root.resolve("state")
        val local = mapOf(
            "HOME" to root.toString(),
            "SPLICE_STATE_DIR" to state.toString(),
            "SPLICE_CONFIG" to root.resolve("splice.toml").toString(),
            "SPLICE_BUN" to root.resolve("no-bun").toString(),
        )
        val paths = StatePaths(baseOverride = state, envReader = EnvReader(local::get))
        val quiet = LogSink {}
        val server = controlServerFor(0, emptyMap(), ConfigService(paths), ControlAuth(MgmtKey(paths, quiet), quiet))
        server.ports.mods = InstalledMods(paths)
        server.start()
        try {
            val output = mutableListOf<String>()
            val errors = mutableListOf<String>()
            val env = local + ("SPLICE_CONTROL_PORT" to server.listeningPort.toString())
            val cli = ModCommand(EnvReader(env::get), { output.add(it) }, { errors.add(it) })
            val source = folder(root)
            val installed = ask(cli, output, "install", source.toString())
            assertTrue(installed.contains("Declared capability: watch-sessions"), installed)
            assertTrue(installed.contains("Bun not found"), installed)
            assertTrue(installed.contains("splice mod enable board"), installed)
            assertTrue(installed.contains("No session.start is replayed"), installed)
            assertTrue(ask(cli, output, "list").contains("enabled; failed: Bun not found; dropped events: 0"))
            assertTrue(ask(cli, output, "disable", "board").contains("It will stop receiving events"))
            assertTrue(ask(cli, output, "enable", "board").contains("including in sessions already running"))
            assertTrue(ask(cli, output, "enable", "board", "--accept").contains("Accepted the current files"))
            val store = Files.createDirectories(state.resolve("mods/data/board"))
            Files.writeString(store.resolve("store.sqlite"), "")
            val log = Files.createDirectories(root.resolve("logs/mods")).resolve("board.log")
            Files.writeString(log, "")
            val removed = ask(cli, output, "remove", "board")
            assertTrue(removed.contains("Its installed copy was removed"), removed)
            assertTrue(removed.contains("Kept on disk: its data store and its log."), removed)
            assertTrue(removed.contains("trash '$store' '$log'"), removed)
            assertTrue(ask(cli, output, "list").contains("No mods installed"))
            assertTrue(ask(cli, output, "install", "--link", source.toString()).contains("not hash-checked"))
            assertTrue(ask(cli, output, "remove", "board").contains("Its source folder was left alone"))
            assertTrue(errors.isEmpty(), errors.toString())
            val empty = Files.createDirectory(root.resolve("empty"))
            assertEquals(1, cli.run(listOf("install", empty.toString())))
            assertTrue(errors.single().contains(".splice-mod/mod.json"))
            println(errors.single())
        } finally {
            server.stop()
        }
    }

    private fun folder(root: Path): Path {
        val source = root.resolve("board")
        val metadata = Files.createDirectories(source.resolve(".splice-mod"))
        Files.writeString(
            metadata.resolve("mod.json"),
            """{"name":"board","version":"1.2.3","description":"Track sessions.","capabilities":["watch-sessions"]}""",
        )
        return source
    }

    private fun ask(command: ModCommand, output: MutableList<String>, vararg argv: String): String {
        assertEquals(0, command.run(argv.toList()))
        return output.last().also { println("splice mod ${argv.joinToString(" ")}\n$it") }
    }
}
