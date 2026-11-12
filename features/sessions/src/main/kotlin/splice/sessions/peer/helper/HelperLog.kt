// NEW: Oct 27, 2026: helper.log (docs/specs/peer-messaging.md 2), moved out of PeerHelper.kt unchanged when the codex
// peer joined the helper. It carries counts and errors, never text, tokens or keys, and is made 0600, as the spec's
// on-disk layout has every file but the registry record.
package splice.sessions.peer.helper

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.Cancellables
import splice.core.util.JsonWire
import splice.core.util.JsonlSink
import splice.core.util.LogSafe
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.core.util.WallClock
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

// why: helper.log holds counts and errors only; a megabyte is months of them, and one rolled generation is kept.
private const val HELPER_LOG_MAX_BYTES = 1L shl 20

/** helper.log: counts of what the helper saw and the errors it met, never a message's text, a token or a key. */
public class HelperLog(private val file: Path, private val clock: WallClock) {
    private val counts = ConcurrentHashMap<String, Long>()

    @Volatile
    private var written: Map<String, Long> = emptyMap()

    public fun count(event: String) {
        counts.merge(event, 1L, Long::plus)
    }

    public fun error(what: String, failure: Throwable) {
        note(what, SafeFailureText.render(failure))
    }

    public fun note(what: String, detail: String? = null) {
        val line = buildJsonObject {
            put("at", clock())
            put("event", LogSafe.str(what))
            detail?.let { put("detail", LogSafe.str(it)) }
        }
        append(JsonWire.string(line))
    }

    /** Writes the running counts, when they moved since the last time. */
    public fun flush() {
        val now = counts.toMap()
        if (now == written) return
        written = now
        val line = buildJsonObject {
            put("at", clock())
            put("event", "counts")
            put("counts", buildJsonObject { now.toSortedMap().forEach { (event, n) -> put(event, n) } })
        }
        append(JsonWire.string(line))
    }

    private fun append(line: String) {
        Cancellables.discard(
            Cancellables.runCatchingCancellable {
                fresh()
                JsonlSink.appendLine(file, line, HELPER_LOG_MAX_BYTES)
            },
            "helper.log is diagnostics; a line it could not take must not stop the inbox",
        )
    }

    /** Makes helper.log owner-only before JsonlSink appends, which would make it, and remake it after each roll, at the
     *  umask's mode. */
    private fun fresh() {
        if (Files.exists(file)) return
        try {
            SecureFile.createNew0600(file, ByteArray(0))
        } catch (_: FileAlreadyExistsException) {
            // Made by another append between the check and the create: it is the file wanted.
        }
    }
}
