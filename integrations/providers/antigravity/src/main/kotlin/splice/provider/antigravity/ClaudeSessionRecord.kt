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
import java.util.concurrent.atomic.AtomicBoolean

/** Peer inbox facts advertised in `<config>/sessions/<pid>.json` when the in-process UDS inbox is open. */
internal data class SessionPeerFacts(
    val messagingSocketPath: String? = null,
    val pidDomain: String? = null,
    val procStart: String? = null,
    val peerProtocol: Int? = null,
)

/** What the record says of the session and its process. */
internal data class SessionFacts(
    val pid: Long,
    val sessionId: String,
    val cwd: String,
    val version: String,
    val entrypoint: String,
    val hostSessionId: String?,
    val peer: SessionPeerFacts = SessionPeerFacts(),
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
    private val procStart: String? = facts.peer.procStart ?: procStart()
    private val closed = AtomicBoolean(false)

    /** Writes the record, saying whether a turn is [busy]; ignored once [remove] has closed the record. Re-checks
     *  [closed] after the atomic write so a concurrent shutdown hook can never leave a freshly written file behind. */
    fun write(busy: Boolean) {
        if (closed.get()) return
        try {
            // CREDENTIAL-WRITE-EXEMPT[2026-11-21]: this process's session record, written whole as Claude Code does
            SecureFile.writeAtomic0600(file, JsonWire.string(buildRecord(busy)))
        } catch (unwritten: IOException) {
            log("[antigravity] $file was not written (${SafeFailureText.render(unwritten)})\n")
        }
        if (closed.get()) deleteFile()
    }

    private fun buildRecord(busy: Boolean) = buildJsonObject {
        val now = clock()
        put("pid", facts.pid)
        put("sessionId", facts.sessionId)
        put("cwd", facts.cwd)
        put("startedAt", startedAt)
        procStart?.let { put("procStart", it) }
        facts.peer.pidDomain?.let { put("pidDomain", it) }
        put("version", facts.version)
        facts.peer.peerProtocol?.let { put("peerProtocol", it) }
        facts.peer.messagingSocketPath?.let { put("messagingSocketPath", it) }
        put("kind", "interactive")
        put("entrypoint", facts.entrypoint)
        facts.hostSessionId?.let { put("hostSessionId", it) }
        put("status", if (busy) "busy" else "idle")
        put("updatedAt", now)
        put("statusUpdatedAt", now)
    }

    /** Removes the record, as Claude Code does when its session ends, and prevents any later [write] from keeping it. */
    fun remove() {
        closed.set(true)
        deleteFile()
    }

    private fun deleteFile() {
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
