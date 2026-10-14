package splice.core.compaction

import splice.core.memory.HeapCategory
import splice.core.memory.HeapObservation
import splice.core.memory.HeapOwnerState
import splice.core.memory.HeapOwners
import splice.core.memory.HeapRelease
import splice.core.memory.HeapReservations
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private const val MAX_PROJECT_EVIDENCE_ENTRIES = 1_024
private const val MAX_PROJECT_EVIDENCE_BYTES = 1L shl 20
private const val PROJECT_CACHE_INITIAL_CAPACITY = 16
private const val PROJECT_CACHE_LOAD_FACTOR = 0.75f

/** Nanosecond file times and inode/device/change time on Unix. A restored mtime does not hide a rewrite there. */
internal data class ProjectEvidence(val attributes: Map<String, Any?>) {
    val regular: Boolean get() = attributes["isRegularFile"] == true
    val directory: Boolean get() = attributes["isDirectory"] == true
    val reusable: Boolean get() = attributes["ctime"] != null
}

internal object ProjectEvidenceReader {
    fun read(file: Path): ProjectEvidence? = try {
        val unix = "unix" in file.fileSystem.supportedFileAttributeViews()
        val view = if (unix) "unix:dev,ino,ctime," else "basic:"
        ProjectEvidence(
            Files.readAttributes(file, view + "size,lastModifiedTime,creationTime,isRegularFile,isDirectory"),
        )
    } catch (_: IOException) {
        null
    }
}

/** Null values are cached misses. Every reuse requires current filesystem evidence, never a TTL. */
internal class ProjectEvidenceCache<V>(private val heap: HeapReservations? = null) {
    internal data class Held<V>(val evidence: ProjectEvidence?, val value: V?, val bytes: Long, val started: Long) {
        var observation: HeapObservation? = null
    }
    private val held = LinkedHashMap<Path, Held<V>>(PROJECT_CACHE_INITIAL_CAPACITY, PROJECT_CACHE_LOAD_FACTOR, true)
    private var bytes = 0L

    @Synchronized
    fun lookup(file: Path, evidence: ProjectEvidence?): Held<V>? =
        held[file]?.takeIf { it.evidence == evidence && evidence?.reusable != false }

    /** A slower old read cannot replace a newer observation. I/O and evidence checks happen outside this lock. */
    @Synchronized
    fun remember(file: Path, evidence: ProjectEvidence?, value: V?, size: Long, started: Long) {
        // An uncached or stale result can still escape. Only scalar ticket metadata reaches the ledger.
        val escaped = observe(value, size)
        if ((held[file]?.started ?: Long.MIN_VALUE) > started) return
        remove(file, HeapRelease.CLOSED)
        if (size > MAX_PROJECT_EVIDENCE_BYTES || evidence?.reusable == false) return
        while (held.isNotEmpty() && overBudget(size)) {
            remove(held.keys.iterator().next(), HeapRelease.EVICTED)
        }
        val item = Held(evidence, value, size, started)
        item.observation = escaped?.share() ?: heap?.observe(size, HeapCategory.TRANSCRIPT_CACHE)
        item.observation?.let { HeapOwners.keep(item, it) }
        held[file] = item
        bytes += size
    }

    private fun observe(value: V?, size: Long): HeapObservation? {
        val source = heap ?: return null
        // Empty inventories can be JVM singletons; never bind an escaped ticket to an immortal empty list.
        val emptyInventory = value is Collection<*> && value.isEmpty()
        if (value == null || emptyInventory) return null
        return HeapOwners.observe(
            value,
            source,
            size,
            HeapCategory.TRANSCRIPT_CACHE,
            HeapOwnerState.AWAITING_COLLECTION,
        )
    }

    private fun remove(file: Path, reason: HeapRelease) {
        held.remove(file)?.let {
            bytes -= it.bytes
            it.observation?.close(reason)
        }
    }

    private fun overBudget(size: Long): Boolean =
        held.size >= MAX_PROJECT_EVIDENCE_ENTRIES || bytes + size > MAX_PROJECT_EVIDENCE_BYTES
}
