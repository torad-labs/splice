// NEW: Oct 26, 2026 (a relaunch brings a session back as it was) — the command a running session was started with,
// read back from its own process: what a relaunch starts it on again. The relaunch of Oct 26 resumed every session on
// its head's own command, so two sessions started as `claude` came back "Not logged in" in the head's config folder,
// and every review came back without its agent.
//
// WHICH COMMAND. A session in its head's own config folder was started by the head's command, which plants that
// folder, and is started by it again. One in any other folder went through the person's wrapped `claude`, the one
// command that runs in his own folders: that `claude` starts it again, with the folder named outright, since the
// terminal's environment may name another. Any other is refused, because splice cannot say what started it.
//
// WHICH WORDS. The words its process was given, less the ones splice's launch put before his (LaunchRecipeArgs), read
// as the client reads them (ClaudeArgv.resumed): every option he gave is kept, his first prompt is never sent again,
// and it is resumed by its id on the model its row reports. A word the client's grammar cannot place refuses the
// relaunch.
package splice.app.control

import splice.app.control.mount.HeadStartCommands
import splice.client.ClaudeArgv
import splice.core.config.InstallPaths
import splice.core.config.UserHome
import splice.launch.recipe.LaunchRecipeArgs
import splice.launch.wrap.WrappedClaudeLaunch
import splice.sessions.http.MovingSessionModel
import splice.sessions.http.SessionRelaunches
import splice.sessions.http.StartCommand
import splice.sessions.registry.ProcessEnvironment
import splice.sessions.registry.SessionRecord
import java.nio.file.Path

private const val CONFIG_DIR_VARIABLE = "CLAUDE_CONFIG_DIR"
private const val UNREAD =
    "splice could not read the command line it was started with, so it cannot start it that way again"
private const val UNPLACED_WORD =
    "it was started with a word Claude Code does not list, so splice cannot tell its options from its prompt"

/** How the daemon reads a running session's process, read at call time: ControlPlane wires it after construction, as
 *  it wires the session driver, so [ConsolePorts] can carry it without naming the registry itself. */
public fun interface ProcessReads {
    public fun current(): ProcessEnvironment?
}

internal class RelaunchCommands(
    private val heads: Map<String, ManagedHead>,
    /** Where the wrapped `claude` is, for a session started through it; null when this daemon launches nothing. */
    private val claude: WrappedClaudeLaunch?,
    private val processes: ProcessReads,
    /** splice's state folder, where a launch writes the MCP files it names: its words, never his. */
    private val state: Path,
    private val models: MovingSessionModel,
    installPaths: InstallPaths = InstallPaths(),
) : SessionRelaunches {
    private val headCommands = HeadStartCommands(heads, installPaths)

    override fun model(record: SessionRecord): String? = models(record)

    override fun command(record: SessionRecord, model: String?): StartCommand {
        val head = record.head ?: return StartCommand.Refused(UNREAD)
        val session = record.sessionId ?: return StartCommand.Refused(UNREAD)
        val pid = record.process.pid
        val reads = processes.current()
        val folder = pid?.let { reads?.configHome(it, UserHome.claudeDir()) }
        val args = pid?.let { reads?.arguments(it) }
        return if (folder == null || args == null) {
            StartCommand.Refused(UNREAD)
        } else {
            startedWith(head, folder, args, session, model)
        }
    }

    /** [args], given to a client of [head] in config [folder], as the command that starts [session] that way again on
     *  [model]: the head's own command in the head's own folder, else the wrapped `claude` in that folder. */
    private fun startedWith(
        head: String,
        folder: Path,
        args: List<String>,
        session: String,
        model: String?,
    ): StartCommand {
        val own = heads[head]?.launchSpec?.trees?.own?.let { same(it, folder) } == true
        val program = if (own) headCommands.forHead(head) else throughClaude(head, folder)
        val words = ClaudeArgv.resumed(LaunchRecipeArgs.callerArgs(args, state, wrapped = !own), session, model)
        return when {
            program !is StartCommand.Ready -> program
            words == null -> StartCommand.Refused(UNPLACED_WORD)
            else -> StartCommand.Ready(program.argv + words)
        }
    }

    /** The wrapped `claude` in config [folder], named outright: the terminal it starts in may name another folder. */
    private fun throughClaude(head: String, folder: Path): StartCommand {
        val wrapped = claude?.of(head)
            ?: return StartCommand.Refused("it runs in $folder, and no splice command starts a session there")
        val vanilla = same(folder, UserHome.claudeDir())
        val dir = if (vanilla) listOf("-u", CONFIG_DIR_VARIABLE) else listOf("$CONFIG_DIR_VARIABLE=$folder")
        return StartCommand.Ready(listOf("env") + dir + wrapped.toString())
    }

    private fun same(one: Path, other: Path): Boolean =
        one.toAbsolutePath().normalize() == other.toAbsolutePath().normalize()
}
