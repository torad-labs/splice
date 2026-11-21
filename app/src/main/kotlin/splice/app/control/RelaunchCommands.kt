// NEW: Oct 26, 2026 (a relaunch brings a session back as it was) — the command a running session was started with,
// read back from its own process: what a relaunch starts it on again. The relaunch of Oct 26 resumed every session on
// its head's own command, so two sessions started as `claude` came back "Not logged in" in the head's config folder,
// and every review came back without its agent.
//
// WHICH COMMAND. A session in its head's own config folder was started by the head's command, which plants that
// folder, and is started by it again. One in any other folder went through the person's wrapped `claude`, the one
// command that runs in his own folders: that `claude` starts it again, with the folder named outright, since the
// terminal's environment may name another. While `claude` is not wrapped, a session in his own ~/.claude was started
// by the Claude head's own command, which runs there too, and is started by it again. Any other is refused, because
// splice cannot say what started it.
//
// WHICH WORDS. The words its process was given, less the ones splice's launch put before his (LaunchRecipeArgs), read
// as the client reads them (ClaudeArgv.resumed): every option he gave is kept, his first prompt is never sent again,
// and it is resumed by its id on the model its row reports. A word the client's grammar cannot place refuses the
// relaunch.
//
// A DOOR SESSION (door-sessions, Nov 20, 2026) was started by the command its launch recorded and is started by it
// again, which goes to the same door: the person's wrapped `claude` for this machine's door and for `[remote.claude]`,
// else the command `splice install` linked for its `[remote.<command>]`. One that is not linked is refused, naming it,
// before anything is ended.
package splice.app.control

import splice.app.control.mount.HeadStartCommands
import splice.client.ClaudeArgv
import splice.core.config.InstallPaths
import splice.core.config.UserHome
import splice.core.topology.CLAUDE_COMMAND
import splice.core.topology.LaunchDoor
import splice.launch.recipe.LaunchRecipeArgs
import splice.launch.wrap.WrappedClaudeLaunch
import splice.sessions.http.MovingSessionModel
import splice.sessions.http.SessionRelaunches
import splice.sessions.http.StartCommand
import splice.sessions.registry.ProcessEnvironment
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import java.nio.file.Files
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
    private val installPaths: InstallPaths = InstallPaths(),
) : SessionRelaunches {
    private val headCommands = HeadStartCommands(heads, installPaths)

    override fun model(record: SessionRecord): String? = models(record)

    override fun command(record: SessionRecord, model: String?): StartCommand {
        val session = record.sessionId ?: return StartCommand.Refused(UNREAD)
        val started = startedIn(record)
        return when (val route = record.route) {
            is SessionRoute.Door -> onDoor(doorProgram(route.door), started, session, model)
            is SessionRoute.Head -> started?.let { startedWith(route.key, it, session, model) }
                ?: StartCommand.Refused(UNREAD)
            // splice launched it on neither, and SessionContinue refuses such a session before it asks
            SessionRoute.Direct, SessionRoute.Unknown -> StartCommand.Refused(UNREAD)
        }
    }

    /** The config folder [record]'s process runs in and the words it was given, or null when either cannot be read. */
    private fun startedIn(record: SessionRecord): Started? {
        val pid = record.process.pid ?: return null
        val reads = processes.current() ?: return null
        val folder = reads.configHome(pid, UserHome.claudeDir())
        val args = reads.arguments(pid)
        return if (folder == null || args == null) null else Started(folder, args)
    }

    /** [started], a client of [head], as the command that starts [session] that way again on [model]: the head's own
     *  command in the head's own folder, else the wrapped `claude` in that folder. */
    private fun startedWith(head: String, started: Started, session: String, model: String?): StartCommand {
        val folder = started.folder
        val own = heads[head]?.launchSpec?.trees?.own?.let { same(it, folder) } == true
        val program = if (own) headCommands.forHead(head) else throughClaude(head, folder)
        val words = ClaudeArgv.resumed(LaunchRecipeArgs.callerArgs(started.args, state, wrapped = !own), session, model)
        return when {
            program !is StartCommand.Ready -> program
            words == null -> StartCommand.Refused(UNPLACED_WORD)
            else -> StartCommand.Ready(program.argv + words)
        }
    }

    /** What starts a session on [door] again, which goes to the same door: the person's wrapped `claude` for this
     *  machine's door and for another machine's `[remote.claude]`, else the command `splice install` linked for its
     *  `[remote.<command>]`. One that is not on this machine is refused, naming it, as a head's missing command is. */
    private fun doorProgram(door: LaunchDoor): StartCommand {
        val wrapped = door.machine == null || door.command == CLAUDE_COMMAND
        val linked = if (wrapped) {
            claude?.door()
        } else {
            installPaths.binDir.resolve(door.command).takeIf(Files::isSymbolicLink)
        }
        return linked?.let { StartCommand.Ready(listOf(it.toString())) }
            ?: if (wrapped) {
                headCommands.notLinked(CLAUDE_COMMAND, "splice install")
            } else {
                headCommands.notLinked(door.command, "splice install ${door.command}")
            }
    }

    /** [started], given to [program], as the command that starts [session] on its door again on [model], with the
     *  words read back as for a session of the wrapped `claude`, and the folder named outright: the terminal it starts
     *  in may name another. A [program] that is not on this machine is the refusal, whatever else was read. */
    private fun onDoor(program: StartCommand, started: Started?, session: String, model: String?): StartCommand {
        val callerArgs = started?.let { LaunchRecipeArgs.callerArgs(it.args, state, wrapped = true) }
        val words = callerArgs?.let { ClaudeArgv.resumed(it, session, model) }
        return when {
            program !is StartCommand.Ready -> program
            started == null -> StartCommand.Refused(UNREAD)
            words == null -> StartCommand.Refused(UNPLACED_WORD)
            else -> StartCommand.Ready(listOf("env") + configDir(started.folder) + program.argv + words)
        }
    }

    /** The wrapped `claude` in config [folder], named outright: the terminal it starts in may name another folder.
     *  While `claude` is not wrapped, a session in his own folder is the Claude command's, started by its own name,
     *  which runs there too: that command starts it again. */
    private fun throughClaude(head: String, folder: Path): StartCommand {
        val vanilla = same(folder, UserHome.claudeDir())
        val program = claude?.of(head)?.let { StartCommand.Ready(listOf(it.toString())) }
            ?: headCommands.forHead(head).takeIf { vanilla && claude?.ownHome(head) == true }
        return when (program) {
            is StartCommand.Ready -> StartCommand.Ready(listOf("env") + configDir(folder) + program.argv)
            is StartCommand.Refused -> program
            null -> StartCommand.Refused("it runs in $folder, and no splice command starts a session there")
        }
    }

    /** The env words that start a session in config [folder]: the person's own ~/.claude by unsetting the variable,
     *  any other by naming it. */
    private fun configDir(folder: Path): List<String> = if (same(folder, UserHome.claudeDir())) {
        listOf("-u", CONFIG_DIR_VARIABLE)
    } else {
        listOf("$CONFIG_DIR_VARIABLE=$folder")
    }

    /** The config [folder] a session's process runs in and the [args] it was given, after its command. */
    private data class Started(val folder: Path, val args: List<String>)

    private fun same(one: Path, other: Path): Boolean =
        one.toAbsolutePath().normalize() == other.toAbsolutePath().normalize()
}
