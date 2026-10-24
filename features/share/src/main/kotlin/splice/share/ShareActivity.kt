// NEW: LAN share, phase 2, milestone 3 (docs/specs/lan-share.md). When each joined machine was last heard from, and
// how many turns it sent today: what `splice share status` and the console's Sharing tab show beside its name. Every
// guarded request counts as seen; only a relayed turn counts as a turn. Kept in memory by the daemon, which answers
// those requests, and written to <state>/share/activity.json at most once a minute and when sharing stops, so a turn
// never waits on the disk for it; a crash loses at most that minute. Separate from clients.json, which a join and a
// revoke change under its lock: this file has one writer, the daemon, and the CLI only reads it.
package splice.share

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.util.Cancellables
import splice.core.util.PathProbe
import splice.core.util.SecureFile
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** One joined machine's activity: [lastSeen] an ISO instant, [turnsToday] counted on [day] (this machine's date). */
@Serializable
public data class ShareSeen(
    public val name: String,
    @SerialName("last_seen") public val lastSeen: String,
    public val day: String,
    @SerialName("turns_today") public val turnsToday: Int,
) {
    /** The turns counted [today]; a count from another day is none. */
    public fun turnsOn(today: String): Int = if (day == today) turnsToday else 0
}

@Serializable
internal data class ShareSeenBook(val clients: List<ShareSeen> = emptyList())

public class ShareActivity(private val file: Path) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val seen: ConcurrentHashMap<String, ShareSeen> by lazy {
        ConcurrentHashMap(read().associateBy { it.name })
    }

    @Volatile
    private var writtenAt = 0L

    /** Every machine's activity as last written: what another process (`splice share status`) reads. */
    public fun read(): List<ShareSeen> {
        val text = PathProbe.text(file) ?: return emptyList()
        return Cancellables.runCatchingCancellable { json.decodeFromString(ShareSeenBook.serializer(), text) }
            .fold(onSuccess = { it.clients }, onFailure = { emptyList() })
    }

    /** Every machine's activity as the daemon holds it now, ahead of the minute's write: what its Sharing page shows. */
    public fun now(): List<ShareSeen> = seen.values.sortedBy { it.name }

    /** [name] was heard from now; [turn] counts it as a relayed turn. Blocks on the disk at most once a minute. */
    internal fun heard(name: String, turn: Boolean) {
        val now = Instant.now()
        val today = today()
        seen.compute(name) { _, was ->
            val turns = (was?.turnsOn(today) ?: 0) + if (turn) 1 else 0
            ShareSeen(name, now.toString(), today, turns)
        }
        if (now.toEpochMilli() - writtenAt >= SHARE_ACTIVITY_WRITE_MS) flush()
    }

    /** [name] keeps no activity: a machine that joins under a revoked one's name starts with none. */
    internal fun forget(name: String) {
        if (seen.remove(name) != null) write()
    }

    /** Writes what is held now, when anything is: the minute's debounce, and the listener's stop. */
    internal fun flush() {
        if (seen.isNotEmpty()) write()
    }

    @Synchronized
    private fun write() {
        writtenAt = System.currentTimeMillis()
        val book = ShareSeenBook(seen.values.sortedBy { it.name })
        SecureFile.writeAtomic0600(file, json.encodeToString(ShareSeenBook.serializer(), book) + "\n")
    }

    public fun today(): String = LocalDate.now(ZoneId.systemDefault()).toString()
}

// why: a minute is as fresh as "last seen" reads to a person, and a busy machine's turns then cost the disk one write
// a minute, not one per turn.
private const val SHARE_ACTIVITY_WRITE_MS = 60_000L
