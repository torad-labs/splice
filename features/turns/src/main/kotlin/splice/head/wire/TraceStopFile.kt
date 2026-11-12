// NEW: #518. When a head's trace stopped saving is kept across a restart. TraceSwitch held the first refusal's time in
// memory only, so a restart forgot it and Settings showed the first refusal since that start: on Nov 11, 2026 the card
// read "Saving stopped 6:37 PM" a minute after a restart at 6:37 PM, while nothing had been saved since 6:00 PM.
package splice.head.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path

/** One head's [TraceStop] between restarts: a small owner-only file beside the head's day files, holding a time and
 *  the store's own fixed reason. Best effort, as every trace write is: a file that will not read says nothing, and
 *  one that cannot be written leaves the time in memory until the restart. */
public class TraceStopFile(private val path: Path, private val log: LogSink = LogSink(DaemonLog::write)) {

    public fun load(): TraceStop? {
        if (!Files.exists(path)) return null
        return Cancellables.runCatchingCancellable {
            val root = Json.parseToJsonElement(Files.readString(path)).jsonObject
            val at = JsonScalars.long(root, STOPPED_AT)
            JsonScalars.str(root, STOP_REASON)?.let { reason -> at?.let { TraceStop(it, reason) } }
        }.onFailure {
            log("[trace] when saving stopped did not read (${SafeFailureText.render(it)}); the next refusal sets it\n")
        }.getOrNull()
    }

    /** Keeps [stop], or removes the file once a request saved whole again. */
    public fun save(stop: TraceStop?) {
        Cancellables.runCatchingCancellable {
            if (stop == null) {
                Files.deleteIfExists(path)
            } else {
                val _ = SecureFile.ownerOnlyDirectory(path.toAbsolutePath().parent)
                SecureFile.writeAtomic0600(path, json(stop))
            }
        }.onFailure {
            log(
                "[trace] when saving stopped could not be kept (${SafeFailureText.render(it)}); " +
                    "a restart forgets it\n",
            )
        }
    }

    private fun json(stop: TraceStop): String = JsonWire.string(
        buildJsonObject {
            put(STOPPED_AT, stop.atEpochMs)
            put(STOP_REASON, stop.reason)
        },
    )
}

private const val STOPPED_AT = "at_epoch_ms"
private const val STOP_REASON = "reason"
