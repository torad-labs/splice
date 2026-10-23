// NEW: Oct 10, 2026 — one tmux client call, run to its end with a deadline. The only place in splice that
// starts a tmux process, so how tmux is reached (which binary, which server, how long a call may take) is
// decided once.
package splice.tmux

import splice.core.util.SafeFailureText
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

// why: the most one reply is kept. A screen is rows times columns, and even a very large terminal is well under a
// megabyte of it; sixteen leaves room for a pane far past that and keeps a runaway reply from taking the memory.
private const val MAX_REPLY_BYTES = 16 * 1024 * 1024

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
    // One virtual thread per call reads that call's output while the call waits for the client to exit. A virtual
    // thread is a daemon and nothing pools it, so there is nothing to shut down.
    private val readers: ExecutorService =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("tmux-reader-", 0).factory())

    /** Run tmux with [args] on this server, against an explicit [server] socket when the pane names one. */
    fun run(args: List<String>, server: String? = null, input: String? = null): TmuxReply {
        val target = server ?: socket?.toString()
        val argv = listOf(binary) + (if (target != null) listOf("-S", target) else emptyList()) + args
        // stderr into stdout: a tmux client prints one short line on failure ("can't find pane: %4"), and that
        // line is the only account of why a call refused. The output is read while the client runs, not after it
        // exits: a pane bigger than a pipe's buffer (64 KiB on Linux) blocks the client on its own write, and a
        // wait for an exit that cannot come ends in the deadline killing a healthy capture.
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        val output = readers.submit(Callable { kept(process.inputStream) })
        process.outputStream.use { stdin -> if (input != null) stdin.write(input.toByteArray(Charsets.UTF_8)) }
        if (!process.waitFor(deadlineMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw IOException("tmux ${args.first()} did not answer within $deadlineMs ms")
        }
        return TmuxReply(process.exitValue(), drained(output, args.first()).toString(Charsets.UTF_8))
    }

    /** What the [output] reader kept of a [verb] call's reply; a reply past [MAX_REPLY_BYTES] is refused as too long
     *  rather than cut short. */
    private fun drained(output: Future<ByteArray?>, verb: String): ByteArray {
        val read = try {
            output.get()
        } catch (failed: ExecutionException) {
            val reason = SafeFailureText.render(failed.cause ?: failed)
            throw IOException("tmux $verb reply could not be read: $reason", failed)
        }
        return read ?: throw IOException("tmux $verb reply is more than $MAX_REPLY_BYTES bytes")
    }

    /** The same call, refused out loud when tmux refuses it: a caller that needs it to have happened. */
    fun must(args: List<String>, server: String? = null, input: String? = null): String {
        val reply = run(args, server, input)
        if (!reply.ok) {
            throw IOException("tmux ${args.first()} refused: ${reply.out.trim().lineSequence().firstOrNull()}")
        }
        return reply.out
    }

    /** Everything [stream] carries, to its end. Past [MAX_REPLY_BYTES] the rest is read and dropped, so the client
     *  is never left blocked on a full pipe, and null is answered, so the reply is refused rather than cut short. */
    private fun kept(stream: InputStream): ByteArray? = stream.use {
        val head = it.readNBytes(MAX_REPLY_BYTES + 1)
        if (head.size > MAX_REPLY_BYTES) {
            it.transferTo(OutputStream.nullOutputStream())
            null
        } else {
            head
        }
    }
}
