// NEW: `splice check-config` — the boot findings pass against the person's splice.toml, run by the build that is
// about to boot, and nothing else. An install that replaces a running daemon asks the NEW jar this before it
// stops the old one: a splice.toml the new build refuses must leave the old daemon running (fail-closed boot,
// the project law). It reads and reports; it never writes, never starts a daemon and never offers a fix.
package splice.app.cli

import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.core.util.TopologyRefusal
import splice.topology.ConfigFindings
import splice.topology.ConfigRead
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
        val path = TopologyLoader.configPath(env)
        // Boot's own rule (TopologyLoader.readOrMaterialize): only PROVEN absence is a first run, which boots a
        // starter. An existing file that cannot be read, or a dangling symlink, is something boot refuses on, so it is
        // a refusal here too; passing it would let the replacement stop the serving daemon for a boot that cannot happen.
        val text = try {
            Files.readString(path)
        } catch (failure: NoSuchFileException) {
            return if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) unreadable(failure) else 0
        } catch (failure: IOException) {
            return unreadable(failure)
        }
        val read = ConfigFindings.read(text, path.toAbsolutePath().parent ?: path)
        return if (read is ConfigRead.Refused) refused(read) else 0
    }

    private fun unreadable(failure: IOException): Int {
        said.line("splice: splice.toml cannot be read, so splice will not start: ${SafeFailureText.render(failure)}")
        return CONFIG_REFUSED_EXIT
    }

    private fun refused(read: ConfigRead.Refused): Int {
        said.line("splice: ${SafeFailureText.render(TopologyRefusal(read.findings))}")
        return CONFIG_REFUSED_EXIT
    }
}
