// NEW: bounded in-process receipts for bytes appended by the product JSONL writer.
package splice.core.util

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.FileTime

// why: 256 paths cover 128 paired perf/compact sinks with bounded storage; eviction only restores byte hashing.
private const val APPEND_PROOF_PATHS = 256

/** Filesystem identity and stamps; unsupported change-time providers never authorize the fast path. */
public data class JsonlFileVersion(
    public val key: Any?,
    public val created: FileTime,
    public val modified: FileTime,
    public val changed: FileTime?,
    public val size: Long,
    public val regular: Boolean,
)

/** One writer chain's identity: receipts compare it with ===, so each chain is its own token. */
internal class AppendChain

/** One uninterrupted product-writer append chain, not a promise that arbitrary growth is append-only. */
public class JsonlAppendReceipt internal constructor(
    private val first: JsonlFileVersion,
    private val last: JsonlFileVersion,
    private val chain: AppendChain,
) {
    /** Both source snapshots must belong to this exact writer chain. External edits, stamp restoration,
     * rotation and truncation break it and require the reader's original prefix-byte proof. */
    public fun continues(
        before: JsonlFileVersion,
        after: JsonlFileVersion,
        previous: JsonlAppendReceipt?,
    ): Boolean = last == after && (
        first == before || (previous?.chain === chain && previous.last == before)
        )

    internal fun extend(before: JsonlFileVersion, after: JsonlFileVersion): JsonlAppendReceipt? =
        if (last == before) JsonlAppendReceipt(first, after, chain) else null
}

/** An optional read optimization. Losing a receipt always falls back to the existing byte hash. */
public object JsonlAppendProof {
    private val receipts = LinkedHashMap<Path, JsonlAppendReceipt>()

    public fun version(file: Path): JsonlFileVersion {
        val attributes = Files.readAttributes(file, "basic:fileKey,isRegularFile,creationTime,lastModifiedTime,size")
        val changed = if ("unix" in file.fileSystem.supportedFileAttributeViews()) {
            Files.getAttribute(file, "unix:ctime") as? FileTime
        } else {
            null
        }
        return JsonlFileVersion(
            attributes["fileKey"],
            attributes["creationTime"] as? FileTime ?: error("missing creation time"),
            attributes["lastModifiedTime"] as? FileTime ?: error("missing modification time"),
            changed,
            attributes["size"] as? Long ?: error("missing file size"),
            attributes["isRegularFile"] == true,
        )
    }

    internal fun before(file: Path): JsonlFileVersion? = try {
        version(file)
    } catch (_: NoSuchFileException) {
        null
    }

    @Synchronized
    public fun current(file: Path): JsonlAppendReceipt? = receipts[file.toAbsolutePath().normalize()]

    @Synchronized
    internal fun appended(file: Path, before: JsonlFileVersion?, after: JsonlFileVersion) {
        val path = file.toAbsolutePath().normalize()
        val previous = receipts.remove(path)
        if (before == null || !valid(before, after)) return
        receipts[path] = previous?.extend(before, after) ?: JsonlAppendReceipt(before, after, AppendChain())
        if (receipts.size > APPEND_PROOF_PATHS) receipts.remove(receipts.keys.first())
    }

    @Synchronized
    internal fun forget(file: Path) {
        receipts.remove(file.toAbsolutePath().normalize())
    }

    private fun valid(before: JsonlFileVersion, after: JsonlFileVersion): Boolean =
        before.regular && after.regular && before.changed != null && after.changed != null &&
            before.key != null && before.key == after.key && before.size < after.size
}
