// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 5). One program a Code tab session runs on
// while the pipe holds it: Claude Code, or `splice desktop acp` for an Antigravity model. The pipe writes Desktop's
// lines to its standard input; its standard output and error are Desktop's own, given to it unread. Like a `claude`,
// it keeps `<config>/sessions/<pid>.json` while it runs, which says the session id a switch hands to the next program.
package splice.app.desktop.pipe

import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import splice.core.util.JsonScalars
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** The running [process], whose session record is kept under [sessions]. */
internal class PipeLeg(private val process: Process, private val sessions: Path) {
    private val input = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)

    /** Writes Desktop's [line] to the program; false once the program no longer reads. */
    fun send(line: String): Boolean = try {
        input.write(line)
        input.write(LINE_END)
        input.flush()
        true
    } catch (_: IOException) {
        false
    }

    /** Closes the program's standard input, which ends a stream-json session once its turn is done. */
    fun endInput() {
        try {
            input.close()
        } catch (_: IOException) {
            // Already gone: nothing is left to tell it.
        }
    }

    /** Waits for the program to end; its exit code. */
    suspend fun ended(): Int = process.onExit().await().exitValue()

    /** Ends the program for a switch: its input closed, then stopped when it is still running after [graceMs], then
     *  killed after as long again; its exit code. */
    suspend fun stop(graceMs: Long): Int {
        endInput()
        val finished = withTimeoutOrNull(graceMs) { ended() }
        if (finished != null) return finished
        process.destroy()
        val stopped = withTimeoutOrNull(graceMs) { ended() }
        if (stopped != null) return stopped
        process.destroyForcibly()
        return ended()
    }

    /** Kills the program, for a pipe that is itself being stopped. */
    fun destroy() {
        process.toHandle().descendants().forEach(ProcessHandle::destroy)
        process.destroy()
    }

    /** The session id the program's record says, read from its own process or one it started (a wrapper's
     *  `claude`); null while there is no record. */
    fun sessionId(): String? {
        val pids = listOf(process.pid()) + process.toHandle().descendants().map(ProcessHandle::pid).toList()
        return pids.firstNotNullOfOrNull { pid -> recorded(sessions.resolve("$pid.json")) }
    }

    private fun recorded(file: Path): String? {
        if (!Files.isRegularFile(file)) return null
        val text = try {
            Files.readString(file)
        } catch (_: IOException) {
            return null
        }
        return JsonScalars.str(JsonScalars.objectOrNull(Json, text), SESSION_ID_KEY)?.takeIf(String::isNotBlank)
    }
}

private const val LINE_END = "\n"

/** The field of Claude Code's session record that names the session. */
private const val SESSION_ID_KEY = "sessionId"
