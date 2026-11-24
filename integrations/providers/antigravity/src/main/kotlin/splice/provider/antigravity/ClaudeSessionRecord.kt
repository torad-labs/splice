// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 3). The record a `claude` keeps of its running
// session, `<config>/sessions/<pid>.json`, kept by the translator in Claude Code's shape, so any tool that lists
// running Claude sessions (and the launch pipe, which reads the session id from it for a live switch) finds the process
// that runs a Gemini session as it finds a Claude one: the pid, the session id, the folder, when the process started
// (and /proc's start time of it, to match against the live process), what started it, and whether a turn is
// running. It is removed when the session ends.
package splice.provider.antigravity

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonWire
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.core.util.WallClock
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** What the record says of the session and its process. */
internal data class SessionFacts(
    val pid: Long,
    val sessionId: String,
    val cwd: String,
    val version: String,
    val entrypoint: String,
    val hostSessionId: String?,
)

/** The record of [facts] under Claude's [config] folder; what could not be written goes to [log]. */
internal class ClaudeSessionRecord(
    config: Path,
    private val facts: SessionFacts,
    private val clock: WallClock,
    private val log: LogSink,
) {
    val file: Path = config.resolve(RECORDS_FOLDER).resolve("${facts.pid}.json")
    private val startedAt = clock()
    private val procStart: String? = procStart()

    /** Writes the record, saying whether a turn is [busy]. */
    fun write(busy: Boolean) {
        val now = clock()
        val record = buildJsonObject {
            put("pid", facts.pid)
            put("sessionId", facts.sessionId)
            put("cwd", facts.cwd)
            put("startedAt", startedAt)
            if (procStart != null) put("procStart", procStart)
            put("version", facts.version)
            put("kind", "interactive")
            put("entrypoint", facts.entrypoint)
            if (facts.hostSessionId != null) put("hostSessionId", facts.hostSessionId)
            put("status", if (busy) "busy" else "idle")
            put("updatedAt", now)
            put("statusUpdatedAt", now)
        }
        try {
            // CREDENTIAL-WRITE-EXEMPT[2026-11-21]: this process's session record, written whole as Claude Code does
            SecureFile.writeAtomic0600(file, JsonWire.string(record))
        } catch (unwritten: IOException) {
            log("[antigravity] $file was not written (${SafeFailureText.render(unwritten)})\n")
        }
    }

    /** Removes the record, as Claude Code does when its session ends. */
    fun remove() {
        try {
            Files.deleteIfExists(file)
        } catch (kept: IOException) {
            log("[antigravity] $file was not removed (${SafeFailureText.render(kept)})\n")
        }
    }

    /** This process's start in clock ticks since boot (/proc/<pid>/stat field 22), as Claude Code writes it on Linux;
     *  null where there is no /proc. */
    private fun procStart(): String? = try {
        Files.readString(Path.of(PROC, facts.pid.toString(), "stat")).substringAfterLast(')').trim().split(' ')
            .getOrNull(START_TIME_AFTER_COMM)
    } catch (_: IOException) {
        null
    }
}

/** Where Claude keeps its running sessions' records, under its config folder. */
private const val RECORDS_FOLDER = "sessions"

private const val PROC = "/proc"

/** Field 22 of /proc/<pid>/stat, counted from the first field after `(comm)`, which is field 3. */
private const val START_TIME_AFTER_COMM = 19
