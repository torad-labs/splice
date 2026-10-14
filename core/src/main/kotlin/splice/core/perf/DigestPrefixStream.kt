package splice.core.perf

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest

// why: OutputStream values are unsigned bytes even when the backing Kotlin Byte sign-extends its high bit.
private const val DIGEST_UNSIGNED_BYTE_MASK = 0xff

/** Matches rendered bytes against an old prefix while retaining only bounded evidence for the next checkpoint.
 * The last byte is held back: an array checkpoint must precede ']', so an append can supply its comma and suffix. */
internal class DigestPrefixStream(
    private val previous: ByteArray?,
    private val resumed: MessageDigest?,
    private val limit: Int,
) : OutputStream() {
    private var digest: MessageDigest? = if (previous == null) fresh() else null
    private var matched = 0
    private var tail: Int? = null
    private var evidence: ByteArrayOutputStream? = if (limit > 0) ByteArrayOutputStream() else null

    override fun write(value: Int) {
        val bytes = byteArrayOf(value.toByte())
        write(bytes, 0, bytes.size)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        tail?.let { accept(byteArrayOf(it.toByte()), 0, 1) }
        accept(bytes, offset, length - 1)
        tail = bytes[offset + length - 1].toInt() and DIGEST_UNSIGNED_BYTE_MASK
    }

    private fun accept(bytes: ByteArray, offset: Int, length: Int) {
        capture(bytes, offset, length)
        val end = offset + length
        val start = if (digest == null) match(bytes, offset, end) else offset
        if (start < end) unfinished().update(bytes, start, end - start)
    }

    private fun capture(bytes: ByteArray, offset: Int, length: Int) {
        val held = evidence ?: return
        if (length <= limit - held.size()) held.write(bytes, offset, length) else evidence = null
    }

    private fun match(bytes: ByteArray, offset: Int, end: Int): Int {
        val prior = previous ?: return offset
        var start = offset
        while (start < end && matched < prior.size) {
            if (bytes[start] != prior[matched]) break
            start++
            matched++
        }
        if (matched == prior.size) {
            digest = resumed
        } else if (start < end) {
            digest = fresh().also { it.update(prior, 0, matched) }
        }
        return start
    }

    /** The returned digest is private to this call and has never received the final JSON delimiter. */
    fun unfinished(): MessageDigest = digest ?: fresh().also {
        previous?.let { bytes -> it.update(bytes, 0, matched) }
        digest = it
    }

    fun prefixBytes(): ByteArray? = evidence?.toByteArray()

    fun finalByte(): Int? = tail

    private fun fresh(): MessageDigest = MessageDigest.getInstance("SHA-256")
}
