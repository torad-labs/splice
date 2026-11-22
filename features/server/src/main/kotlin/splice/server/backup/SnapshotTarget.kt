// NEW: server mode (docs/specs/server-mode.md, F14). Durable state snapshots: one bundle holding the server's state
// files (`keys.json`, `people.json`, `budgets.json`, `ledger.jsonl`, `audit.jsonl`) written on a schedule to a folder
// or S3-compatible bucket and restored fail-closed on a fresh disk.
package splice.server.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What reading a snapshot key came to. */
internal sealed class SnapshotRead {
    /** The snapshot existed and was read whole. */
    data class Found(val bytes: ByteArray) : SnapshotRead()

    /** The store answered positively that no snapshot exists yet (fresh deployment). */
    data object Missing : SnapshotRead()

    /** The store could not be reached, refused auth, or returned an error — caller must fail closed on an empty disk. */
    data class Failed(val why: String) : SnapshotRead()
}

/** One store where server-state snapshots are written and read back. */
internal interface SnapshotTarget {
    /** Writes [bytes] at [key], replacing it; null on success, else an actionable error message. */
    suspend fun put(key: String, bytes: ByteArray): String?

    /** Reads [key] back synchronously at startup, distinguishing missing from unreachable so boot fails closed. */
    fun get(key: String): SnapshotRead
}

/** One point-in-time snapshot of `<state>/server` state files. */
@Serializable
internal data class ServerSnapshot(
    val version: Int = BACKUP_SNAPSHOT_VERSION,
    @SerialName("taken_at") val takenAt: String,
    val files: Map<String, String> = emptyMap(),
)

// why: snapshot format version 1 holds a plain UTF-8 map of allowed `<state>/server` filenames to their exact text.
internal const val BACKUP_SNAPSHOT_VERSION: Int = 1

/** Exact allowlist of server state files included in snapshots and permitted during restore (no path traversal). */
internal val snapshotStateFiles: List<String> = listOf(
    "keys.json",
    "people.json",
    "budgets.json",
    "ledger.jsonl",
    "audit.jsonl",
)

internal const val LATEST_SNAPSHOT_KEY: String = "splice-state/latest.json"
