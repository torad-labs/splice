// NEW: the MCP hub's three systemd units, for a machine hostshield has not given them (hostshield-lead, Oct 12). The
// bytes under resources/splice/lifecycle/hub are hostshield 88076af5d's canonical units, byte for byte: hostshield's
// apply owns them wherever it is installed, and this copy only covers a machine without it.
package splice.lifecycle.hub

import splice.core.terminal.TerminalOutput
import splice.core.util.Cancellables
import splice.lifecycle.start.UserManagerBus
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

/** Creates each unit in [unitDir], the user's own systemd/user directory, only where it is positively absent, and
 *  never touches one that is there.
 *
 *  POSITIVELY ABSENT means two things at once: nothing of that name in [unitDir], no unit file, symlink (a masked
 *  unit is one), or drop-in directory; and the user manager answering, with exit 0, that the unit's load state is
 *  `not-found`. A systemctl that fails, times out or cannot reach the manager is not an answer, so nothing is
 *  created, and the daemon keeps its in-process MCP path. The write is create-only, so a unit that appears between
 *  the check and the write is kept. Nothing is enabled or started here. */
public class HubUnits(private val unitDir: Path, private val output: TerminalOutput) {
    public fun install() {
        val written = UNITS.filter { name -> absent(name) && create(name) }
        if (written.isEmpty()) return
        systemctl(listOf("daemon-reload"))
        written.forEach { output.line("splice: wrote $it to $unitDir, since no unit of that name was installed") }
    }

    private fun absent(name: String): Boolean =
        !Files.exists(unitDir.resolve(name), LinkOption.NOFOLLOW_LINKS) &&
            !Files.exists(unitDir.resolve("$name.d"), LinkOption.NOFOLLOW_LINKS) &&
            systemctl(listOf("show", "-p", "LoadState", "--value", name)) == NOT_FOUND

    private fun create(name: String): Boolean {
        val bytes = javaClass.getResourceAsStream("$RESOURCES/$name")?.use { it.readBytes() } ?: return false
        Files.createDirectories(unitDir)
        return Cancellables.runCatchingBestEffort {
            Files.write(unitDir.resolve(name), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        }.isSuccess
    }

    /** The command's trimmed stdout when it exits 0 within the bound; null for any other outcome. */
    private fun systemctl(args: List<String>): String? = Cancellables.runCatchingBestEffort {
        val process = UserManagerBus.supply(ProcessBuilder(listOf("systemctl", "--user") + args))
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        val done = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!done) process.destroyForcibly()
        out.trim().takeIf { done && process.exitValue() == 0 }
    }.getOrNull()
}

private val UNITS = listOf("app-mcp.slice", "splice-mcp-front.socket", "splice-mcp-front.service")
private const val RESOURCES = "/splice/lifecycle/hub"
private const val NOT_FOUND = "not-found"
private const val TIMEOUT_MS = 15_000L
