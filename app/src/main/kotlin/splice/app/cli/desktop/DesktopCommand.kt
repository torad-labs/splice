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
//   splice desktop acp [claude args...]            what Desktop's launcher runs in place of `claude` for an Antigravity
//                                                  model: the session runs on `agy acp` (DesktopAcpCommand); needs no
//                                                  daemon
//   splice desktop wrapper [--print | --check]     the processWrapper (and parentSettingsBehavior "merge") Claude Code's
//                                                  managed settings need to start the Code tab through desktop-launch:
//                                                  shown as they should read, or checked
package splice.app.cli.desktop

import splice.app.LifecycleWiring
import splice.app.cli.hook.StreamDevice
import splice.app.cli.hook.StreamTerminal
import splice.app.desktop.acp.DesktopAcpCommand
import splice.app.seatd.SeatdAdvice
import splice.app.seatd.SeatdChoice
import splice.app.seatd.SeatdConsent
import splice.core.config.StatePaths
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.lifecycle.seatd.SeatdCompanion
import splice.lifecycle.seatd.SeatdInstaller

internal const val DESKTOP_USAGE =
    "[--json] | on [--model <id>] [--yes] | off | rotate-key | credential | launch <command> [args...] | " +
        "acp [claude args...] | wrapper [--print | --check]"

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
    private val seatd: SeatdCompanion = SeatdInstaller(env),
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

    suspend fun run(args: List<String>): Int = when {
        args.firstOrNull() == "acp" -> DesktopAcpCommand(env, err).run(args.drop(1))
        args == listOf("credential") -> DesktopCredential(out, err).run(StatePaths(envReader = env).desktopKeyFile)
        args.firstOrNull() == "launch" -> DesktopLaunch(env, err).run(args.drop(1))
        args.firstOrNull() == "wrapper" -> DesktopWrapper(env, out, err).run(args.drop(1))
        args.firstOrNull() == "on" -> on(args)
        else -> verbs.run(args)
    }

    private fun on(args: List<String>): Int {
        val open = args - "--yes"
        if (!verbs.canOpen(open)) return verbs.run(open)
        val blocked = if (seatd.installed() == null) installCompanion() else null
        return blocked ?: if ("--yes" in args) verbs.run(open) else asked(open)
    }

    /** A skipped or refused install ends the command before it tries to enable Desktop. */
    private fun installCompanion(): Int? {
        if (!acceptCompanion()) {
            out.line(
                "splice desktop: left Desktop unchanged. To install the companion and enable Desktop later, " +
                    "run `${SeatdAdvice.DESKTOP}` in a terminal.",
            )
            return 0
        }
        val result = seatd.install(SeatdAdvice.DESKTOP)
        if (result is SeatdInstaller.Result.Failed) return client.refused(result.message)
        SeatdAdvice.lines(result).forEach(out::line)
        return null
    }

    /** The Desktop mode confirmation cannot authorize installing another program. */
    private fun acceptCompanion(): Boolean {
        out.line(SeatdAdvice.about)
        out.line(SeatdAdvice.WITHOUT)
        if (!stdin.isTerminal()) return false
        val accepted = confirm.yes("${SeatdAdvice.QUESTION} [y/N] ")
        val choice = if (accepted) SeatdChoice.OPTED_IN else SeatdChoice.DECLINED
        SeatdConsent(env).save(choice)?.let(err::line)
        return accepted
    }

    /** `on` without `--yes`: what it costs said first, then asked on a terminal. With no terminal to ask on, nothing
     *  changes and the person is told to say `--yes`. */
    private fun asked(args: List<String>): Int {
        out.line("splice: $APP_COSTS")
        if (!stdin.isTerminal()) return client.refused(NO_TERMINAL_TO_ASK)
        if (!confirm.yes(DESKTOP_QUESTION)) {
            out.line("splice: nothing was changed.")
            return 1
        }
        return verbs.run(args)
    }
}

// What putting Desktop on splice costs, said before it changes anything (desktop-full, outcome 1).
private const val APP_COSTS = "all of Claude Desktop will run on splice: Chat and Cowork too, not only the Code tab. " +
    "Your conversations are kept on this computer, not in your claude.ai account. Remote Control and Anthropic's " +
    "cloud environments are not offered, and SSH sessions can't reach splice, which listens on this computer only. " +
    "`splice desktop off` puts it back."

private const val DESKTOP_QUESTION = "Put Claude Desktop on splice? [y/N] "

private const val NO_TERMINAL_TO_ASK = "there is no terminal to ask on, so nothing was changed; run " +
    "`splice desktop on --yes` to go on"
