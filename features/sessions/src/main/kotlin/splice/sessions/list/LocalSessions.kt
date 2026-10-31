// The sessions registered on this machine, each joined to the splice head its launch declared, read the way
// `splice sessions` lists them: Claude Code's registry in ~/.claude/sessions, splice's launch roster, and
// splice.toml's head ports. Lifted out of SessionsCommand (Oct 26, 2026), so `splice sessions relaunch` reads the
// same sessions the listing shows and prints their text through the same terminal-safe renderer. Read-only: the
// topology is only read, never materialized, and when it cannot be read no splice launch is placed on a head
// ("unknown head").
package splice.sessions.list

import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
import splice.core.topology.HeadConfig
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.sessions.registry.ProcessEnvironment
import splice.sessions.registry.RouteOfPid
import splice.sessions.registry.SeenArrivalSessions
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionSource
import splice.topology.TopologyLoader
import java.io.IOException
import java.nio.file.Files

private val UNPRINTABLE: Set<Int> = setOf(
    Character.CONTROL.toInt(),
    Character.FORMAT.toInt(),
    Character.LINE_SEPARATOR.toInt(),
    Character.PARAGRAPH_SEPARATOR.toInt(),
    Character.UNASSIGNED.toInt(),
    Character.PRIVATE_USE.toInt(),
    Character.SURROGATE.toInt(),
)

/** This machine's session registry, joined to splice's heads. [errors] takes the one diagnostic: an unreadable
 *  topology, named for [verb]. */
public class LocalSessions(private val errors: TerminalOutput, private val verb: String = "splice sessions") {

    public fun registry(envReader: EnvReader, environment: ProcessEnvironment = ProcessEnvironment()): SessionSource {
        val heads = readHeads(envReader)
        return SessionRegistry(
            UserHome.claudeDir(envReader).resolve("sessions"),
            RouteOfPid { pid ->
                environment.route(pid) { port -> heads.entries.firstOrNull { it.value.port == port }?.key }
            },
        )
    }

    /** [registry] with the head the daemon saw each unplaced session's requests arrive on, as `splice sessions` and
     *  the console show them. */
    public fun joined(envReader: EnvReader): SessionSource = SeenArrivalSessions(registry(envReader), envReader)

    /** Registry text is Claude Code's, not ours: no control, format or unprintable character (C0,
     *  C1, DEL, and the Unicode Cc/Cf/Zl/Zp classes) reaches the terminal, wherever it is printed. Read
     *  by CODE POINT (V4-324): an emoji is two UTF-16 units and one printable character, and filtering
     *  units dropped both halves of it, so `build 🚀` was offered as a SendMessage to `build `. Only a
     *  surrogate standing alone is still SURROGATE here, and it goes. */
    public fun shown(text: String): String = buildString {
        text.codePoints().filter { Character.getType(it) !in UNPRINTABLE }.forEach { appendCodePoint(it) }
    }

    /** Read-only: an absent or malformed topology means every head reads "unknown"; nothing is
     *  written (no starter file) and the listing itself never fails on it. */
    private fun readHeads(envReader: EnvReader): Map<String, HeadConfig> {
        val path = TopologyLoader.configPath(envReader)
        return try {
            TopologyLoader.parse(Files.readString(path)).heads
        } catch (unreadable: IOException) {
            unattributed(SafeFailureText.render(unreadable))
        } catch (ignored: IllegalArgumentException) {
            unattributed("malformed topology")
        } catch (ignored: IllegalStateException) {
            unattributed("malformed topology")
        }
    }

    private fun unattributed(why: String): Map<String, HeadConfig> {
        errors.line("$verb: topology not readable ($why); heads shown as unknown")
        return emptyMap()
    }
}
