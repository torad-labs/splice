// PORT-OF: UsageHud.kt (UsageStore) @ d8653a0 — invariants unchanged: the ratelimit lane's disk
// side, moved verbatim onto its own collaborator (HD-24, 2026-08-17). Symmetric with
// [UsageRingFile] — the same in-memory-latest-wins-vs-file seam, applied to the same lane.
package splice.gateway.usage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path

/** The ratelimit file: exists-check + parse on read, atomic write. No lock — the caller
 *  ([RateLimitStore]) owns the writeLock it shares with [UsageRingFile]. */
internal class RateLimitFile(private val ratelimitFile: Path) {
    private val json = Json { ignoreUnknownKeys = true }

    internal fun read(): JsonObject? {
        if (!Files.exists(ratelimitFile)) return null
        return json.parseToJsonElement(Files.readString(ratelimitFile)).jsonObject
    }

    internal fun write(encoded: String) {
        SecureFile.writeAtomic0600(ratelimitFile, encoded)
    }
}
