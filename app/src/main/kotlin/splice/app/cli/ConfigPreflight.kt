// NEW: `splice check-config` — the boot findings pass against the person's splice.toml, run by the build that is
// about to boot, and nothing else. An install that replaces a running daemon asks the NEW jar this before it
// stops the old one: a splice.toml the new build refuses must leave the old daemon running (fail-closed boot,
// the design rule). It reads and reports; it never writes, never starts a daemon and never offers a fix.
package splice.app.cli

import splice.app.modes.ModeBoot
import splice.app.provider.local.LocalRowBootFindings
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.core.util.TopologyRefusal
import splice.topology.BootSettings
import splice.topology.ConfigFindings
import splice.topology.ConfigRead
import splice.topology.RetiredSettings
import splice.topology.TopologyLoader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException

/** The exit code that says "this build would refuse boot on your splice.toml". Distinct from 1 and 2, which an
 *  older jar's unknown-verb answer and a crash already use, so a caller can tell a refusal from a build that has
 *  no such verb. */
internal const val CONFIG_REFUSED_EXIT = 3

internal class ConfigPreflight(
    private val env: EnvReader,
    private val said: TerminalOutput = TerminalOutput(::println),
) {
    /** 0 when the file is absent or has no finding, [CONFIG_REFUSED_EXIT] with every finding printed when it has any. */
    fun check(): Int {
        val error = refusal() ?: return 0
        said.line("splice: $error")
        return CONFIG_REFUSED_EXIT
    }

    /** Null when splice.toml is absent or clean; the refusal sentence otherwise (shared by `check-config` and
     *  `POST /api/daemon/restart` so a bad config never reaches the launcher). */
    fun refusal(): String? {
        val path = TopologyLoader.configPath(env)
        val text = try {
            Files.readString(path)
        } catch (failure: NoSuchFileException) {
            return if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) unreadableReason(failure) else null
        } catch (failure: IOException) {
            return unreadableReason(failure)
        }
        val dir = path.toAbsolutePath().parent ?: path
        val beside = BootSettings(RetiredSettings(env), ModeBoot().found())
        val read = ConfigFindings.read(text, dir, dir, LocalRowBootFindings(env), beside)
        return if (read is ConfigRead.Refused) SafeFailureText.render(TopologyRefusal(read.findings)) else null
    }

    private fun unreadableReason(failure: IOException): String =
        "splice.toml cannot be read, so splice will not start: ${SafeFailureText.render(failure)}"
}
