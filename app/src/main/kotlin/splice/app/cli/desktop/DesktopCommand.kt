// NEW: desktop-door (docs/specs/desktop-door.md, decision 4). `splice desktop`: open Claude Desktop's door to splice,
// or close it, and say what is so. The daemon holds the door, the key and the heads, so every verb asks the running
// daemon (DoorClient) and prints its answer: what changed, which model a session starts on and why, what to set in
// Desktop's own environment store, or what made it refuse.
//
//   splice desktop [--json]                 whether Desktop is on, the door, the default model, the lineup, the heads
//                                           left out and why, and the last turn through the door
//   splice desktop on [--model <selector>]  open the door, and say what to set once in Desktop's environment store
//   splice desktop off                      close the door, and say what to remove there
//   splice desktop rotate-key               replace the desktop key, and say the new one while Desktop is on
package splice.app.cli.desktop

import splice.app.LifecycleWiring
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader

internal const val DESKTOP_USAGE = "[--json] | on [--model <selector>] | off | rotate-key"

internal class DesktopCommand(
    env: EnvReader = EnvReader(System::getenv),
    out: TerminalOutput = TerminalOutput { println(it) },
    err: TerminalOutput = TerminalOutput(System.err::println),
    daemon: DaemonUp = DaemonUp(LifecycleWiring::ensureDaemon),
) {
    private val verbs = DoorVerbs(
        DoorClient("desktop", env, err, daemon),
        out,
        DesktopSaid(out),
        opens = "on",
        plain = setOf("off", "rotate-key"),
        usage = "splice desktop $DESKTOP_USAGE",
    )

    fun run(args: List<String>): Int = verbs.run(args)
}
