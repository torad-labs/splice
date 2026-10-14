package splice.core.compaction

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
internal class ProjectEvidenceCache<V> {
    internal data class Held<V>(val evidence: ProjectEvidence?, val value: V?, val bytes: Long, val started: Long)
    private val held = LinkedHashMap<Path, Held<V>>(PROJECT_CACHE_INITIAL_CAPACITY, PROJECT_CACHE_LOAD_FACTOR, true)
    private var bytes = 0L

    @Synchronized
    fun lookup(file: Path, evidence: ProjectEvidence?): Held<V>? =
        held[file]?.takeIf { it.evidence == evidence && evidence?.reusable != false }

    /** A slower old read cannot replace a newer observation. I/O and evidence checks happen outside this lock. */
    @Synchronized
    fun remember(file: Path, evidence: ProjectEvidence?, value: V?, size: Long, started: Long) {
        if ((held[file]?.started ?: Long.MIN_VALUE) > started) return
        held.remove(file)?.let { bytes -= it.bytes }
        if (size > MAX_PROJECT_EVIDENCE_BYTES || evidence?.reusable == false) return
        while (held.isNotEmpty() && overBudget(size)) {
            val oldest = held.entries.iterator().next()
            bytes -= oldest.value.bytes
            held.remove(oldest.key)
        }
        held[file] = Held(evidence, value, size, started)
        bytes += size
    }

    private fun overBudget(size: Long): Boolean =
        held.size >= MAX_PROJECT_EVIDENCE_ENTRIES || bytes + size > MAX_PROJECT_EVIDENCE_BYTES
}
