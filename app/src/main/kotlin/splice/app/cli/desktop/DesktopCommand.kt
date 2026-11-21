// NEW: desktop-door (docs/specs/desktop-door.md, decision 4); desktop-full (docs/specs/desktop-full.md). `splice
// desktop`: put Claude Desktop on splice, or take it back off, and say what is so. The daemon holds the door, the key and
// the heads, so every verb but `credential` and `launch` asks the running daemon (DoorClient) and prints its answer:
// what changed, which model Desktop starts on and why, or what made it refuse.
//
//   splice desktop [--json]                        the mode Desktop is in, whether it was reopened since splice changed
//                                                  it, the door, Desktop's default model, its picker, the heads left
//                                                  out and why, and the last turn through the door
//   splice desktop on [--model <id>] [--yes]       put all of Claude Desktop on splice, with nothing to paste: splice
//                                                  writes Desktop's configuration itself. It says first what that
//                                                  costs, and asks on a terminal; --yes goes on without asking
//   splice desktop off                             take back out exactly what `on` put in
//   splice desktop rotate-key                      replace the desktop key
//   splice desktop credential                      Desktop's credential helper: prints the desktop key to the program
//                                                  that runs it, never to a terminal; needs no daemon
//   splice desktop launch <command> [args...]      what a managed processWrapper starts the Code tab through: runs the
//                                                  command, with the desktop key where Desktop stripped it when it
//                                                  launches on this door; needs no daemon
package splice.app.cli.desktop

import splice.app.LifecycleWiring
import splice.app.cli.hook.StreamDevice
import splice.app.cli.hook.StreamTerminal
import splice.core.config.StatePaths
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader

internal const val DESKTOP_USAGE =
    "[--json] | on [--model <id>] [--yes] | off | rotate-key | credential | launch <command> [args...]"

/** Whether the person said yes to a question asked on the terminal. */
internal fun interface DesktopConfirm {
    fun yes(question: String): Boolean
}

internal class DesktopCommand(
    private val env: EnvReader = EnvReader(System::getenv),
    private val out: TerminalOutput = TerminalOutput { println(it) },
    private val err: TerminalOutput = TerminalOutput(System.err::println),
    daemon: DaemonUp = DaemonUp(LifecycleWiring::ensureDaemon),
    private val stdin: StreamTerminal = StreamDevice(),
    private val confirm: DesktopConfirm = DesktopConfirm { question ->
        System.console()?.readLine(question)?.trim()?.lowercase() in setOf("y", "yes")
    },
) {
    private val client = DoorClient("desktop", env, err, daemon)
    private val verbs = DoorVerbs(
        client,
        out,
        DesktopSaid(out),
        opens = "on",
        plain = setOf("off", "rotate-key"),
        usage = "splice desktop $DESKTOP_USAGE",
    )

    fun run(args: List<String>): Int = when {
        args == listOf("credential") -> DesktopCredential(out, err).run(StatePaths(envReader = env).desktopKeyFile)
        args.firstOrNull() == "launch" -> DesktopLaunch(env, err).run(args.drop(1))
        args.firstOrNull() == "on" && "--yes" in args -> verbs.run(args - "--yes")
        args.firstOrNull() == "on" -> asked(args)
        else -> verbs.run(args)
    }

    /** `on` without `--yes`: what it costs said first, then asked on a terminal. With no terminal to ask on, nothing
     *  changes and the person is told to say `--yes`. */
    private fun asked(args: List<String>): Int {
        out.line("splice: $COSTS")
        if (!stdin.isTerminal()) return client.refused(NO_TERMINAL)
        if (!confirm.yes(QUESTION)) {
            out.line("splice: nothing was changed.")
            return 1
        }
        return verbs.run(args)
    }
}

// What putting Desktop on splice costs, said before it changes anything (desktop-full, outcome 1).
private const val COSTS = "all of Claude Desktop will run on splice: Chat and Cowork too, not only the Code tab. " +
    "Your conversations are kept on this computer, not in your claude.ai account. Remote Control and Anthropic's " +
    "cloud environments are not offered, and SSH sessions can't reach splice, which listens on this computer only. " +
    "`splice desktop off` puts it back."

private const val QUESTION = "Put Claude Desktop on splice? [y/N] "

private const val NO_TERMINAL = "there is no terminal to ask on, so nothing was changed; run " +
    "`splice desktop on --yes` to go on"
