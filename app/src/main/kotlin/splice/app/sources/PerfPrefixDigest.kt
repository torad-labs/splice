// NEW: bounded byte-prefix validation distinguishes append from same-inode perf repairs.
package splice.app.sources

import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

// One fixed 8 KiB binary buffer; hashing validates bytes without a second JSON or aggregate scan.
private const val PREFIX_BUFFER_BYTES = 8 * 1_024

internal class PerfPrefixDigest : OutputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val buffer = ByteBuffer.allocate(PREFIX_BUFFER_BYTES)

    fun start(channel: FileChannel, length: Long) {
        digest.reset()
        var position = 0L
        while (position < length) {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), length - position).toInt())
            val count = channel.read(buffer, position)
            if (count <= 0) throw IOException("perf generation changed during the read")
            buffer.flip()
            digest.update(buffer)
            position += count
        }
    }

    /** Snapshot without ending the prefix, so appended bytes extend the exact bytes already proved. */
    fun fingerprint(): ByteArray {
        val snapshot = digest.clone() as? MessageDigest ?: error("SHA-256 provider cannot snapshot its digest")
        return snapshot.digest()
    }

    override fun write(value: Int) {
        digest.update(value.toByte())
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        digest.update(bytes, offset, length)
    }
}
