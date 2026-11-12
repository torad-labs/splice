package splice.head.perf

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** Digests advance with appends; checkpoints do not rescan the entire retained store every tick. */
internal class RequestEvidencePrefixes {
    private val digests = LinkedHashMap<Path, EvidenceDigest>()

    fun clear() { digests.clear() }

    fun appended(path: Path, line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        val end = Files.size(path)
        val prior = digests[path]
        if (prior != null && prior.end == end - bytes.size) {
            prior.digest.update(bytes)
            prior.end = end
            prior.forced = false
        } else {
            digests.remove(path)
        }
    }

    fun capture(paths: List<Path>): List<RequestEvidencePrefix> {
        if (paths.sumOf { Files.size(it) } > MAX_PREFIX_CAPTURE_BYTES) throw IOException("evidence prefix work limit")
        digests.keys.retainAll(paths.toSet())
        return paths.map { path ->
            val end = Files.size(path)
            val state = digests[path]?.takeIf { it.end == end } ?: read(path, end).also { digests[path] = it }
            if (!state.forced) {
                FileChannel.open(path, StandardOpenOption.WRITE).use { it.force(true) }
                state.forced = true
            }
            RequestEvidencePrefix(path.fileName.toString(), end, fingerprint(state))
        }
    }

    private fun read(path: Path, end: Long): EvidenceDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            val buffer = ByteBuffer.allocate(BUFFER_BYTES)
            var offset = 0L
            while (offset < end) {
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), end - offset).toInt())
                val count = channel.read(buffer, offset)
                if (count <= 0) throw IOException("evidence prefix shortened")
                digest.update(buffer.array(), 0, count)
                offset += count
            }
        }
        return EvidenceDigest(digest, end)
    }

    private fun fingerprint(state: EvidenceDigest): String {
        val copy = try {
            state.digest.clone() as? MessageDigest
        } catch (failure: CloneNotSupportedException) {
            throw IOException("evidence digest snapshot unavailable", failure)
        }
        return requireNotNull(copy).digest().joinToString("") { "%02x".format(it) }
    }
}

private data class EvidenceDigest(val digest: MessageDigest, var end: Long, var forced: Boolean = false)

// Hash retained segments through a reusable 64 KiB buffer instead of reading their complete contents into memory.
private const val BUFFER_BYTES = 64 * 1_024

// Limit prefix reconstruction to the 64 MiB retained-evidence contract before scanning the files.
private const val MAX_PREFIX_CAPTURE_BYTES = 64L * 1_024 * 1_024
