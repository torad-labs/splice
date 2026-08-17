// PORT-OF: UsageHud.kt (UsageStore) @ d8653a0 — invariants unchanged: the ring's disk lane (read
// on cold-start, atomically rewritten on flush), moved verbatim onto its own collaborator (HD-24,
// 2026-08-17). readEntriesFromDisk and persistSnapshot touch no ring state — that half is UsageRing.
package splice.gateway.usage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path

// MUST comfortably exceed MAX_RING_ENTRIES x ~50 bytes/row — at 2MB the reader treated a
// legitimately capped ring file (~2.25MB) as corrupt and DROPPED the whole live window on
// restart (audit 2026-07-18). 8MB keeps the corrupt-file guard with real headroom.
private const val MAX_USAGE_FILE_BYTES = 8L * 1024 * 1024

/** The usage ring's disk lane. Pure operations on [usageFile]; a mutation of ring state is
 *  [UsageRing]'s concern, not this file's. */
internal class UsageRingFile(
    private val usageFile: Path,
    private val writeLock: Any,
    private val log: LogSink,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    internal var persistedVersion: Long = -1L
        private set

    // best-effort by design: a missing/corrupt usage file reads as empty; cancellation propagates.
    // The file is a JSON array rewritten on every append (not JSONL). Growth is bounded by the
    // 5h window filter + MAX_RING_ENTRIES; oversize files are treated as empty. USG-005: the drop
    // still degrades to empty (never throws), but is now logged via the same sink every other
    // component in this codebase defaults to (DaemonLog::write) — the user's real 5h spend
    // disappearing from the HUD must leave a trace.
    internal fun readEntriesFromDisk(): List<JsonObject> {
        if (!Files.exists(usageFile)) return emptyList()
        val size = Cancellables.runCatchingCancellable { Files.size(usageFile) }.getOrDefault(0L)
        if (size > MAX_USAGE_FILE_BYTES) {
            log("[usage] $usageFile is ${size}B > ${MAX_USAGE_FILE_BYTES}B cap — treating as empty, 5h window reset\n")
            return emptyList()
        }
        return Cancellables.runCatchingCancellable {
            json.parseToJsonElement(Files.readString(usageFile)).jsonArray.mapNotNull { it as? JsonObject }
        }.getOrElse {
            log("[usage] $usageFile unreadable/corrupt (${it.message}) — treating as empty, 5h window reset\n")
            emptyList()
        }
    }

    internal fun persistSnapshot(snapshot: List<JsonObject>, version: Long) {
        synchronized(writeLock) {
            if (version <= persistedVersion) return
            val encoded = buildJsonArray { snapshot.forEach { add(it) } }.toString() + "\n"
            val written = Cancellables
                .runCatchingCancellable { SecureFile.writeAtomic0600(usageFile, encoded) }
                .isSuccess
            if (written) persistedVersion = version
        }
    }
}
