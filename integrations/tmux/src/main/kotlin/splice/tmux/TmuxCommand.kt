// NEW: Oct 10, 2026 — one tmux client call, run to its end with a deadline. The only place in splice that
// starts a tmux process, so how tmux is reached (which binary, which server, how long a call may take) is
// decided once.
package splice.tmux

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** What a tmux client call answered: its exit status and what it printed. */
internal data class TmuxReply(val exit: Int, val out: String) {
    val ok: Boolean get() = exit == 0
}

/**
 * Runs tmux client commands against one server.
 *
 * [socket] names the server: null is the person's own default server, which is the one `tmux attach` reaches
 * from any terminal they open, and a path is a server of its own (what a test uses, so it never touches theirs).
 */
internal class TmuxCommand(
    private val binary: String,
    private val socket: Path?,
    private val deadlineMs: Long,
) {
    /** Run tmux with [args] on this server, against an explicit [server] socket when the pane names one. */
    fun run(args: List<String>, server: String? = null, input: String? = null): TmuxReply {
        val target = server ?: socket?.toString()
        val argv = listOf(binary) + (if (target != null) listOf("-S", target) else emptyList()) + args
        // stderr into stdout: a tmux client prints one short line on failure ("can't find pane: %4"), and that
        // line is the only account of why a call refused. A client's output is bounded by the pane it reads, far
        // inside a pipe's buffer, so reading after it exits cannot wedge it.
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        process.outputStream.use { stdin -> if (input != null) stdin.write(input.toByteArray(Charsets.UTF_8)) }
        if (!process.waitFor(deadlineMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw IOException("tmux ${args.first()} did not answer within $deadlineMs ms")
        }
        val out = process.inputStream.use { it.readAllBytes().toString(Charsets.UTF_8) }
        return TmuxReply(process.exitValue(), out)
    }

    /** The same call, refused out loud when tmux refuses it: a caller that needs it to have happened. */
    fun must(args: List<String>, server: String? = null, input: String? = null): String {
        val reply = run(args, server, input)
        if (!reply.ok) {
            throw IOException("tmux ${args.first()} refused: ${reply.out.trim().lineSequence().firstOrNull()}")
        }
        return reply.out
    }
}
