// NEW: Oct 19, 2026 (docs/specs/peer-messaging.md 4.4): the signature a webhook receiver checks.
package splice.sessions.peer.webhook

import java.nio.charset.StandardCharsets
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val PEER_SIGNATURE_ALGORITHM = "HmacSHA256"
private const val PEER_SIGNATURE_SCHEME = "v1"

/**
 * `X-Splice-Signature`. The value is `v1=` and the lowercase hex of HMAC-SHA256 over `<timestamp>.<raw body bytes>`,
 * where the timestamp is the `X-Splice-Timestamp` header (unix seconds) and the key is the text of `webhook.secret`
 * (its 64 hex characters, trimmed), taken as UTF-8 bytes and not decoded from hex. A receiver checks the signature
 * against the exact bytes it was sent and refuses a timestamp more than 300 s from its own clock.
 */
internal object WebhookSignature {

    fun header(secret: String, timestampSeconds: Long, body: ByteArray): String {
        val mac = Mac.getInstance(PEER_SIGNATURE_ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), PEER_SIGNATURE_ALGORITHM))
        mac.update("$timestampSeconds.".toByteArray(StandardCharsets.US_ASCII))
        return "$PEER_SIGNATURE_SCHEME=" + HexFormat.of().formatHex(mac.doFinal(body))
    }
}
