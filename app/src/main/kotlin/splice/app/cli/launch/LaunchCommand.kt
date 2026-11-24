// NEW: side-by-side-launchers. `splice launch claude [args...]` and `splice launch claude-desktop [args...]`:
// launches Claude Code CLI or Claude Desktop side-by-side on Splice's door without wrapping or
// altering the user's default `claude` CLI or default Claude Desktop app.
package splice.app.cli.launch

import splice.app.desktop.setup.SideBySideDesktop
import splice.core.config.InstallPaths
import splice.core.terminal.TerminalOutput
import splice.core.topology.CLAUDE_SPLICE_COMMAND
import splice.core.util.EnvReader
import java.nio.file.Files

internal const val LAUNCH_USAGE: String = "claude [--profile <name>] [args...] | claude-desktop [args...]"

internal class LaunchCommand(
    private val env: EnvReader = EnvReader(System::getenv),
    private val out: TerminalOutput = TerminalOutput { println(it) },
    private val err: TerminalOutput = TerminalOutput(System.err::println),
    private val desktop: SideBySideDesktop = SideBySideDesktop(env, out, err),
) {
    fun run(args: List<String>): Int = when (args.firstOrNull()) {
        TARGET_CLAUDE -> launchClaude(args.drop(1))
        TARGET_CLAUDE_DESKTOP -> desktop.launch(args.drop(1))
        else -> {
            err.line("usage: splice launch $LAUNCH_USAGE")
            2
        }
    }

    /** Fallback when `splice launch claude` is invoked directly via `java -jar` instead of `splice-launch`. */
    private fun launchClaude(extraArgs: List<String>): Int {
        val shim = InstallPaths(envReader = env).shareDir.resolve("splice-launch")
        if (!Files.isRegularFile(shim)) {
            err.line("splice: launch shim not found at $shim (run install.sh)")
            return 1
        }
        val cmd = listOf(shim.toString()) + extraArgs
        val proc = ProcessBuilder(cmd).inheritIO().apply {
            environment()["SPLICE_HEAD"] = CLAUDE_SPLICE_COMMAND
        }.start()
        return proc.waitFor()
    }
}

private const val TARGET_CLAUDE = "claude"
private const val TARGET_CLAUDE_DESKTOP = "claude-desktop"
