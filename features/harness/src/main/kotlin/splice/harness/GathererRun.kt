// NEW: the Context Getter (docs/specs/harness.md, decisions 6 and 9). One run of the gatherer: a `claude -p` splice
// starts and owns. Its input goes in on stdin, its stream is read line by line (each `tool_use_summary` a progress
// message, the `result` its end), and a cancelled call (the time limit, the client leaving, the head stopping) ends it
// and everything it started. Its own deadline (GathererDeadline) ends it if the daemon cannot.
package splice.harness

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import splice.core.parse.lenientJson
import splice.core.util.JsonScalars
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** What the gatherer is started as: its words, the environment over the daemon's (its [unset] removed first), the
 *  folder it runs in and what it reads on stdin. */
internal data class GathererCommand(
    val argv: List<String>,
    val env: Map<String, String>,
    val unset: List<String>,
    val folder: Path,
    val input: String,
)

internal class GathererRun(private val io: CoroutineDispatcher) {
    private val lines = WorkerLines()

    /** Runs [command] to its end, under a deadline of its own past [limitS] that holds even if the daemon is killed. */
    suspend fun run(command: GathererCommand, limitS: Int, progress: WorkerProgress): WorkerEnd {
        val builder = ProcessBuilder()
            .directory(command.folder.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        val env = builder.environment().apply {
            command.unset.forEach(::remove)
            putAll(command.env)
        }
        // found on the PATH the gatherer itself runs with
        builder.command(GathererDeadline.around(command.argv, limitS, env[PATH]))
        val process = try {
            builder.start()
        } catch (_: IOException) {
            return WorkerEnd.Failed("it could not be started")
        }
        return coroutineScope {
            // The read blocks on the process's own pipe, so it is the process ending that ends it: a cancelled call
            // ends the process here, and the read sees its stream close.
            val reading = async(io) { read(process, command.input, progress) }
            try {
                reading.await()
            } finally {
                end(process)
            }
        }
    }

    private fun read(process: Process, input: String, progress: WorkerProgress): WorkerEnd {
        try {
            process.outputStream.use { it.write(input.toByteArray(StandardCharsets.UTF_8)) }
        } catch (_: IOException) {
            // it stopped reading its input: what it does next is read from its stream and its exit as any other run
        }
        var result: WorkerEnd? = null
        process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { stream ->
            stream.forEach { line -> heard(line, progress)?.let { result = result ?: it } }
        }
        val code = process.waitFor()
        return result ?: if (code != 0) WorkerEnd.Failed("it exited with code $code") else lines.unended()
    }

    /** What one line of the stream says: its end, or null for a progress message or a line that ends nothing. */
    private fun heard(line: String, progress: WorkerProgress): WorkerEnd? {
        val event = JsonScalars.objectOrNull(lenientJson, line.trim()) ?: return null
        return when (JsonScalars.str(event, "type")) {
            SUMMARY -> null.also { JsonScalars.str(event, "summary")?.takeIf(String::isNotBlank)?.let(progress::said) }
            RESULT -> {
                val failed = (event["is_error"] as? JsonPrimitive)?.booleanOrNull == true
                val text = JsonScalars.str(event, "result")
                if (failed || text == null) WorkerEnd.Failed("it ended in an error") else WorkerEnd.Result(text)
            }
            else -> null
        }
    }

    /** Ends [process] and everything it started (the tools and servers a Claude Code session runs): asked first, then
     *  made to, once a short grace has passed. Nothing for a process that already ended. */
    private fun end(process: Process) {
        if (!process.isAlive) return
        // read before the parent goes, which hands its children to another parent
        val started = process.descendants().toList()
        process.destroy()
        started.forEach { it.destroy() }
        if (!process.waitFor(SESSION_STOP_GRACE_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        started.filter { it.isAlive }.forEach { it.destroyForcibly() }
    }
}

/** why: the folders a command is found in. */
private const val PATH = "PATH"

/** why: Claude Code's stream-json event types for a tool-use summary and the run's end (2.1.326). */
private const val SUMMARY = "tool_use_summary"
private const val RESULT = "result"

/** why: a Claude Code session asked to stop flushes and exits well within this; one that does not is killed. */
private const val SESSION_STOP_GRACE_MS = 2_000L
