// NEW: door-default (docs/specs/door-default.md, decision 1). `splice claude`: which lane the terminal's `claude` runs
// on, and the two verbs that move it. The daemon holds the lane, the key and the heads, so every verb asks the running
// daemon (DoorClient) and prints its answer.
//
//   splice claude [--json]                    the lane, the picker's rows and the heads left out and why, and on the
//                                             door the door and the model a session starts on
//   splice claude door [--model <selector>]   put `claude` on the door, starting on <selector> when named
//   splice claude head                        put `claude` back on its head, which carries the door's models
//                                             beside Claude's (decision 9)
//
// head-catalog: custom heads are deprecated, and these retire them, bring them back, and check the machine:
//
//   splice claude retire <command|key>... | --all    a head's command and splice's settings into a dated backup
//   splice claude restore <command|key>... | --all   back from the newest backup, exactly as they were
//   splice claude check [--json]                     how Claude Code starts here, and what list each start gets
package splice.app.cli.claude

import splice.app.LifecycleWiring
import splice.app.cli.desktop.DaemonUp
import splice.app.cli.desktop.DoorClient
import splice.app.cli.desktop.DoorVerbs
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader

internal const val CLAUDE_USAGE = "[--json] | door [--model <selector>] | head | retire <command|key>... | " +
    "retire --all | restore <command|key>... | restore --all | check [--json]"

internal class ClaudeCommand(
    env: EnvReader = EnvReader(System::getenv),
    out: TerminalOutput = TerminalOutput { println(it) },
    err: TerminalOutput = TerminalOutput(System.err::println),
    daemon: DaemonUp = DaemonUp(LifecycleWiring::ensureDaemon),
) {
    private val client = DoorClient("claude", env, err, daemon)
    private val verbs = DoorVerbs(
        client,
        out,
        ClaudeSaid(out),
        opens = "door",
        plain = setOf("head"),
        usage = "splice claude $CLAUDE_USAGE",
    )
    private val retire = ClaudeRetire(client, out)
    private val check = ClaudeCheck(client, env, out)

    fun run(args: List<String>): Int = when (args.firstOrNull()) {
        RETIRE, RESTORE -> retire.run(args.first(), args.drop(1))
        CHECK -> check.run(args.drop(1))
        else -> verbs.run(args)
    }
}

private const val RETIRE = "retire"
private const val RESTORE = "restore"
private const val CHECK = "check"
