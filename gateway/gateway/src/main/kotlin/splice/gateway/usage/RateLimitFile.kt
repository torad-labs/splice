// PORT-OF: UsageHud.kt (UsageStore) @ d8653a0 — invariants unchanged: the ratelimit lane's disk
// side, moved verbatim onto its own collaborator (HD-24, 2026-08-17). Symmetric with
// [UsageRingFile] — the same in-memory-latest-wins-vs-file seam, applied to the same lane.
package splice.gateway.usage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.Cancellables
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** The ratelimit file: direct-read classification + atomic write. No lock — the caller
 *  ([RateLimitStore]) owns the writeLock it shares with [UsageRingFile]. */
internal class RateLimitFile(
    private val ratelimitFile: Path,
    private val log: LogSink = LogSink(DaemonLog::write),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val unreadableLogged = java.util.concurrent.atomic.AtomicBoolean(false)

    // DR-60 (DR-58's ratelimit sibling, class law): only PROVEN absence — NoSuchFileException
    // with no NOFOLLOW entry — is the quiet no-state null. The old exists() pre-gate read an
    // inaccessible file as absent and blanked the HUD lane silently; present-but-unreadable (or
    // corrupt) now degrades to the SAME null but leaves a trace — ONCE per unreadable episode
    // (this read runs per HUD tick; a healthy read or proven absence re-arms the latch).
    internal fun read(): JsonObject? = Cancellables.runCatchingCancellable {
        json.parseToJsonElement(Files.readString(ratelimitFile)).jsonObject
    }.getOrElse { failure ->
        val genuinelyAbsent = failure is java.nio.file.NoSuchFileException &&
            !Files.exists(ratelimitFile, LinkOption.NOFOLLOW_LINKS)
        if (!genuinelyAbsent && unreadableLogged.compareAndSet(false, true)) {
            log("[usage] $ratelimitFile unreadable ($failure) — ratelimit HUD state treated as absent\n")
        }
        if (genuinelyAbsent) unreadableLogged.set(false)
        null
    }.also { if (it != null) unreadableLogged.set(false) }

    internal fun write(encoded: String) {
        SecureFile.writeAtomic0600(ratelimitFile, encoded)
    }
}
