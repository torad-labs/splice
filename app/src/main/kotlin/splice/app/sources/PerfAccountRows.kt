// NEW: account-only generation reads reuse the perf framer and exact product append proofs.
package splice.app.sources

import splice.core.util.JsonlAppendProof
import splice.core.util.JsonlAppendReceipt
import splice.core.util.JsonlFileVersion
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest

// why: covers a live cursor's version, receipt and digest state plus worst-case path storage.
private const val GENERATION_BYTES = 1024L

// why: paths retain byte/string forms and worst-case UTF-16 backing plus filesystem metadata.
private const val PATH_CHAR_BYTES = 8L

// why: exact suffix validation uses one fixed buffer even when the filesystem has no change-time stamp.
private const val VALIDATION_BUFFER_BYTES = 8 * 1024

/** Cursors own no row facts. A clipped seed never hashes or materializes the excluded prefix. */
internal class PerfAccountRows(private val scanBytes: Long) {
    data class Generation(
        val path: Path,
        val version: JsonlFileVersion?,
        val receipt: JsonlAppendReceipt? = null,
        val complete: Long = 0L,
        val digest: ByteArray? = null,
        val prefix: PerfPrefixState? = null,
        val proof: PerfSuffixProof = PerfSuffixProof(),
    ) {
        val bytes: Long get() = GENERATION_BYTES + path.toString().length * PATH_CHAR_BYTES
    }

    private val prefix = PerfPrefixDigest()

    fun appendable(before: Generation, after: Generation): Boolean = when {
        before.path != after.path -> false
        before.version == null || after.version == null -> before.version == after.version
        before.version == after.version -> unchanged(before, after)
        else -> continuing(before, after)
    }

    private fun unchanged(before: Generation, after: Generation): Boolean {
        if (after.version?.changed != null) return true
        val expected = before.proof.suffix ?: return false
        return FileChannel.open(after.path, READ).use { channel ->
            fingerprint(channel, before.proof.start, requireNotNull(after.version).size).contentEquals(expected)
        }
    }

    private fun continuing(before: Generation, after: Generation): Boolean {
        val previous = requireNotNull(before.version)
        val next = requireNotNull(after.version)
        val receipt = JsonlAppendProof.current(after.path)
        return when {
            previous.key != next.key || previous.size != before.complete -> false
            next.size - before.proof.start > scanBytes -> false
            receipt?.continues(previous, next, before.receipt) == true -> true
            before.digest == null -> false
            else -> FileChannel.open(after.path, READ).use { channel ->
                prefix.matches(channel, next.size, before.complete, before.digest)
            }
        }
    }

    fun scan(after: Generation, before: Generation?, start: Long, decode: PerfLineDecode): Generation {
        val version = after.version ?: return after
        val wholePrefix = wholePrefix(before, start)
        val end = FileChannel.open(after.path, READ).use { channel ->
            if (before?.prefix == null) prefix.start(channel, 0L) else prefix.resume(before.prefix)
            val reader = PerfLineReader(channel, start, version.size)
            if (skipPartial(before, start, channel)) reader.next()
            scanLines(reader, start, decode)
        }
        val proofStart = proofStart(before, start)
        val suffix = FileChannel.open(after.path, READ).use { channel ->
            fingerprint(channel, proofStart, version.size)
        }
        if (version(after.path) != version) throw IOException("perf generation changed during account indexing")
        return after.copy(
            receipt = JsonlAppendProof.current(after.path),
            complete = end,
            digest = if (wholePrefix) prefix.fingerprint() else null,
            prefix = if (wholePrefix) prefix.save() else null,
            proof = PerfSuffixProof(start = proofStart, suffix = suffix),
        )
    }

    private fun proofStart(before: Generation?, start: Long): Long = before?.proof?.start ?: start

    private fun wholePrefix(before: Generation?, start: Long): Boolean = start == 0L || before?.prefix != null

    private fun skipPartial(before: Generation?, start: Long, channel: FileChannel): Boolean =
        start > 0L && before == null && !lineStart(channel, start)

    private fun lineStart(channel: FileChannel, start: Long): Boolean {
        val previous = ByteBuffer.allocate(1)
        if (channel.read(previous, start - 1) != 1) throw IOException("perf generation changed")
        return previous[0] == '\n'.code.toByte() || previous[0] == '\r'.code.toByte()
    }

    private fun scanLines(reader: PerfLineReader, start: Long, decode: PerfLineDecode): Long {
        var end = start
        while (true) {
            val raw = reader.next() ?: break
            decode.decode(raw)
            if (reader.terminated) {
                reader.hashTo(prefix)
                end = reader.position
            }
        }
        return end
    }

    private fun fingerprint(channel: FileChannel, start: Long, end: Long): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(VALIDATION_BUFFER_BYTES)
        var at = start
        while (at < end) {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), end - at).toInt())
            val count = channel.read(buffer, at)
            if (count <= 0) throw IOException("perf account span changed")
            buffer.flip()
            digest.update(buffer)
            at += count
        }
        return digest.digest()
    }

    fun fingerprint(archives: List<Path>): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        archives.forEach { path ->
            digest.update(path.toString().toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(version(path).toString().toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        return digest.digest()
    }

    fun version(path: Path): JsonlFileVersion? = try {
        JsonlAppendProof.version(path).also { if (!it.regular) throw IOException("not a regular perf generation") }
    } catch (_: NoSuchFileException) {
        null
    }
}

/** The span of a generation that account indexing proved by hash, from [start] to the end of the file it read. */
internal data class PerfSuffixProof(
    val start: Long = 0L,
    val suffix: ByteArray? = null,
)
