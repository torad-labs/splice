// NEW: Oct 22, 2026 — the economics backfill's covered edge survives a restart.
//
// WHY. The edge lived only in memory, and the rollup can never reach the window's edge by itself: the backfill drops
// the oldest, partial hour the request records hold, and the records reach back less far than the window (claudex
// 20.6 days of a 35-day window here). So every boot found the gap open and decoded every in-window request record
// again: 1.10 GB / 1.02 M rows for claudex and 1.34 GB / 0.85 M rows for claude-splice, which took live heap from
// 0.7 to 2.0 GB and committed heap to 4.9 GB in the 30 s after the 2:21 PM and 6:01 PM CT boots of Oct 22.
package splice.app.sources

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** A covered edge as it is kept on disk: the window edge a pass closed the gap down to, and the oldest hour the
 *  rollup held once that pass's hours were written into it. */
@Serializable
internal data class BackfillEdge(val from: Long, val reach: Long)

/** The covered edge across restarts. An unreadable or missing file is no edge, so the next read decides again. */
internal class BackfillEdgeFile(private val path: Path) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): BackfillEdge? = try {
        json.decodeFromString(BackfillEdge.serializer(), Files.readString(path))
    } catch (_: IOException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Best effort: an edge that cannot be kept is scanned for again after the next restart, as before. */
    fun save(edge: BackfillEdge) {
        try {
            SecureFile.writeAtomic0600(path, json.encodeToString(BackfillEdge.serializer(), edge))
        } catch (_: IOException) {
            // The edge still stands in memory for this run.
        }
    }

    /** Best effort: an edge that cannot be removed is judged against the rollup again at the next start. */
    fun clear() {
        try {
            Files.deleteIfExists(path)
        } catch (_: IOException) {
            // stillReaches() still refuses it unless the rollup holds the hours it vouches for.
        }
    }
}
