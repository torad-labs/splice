// NEW: desktop-full (docs/specs/desktop-full.md, df-managed-launcher). `splice desktop launch <command> [args...]`:
// what a managed `processWrapper` runs the Code tab's Claude Code through. When Claude Code's managed settings name a
// process wrapper, Claude Desktop starts the Code tab through it with every credential stripped from the launch
// (ANTHROPIC_API_KEY, ANTHROPIC_AUTH_TOKEN, CLAUDE_CODE_OAUTH_TOKEN, AWS_BEARER_TOKEN_BEDROCK) and
// CLAUDE_CODE_PROVIDER_MANAGED_BY_HOST=1, so a session on splice's door has no key to send. This runs the command with
// its own arguments and exits with its exit code, giving a launch on this splice's door the desktop key where Claude
// Code reads it (DesktopLaunchEnv). The command may be another wrapper followed by Claude Code's own command line.
// Like the credential helper, it reads the key from splice's state folder and needs no running daemon;
// `<state dir>/desktop-launch` (DesktopPrograms) names that folder with `--state-dir`.
//
// desktop-antigravity (docs/specs/desktop-antigravity.md, decisions 3 and 5): a session Desktop starts on a
// `claude-antigravity--` model runs on `splice desktop acp` in place of Claude Code, and a stream-json session runs in
// the pipe (DesktopPipe), so a live switch between Antigravity and any other model changes the program under it. A
// launch that carries the no-new-privileges bit on Linux runs itself again outside it first (PrivilegeEscape).
package splice.app.cli.desktop

import kotlinx.coroutines.CoroutineDispatcher
import splice.app.desktop.pipe.ClaudeLineReader
import splice.app.desktop.pipe.DesktopPipe
import splice.app.desktop.pipe.LegEnvironment
import splice.app.desktop.pipe.NOT_STARTED_EXIT
import splice.app.desktop.pipe.NativePeerLeg
import splice.app.desktop.pipe.PipeLegs
import splice.app.desktop.setup.SpliceSelf
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.upstream.codemode.ProcessDispatchers
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors

/** The launch under [env], what goes wrong said to [err]. [inheritedEnv] is laid over the environment programs start
 *  with, [escape] decides whether it runs again outside the no-new-privileges bit, [acp] is the command that runs
 *  `splice desktop acp`, [input] is Desktop's standard input and [io] waits on programs. */
internal class DesktopLaunch(
    private val env: EnvReader,
    private val err: TerminalOutput,
    private val inheritedEnv: Map<String, String> = emptyMap(),
    private val escape: PrivilegeEscape = PrivilegeEscape(env),
    private val acp: List<String> = SpliceSelf().command() + listOf("desktop", "acp"),
    private val input: InputStream = System.`in`,
    private val io: CoroutineDispatcher = ProcessDispatchers().io(),
) {
    /** [args] run as a command, given the desktop key when it launches on this splice's door; its exit code. */
    suspend fun run(args: List<String>): Int {
        val state = args.takeIf { it.firstOrNull() == STATE_DIR_FLAG }?.getOrNull(1)
        val command = if (state != null) args.drop(2) else args
        if (command.isEmpty()) {
            err.line("usage: splice desktop launch <command> [args...]")
            return LAUNCH_USAGE_EXIT
        }
        return escaped(args) ?: here(command, DesktopLaunchEnv(env, err, inheritedEnv, state))
    }

    /** [command] run in this process's place; its exit code. A stream-json session runs in the pipe, so a live switch
     *  can change its program, and one on an Antigravity model runs on `splice desktop acp` from the start. */
    private suspend fun here(command: List<String>, session: DesktopLaunchEnv): Int {
        val line = ClaudeLineReader().of(command)
        val peers = session.nativePeerLeg()
        if (!line.streamJson && !line.antigravity) return direct(command, session.programs, peers)
        return DesktopPipe(PipeLegs(acp, session.programs, session.sessions(), peers), io, err).run(line, input)
    }

    /** [command] run with Desktop's own streams, as it came; its exit code. */
    private fun direct(command: List<String>, environment: LegEnvironment, peers: NativePeerLeg?): Int {
        val process = ProcessBuilder(peers?.args(command) ?: command).inheritIO()
        environment.apply(process.environment(), claudeCode = true)
        return try {
            val child = process.start()
            peers?.registered(child)
            // Desktop stopping the wrapper stops the session it started, rather than leaving it running alone.
            val stop = Executors.defaultThreadFactory().newThread { child.destroy() }
            Runtime.getRuntime().addShutdownHook(stop)
            child.waitFor().also { Runtime.getRuntime().removeShutdownHook(stop) }
        } catch (e: IOException) {
            err.line("splice desktop launch: ${command.first()} could not be started: ${SafeFailureText.render(e)}")
            NOT_STARTED_EXIT
        }
    }

    /** The session's exit code once this launch ran again outside the no-new-privileges bit; null when it runs here. */
    private fun escaped(args: List<String>): Int? {
        val unit = "$UNIT_PREFIX${ProcessHandle.current().pid()}"
        val names = ProcessBuilder().environment().keys + inheritedEnv.keys
        val command = escape.command(SpliceSelf().command() + listOf("desktop", "launch") + args, names, unit)
            ?: return null
        val systemctl = escape.program(SYSTEMCTL_PROGRAM)?.toString() ?: SYSTEMCTL_PROGRAM
        return EscapedLaunch(command, unit, systemctl, err).run(inheritedEnv)
    }
}

// How `<state dir>/desktop-launch` names splice's state folder, ahead of the command.
internal const val STATE_DIR_FLAG = "--state-dir"

// The user unit a launch that escapes the no-new-privileges bit runs as, with this process's pid after it.
private const val UNIT_PREFIX = "splice-desktop-launch-"

// Shell convention for invalid command arguments.
internal const val LAUNCH_USAGE_EXIT = 2
