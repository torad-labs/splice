// NEW: splice.toml [daemon] alert_command (Nov 12, 2026). The daemon could tell the operator something only through a
// webhook URL saved in the console. A person whose messages go through a program on their own machine, such as a chat
// bridge's send command that holds its own token, had no way to be told. This runs that program.
//
// NO SHELL: the list is the program and its arguments as they stand, so nothing in a message is ever interpreted.
// THE MESSAGE GOES ON STANDARD INPUT, never in the arguments, where a process listing would show it.
// ITS OUTPUT IS DISCARDED and never logged: it is the operator's program, and what it prints may name where the
// message went. A failure is said by what failed, with none of the command in it: an argument may be a secret.
package splice.usage.alerts

import splice.core.util.Cancellables
import java.util.concurrent.TimeUnit

// why: the bound a webhook alert has: one round trip, never longer.
private const val RUN_TIMEOUT_S = 10L

public class AlertCommand(private val argv: List<String>) {
    /** Whether the operator set a command at all. */
    public val set: Boolean get() = argv.isNotEmpty()

    /** A word that changes when the command does, with none of the command readable in it. */
    public val mark: String get() = Integer.toHexString(argv.hashCode())

    /** Runs the command with [text] on its standard input and waits up to [RUN_TIMEOUT_S] seconds for it. Null when
     *  it exited 0, else what happened instead. BLOCKS: the caller runs it off any request or turn path. */
    public fun say(text: String): String? = Cancellables.runCatchingCancellable { run(text) }
        .getOrElse { "${it::class.simpleName ?: "an unnamed failure"} running it" }

    private fun run(text: String): String? {
        val process = ProcessBuilder(argv)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.outputStream.use { it.write(text.toByteArray()) }
        if (!process.waitFor(RUN_TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return "it was still running after $RUN_TIMEOUT_S s and was stopped"
        }
        return process.exitValue().takeIf { it != 0 }?.let { "it exited $it" }
    }
}
