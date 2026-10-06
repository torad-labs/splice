// NEW: trace summary validation certifies current chunk bytes without retaining payloads.
package splice.head.trace.body

import splice.core.memory.HeapJson
import splice.core.memory.HeapOwners
import splice.core.memory.HeapReservations
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID

/** The actual pack decoder, injectable so cost controls count real decompressions. */
internal fun interface TraceChunkDecoder {
    fun decode(format: TracePackFormat, bytes: ByteArray, length: Int): ByteArray?
}

// why: persistent certificates never displace more than four MiB of this reader family's heap share.
private const val TRACE_CERTIFICATE_MAX_BYTES = 4L * 1024 * 1024

// why: a key, digest, map node and eight bounded lexer-state snapshots, conservatively including object headers.
private const val TRACE_CERTIFICATE_STATE_BYTES = 2048L

// why: the prune snapshot's array, path references and missing-path set per retained certificate.
private const val TRACE_CERTIFICATE_PRUNE_BYTES = 256L

// Every existing string-lexer state: before opening, plain, escape, four remaining hex counts, and ended.
private val LITERAL_STATES = listOf("", "\"", "\"\\", "\"\\u", "\"\\u0", "\"\\u00", "\"\\u000", "\"\"")
    .map { prefix -> TraceLiteralScan().also { check(it.feed(prefix.toByteArray())) } }

internal data class TraceChunkKey(
    val file: Path,
    val format: TracePackFormat,
    val generation: UUID,
    val part: TraceChunkReference,
)

/** The compressed seal proves which bytes produced these payload-free lexer transitions. */
internal class TraceChunkCertificate(
    val seal: String,
    private val transitions: List<TraceLiteralScan?>,
    val bytes: Long,
) {
    fun after(before: TraceLiteralScan): TraceLiteralScan? {
        val state = LITERAL_STATES.indexOfFirst(before::sameState)
        check(state >= 0) { "unrepresented trace literal lexer state" }
        return transitions[state]?.copy()
    }
}

/** Current-byte certificates belong to the persistent injected owner, never a decoded-body cache. */
internal class TraceBodyValidation(
    private val heap: HeapReservations,
    private val decoder: TraceChunkDecoder = TraceChunkDecoder(TracePackFormat::decode),
    private val maxBytes: Long = TRACE_CERTIFICATE_MAX_BYTES,
) {
    private val certificates = LinkedHashMap<TraceChunkKey, TraceChunkCertificate>()
    private val lease = HeapOwners.charge(certificates, heap, 0L)
    val retainedBytes: Long get() = synchronized(certificates) { lease.bytes }

    fun certify(
        key: TraceChunkKey,
        header: TracePackEntry,
        stored: ByteArray,
        length: Int = stored.size,
        seal: String = TracePackBytes.hashOf(stored),
    ): TraceChunkCertificate {
        synchronized(certificates) {
            removeWhere { it.file == key.file && it.generation != key.generation }
            certificates[key]?.takeIf { it.seal == seal }?.let { previous ->
                certificates.remove(key)
                certificates[key] = previous
                return previous
            }
        }
        val raw = decoder.decode(key.format, stored.copyOf(length), header.raw)
            ?.takeIf { TracePackBytes.hashOf(it) == header.hash }
        val transitions = LITERAL_STATES.map { state ->
            raw?.let { bytes -> state.copy().takeIf { it.feed(bytes) } }
        }
        val weight = HeapJson.add(
            TRACE_CERTIFICATE_STATE_BYTES,
            HeapJson.add(HeapJson.text(key.part.hash), HeapJson.text(key.file.toString())),
        )
        val certificate = TraceChunkCertificate(seal, transitions, weight)
        synchronized(certificates) {
            removeWhere { it == key || (it.file == key.file && it.generation != key.generation) }
            remember(key, certificate)
        }
        return certificate
    }

    /** Purge and body-only eviction cannot leave a proof, even when no selected row survives. */
    fun prune() {
        val snapshot = synchronized(certificates) {
            val scratch = heap.reserve(certificates.size * TRACE_CERTIFICATE_PRUNE_BYTES) ?: return
            scratch to certificates.keys.map { it.file }
        }
        snapshot.first.use {
            val missing = snapshot.second.filterTo(HashSet()) { !Files.isRegularFile(it, NOFOLLOW_LINKS) }
            synchronized(certificates) { removeWhere { it.file in missing } }
        }
    }

    fun invalidate(file: Path) = synchronized(certificates) { removeWhere { it.file == file } }

    private fun remember(key: TraceChunkKey, certificate: TraceChunkCertificate) {
        if (certificate.bytes > maxBytes) return
        while (lease.bytes + certificate.bytes > maxBytes) evictFirst()
        while (!lease.resize(lease.bytes + certificate.bytes)) {
            if (certificates.isEmpty()) return
            evictFirst()
        }
        certificates[key] = certificate
    }

    private fun evictFirst() {
        val first = certificates.entries.first()
        certificates.remove(first.key)
        check(lease.resize(lease.bytes - first.value.bytes))
    }

    private inline fun removeWhere(predicate: (TraceChunkKey) -> Boolean) {
        val entries = certificates.entries.iterator()
        var freed = 0L
        while (entries.hasNext()) {
            val entry = entries.next()
            if (predicate(entry.key)) {
                freed += entry.value.bytes
                entries.remove()
            }
        }
        if (freed > 0) check(lease.resize(lease.bytes - freed))
    }
}
