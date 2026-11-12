// NEW: generation-owned reply addresses recognize disposed callers without retaining their awaiters.
package splice.codemode

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.US_ASCII
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class HostReplyAddresses {
    private val mac = Mac.getInstance("HmacSHA256").also { signing ->
        // The generation secret has the selected MAC's full output width.
        signing.init(SecretKeySpec(ByteArray(signing.macLength).also(SecureRandom()::nextBytes), signing.algorithm))
    }
    private val hex = HexFormat.of()

    @Synchronized
    fun key(cell: Long, request: Long): String =
        hex.formatHex(mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES * 2).putLong(cell).putLong(request).array()))

    fun matches(reply: HostFrame): Boolean {
        val supplied = reply.replyKey ?: return false
        if (supplied.length != mac.macLength * 2) return false
        return MessageDigest.isEqual(
            key(reply.cell, reply.request).toByteArray(US_ASCII),
            supplied.toByteArray(US_ASCII),
        )
    }
}
