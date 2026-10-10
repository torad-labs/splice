// NEW: timestamped quota readings stay scoped to the effective native credential across daemon restarts.
package splice.head.usage

import splice.core.usage.QuotaJson
import splice.core.usage.QuotaSnapshot
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

private val credentialQuotaKey = Regex("[0-9a-f]{64}")

/** One head's credential-scoped quota files. Keys and credential values never enter logs or API payloads. */
public class CredentialQuotaFiles(private val base: Path, private val log: LogSink) : CredentialQuotaListener {
    private val codec = QuotaJson()

    /** A missing observation is unknown; an unreadable one is named without revealing its private join key. */
    public fun read(key: String): QuotaSnapshot? {
        val file = file(key)
        return try {
            codec.decode(Files.readString(file)).also { snapshot ->
                if (snapshot == null) log("[quota] credential observation malformed; standing is unknown\n")
            }
        } catch (_: NoSuchFileException) {
            null
        } catch (failure: java.io.IOException) {
            log("[quota] credential observation unreadable (${failure::class.simpleName}); standing is unknown\n")
            null
        }
    }

    /** Serialize writes from the one live head owner; an older observation cannot replace a newer one. A turn's
     *  header reading names no model weeks, so it keeps the ones the usage probe stored for the same week, as the
     *  head's own tracker does: before Oct 10, 2026 each turn erased Fable's week from the account's reading. */
    @Synchronized
    override fun observed(key: String, snapshot: QuotaSnapshot) {
        if (snapshot.isEmpty) return
        val file = file(key)
        val previous = read(key)
        if (previous != null && previous.updatedAt > snapshot.updatedAt) return
        val kept = snapshot.keepingModelsOf(previous)
        Cancellables.runCatchingCancellable { SecureFile.writeAtomic0600(file, codec.encode(kept)) }
            .onFailure {
                log("[quota] credential observation write failed (${it::class.simpleName}); standing is unknown\n")
            }
    }

    private fun file(key: String): Path {
        require(credentialQuotaKey.matches(key)) { "invalid credential observation identity" }
        return base.resolveSibling("${base.fileName.toString().removeSuffix(".json")}-$key.json")
    }
}
