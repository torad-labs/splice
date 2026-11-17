// NEW: door-default (docs/specs/door-default.md, decision 1). `splice claude`: which lane the terminal's `claude` runs
// on, and the two verbs that move it. The daemon holds the lane, the key and the heads, so every verb asks the running
// daemon (DoorClient) and prints its answer.
//
//   splice claude [--json]                    the lane, and on the door the door, the model a session starts on, the
//                                             picker's rows and the heads left out and why
//   splice claude door [--model <selector>]   put `claude` on the door, starting on <selector> when named
//   splice claude head                        put `claude` back on its head, which is deprecated
package splice.app.cli.claude

import splice.app.LifecycleWiring
import splice.app.cli.desktop.DaemonUp
import splice.app.cli.desktop.DoorClient
import splice.app.cli.desktop.DoorVerbs
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader

internal const val CLAUDE_USAGE = "[--json] | door [--model <selector>] | head"

internal class ClaudeCommand(
    env: EnvReader = EnvReader(System::getenv),
    out: TerminalOutput = TerminalOutput { println(it) },
    err: TerminalOutput = TerminalOutput(System.err::println),
    daemon: DaemonUp = DaemonUp(LifecycleWiring::ensureDaemon),
) {
    private val verbs = DoorVerbs(
        DoorClient("claude", env, err, daemon),
        out,
        ClaudeSaid(out),
        opens = "door",
        plain = setOf("head"),
        usage = "splice claude $CLAUDE_USAGE",
    )

    fun run(args: List<String>): Int = verbs.run(args)
}
