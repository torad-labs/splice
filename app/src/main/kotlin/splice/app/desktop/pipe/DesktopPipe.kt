// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 5). The pipe `splice desktop launch` holds a
// stream-json Code tab session in, so a live model switch can change the program under it (PipeSession). Desktop
// changes a running session's model with a `set_model` on the standard input of the process it started, and starts
// no new one, so the pipe reads Desktop's lines itself and passes each on. The program's standard output is Desktop's
// own, never read here, so the pipe adds nothing to what Desktop sees.
package splice.app.desktop.pipe

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import splice.core.terminal.TerminalOutput
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Runs sessions on [legs], waiting on them on [io]; what goes wrong goes to [err]. A program a switch ends is given
 *  [graceMs] to finish before it is stopped. */
internal class DesktopPipe(
    private val legs: PipeLegs,
    private val io: CoroutineDispatcher,
    private val err: TerminalOutput,
    private val graceMs: Long = SWITCH_GRACE_MS,
) {
    /** Runs the session [first] starts, Desktop's lines read from [input], until the program it runs on ends; that
     *  program's exit code, or a shell's 127 when a program could not be started. */
    suspend fun run(first: ClaudeLine, input: InputStream): Int = coroutineScope {
        val heard = Channel<Heard>(Channel.UNLIMITED)
        val session = PipeSession(legs, io, graceMs, err, heard, this)
        if (!session.open(first)) return@coroutineScope NOT_STARTED_EXIT
        // Desktop stopping the wrapper stops the session's program, rather than leaving it running alone.
        val stop = Executors.defaultThreadFactory().newThread(session::destroy)
        Runtime.getRuntime().addShutdownHook(stop)
        val reader = read(input, heard)
        try {
            session.serve() ?: NOT_STARTED_EXIT
        } finally {
            reader.shutdownNow()
            Runtime.getRuntime().removeShutdownHook(stop)
        }
    }

    /** Reads Desktop's blocking [input] on a daemon thread of its own, so a session that ends while Desktop keeps the
     *  pipe open returns at once. */
    private fun read(input: InputStream, heard: SendChannel<Heard>): ExecutorService {
        val pool = Executors.newSingleThreadExecutor { task ->
            Executors.defaultThreadFactory().newThread(task).apply {
                name = "splice-desktop-pipe-stdin"
                isDaemon = true
            }
        }
        pool.execute {
            try {
                input.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                    lines.forEach { heard.trySend(Heard.Line(it)) }
                }
            } catch (_: IOException) {
                // Desktop's pipe closed under the read: the same as its end.
            } finally {
                heard.trySend(Heard.InputEnded)
            }
        }
        return pool
    }
}

/** What the pipe heard next: a line of Desktop's, the end of Desktop's input, or a program that ended. */
internal sealed class Heard {
    class Line(val text: String) : Heard()

    data object InputEnded : Heard()

    class Ended(val leg: PipeLeg, val code: Int) : Heard()
}

/** How long a program a switch ends has to finish its turn and go, before it is stopped. */
private const val SWITCH_GRACE_MS = 10_000L

/** A shell's exit for a command it could not run. */
private const val NOT_STARTED_EXIT = 127
