// NEW: server mode (docs/specs/server-mode.md, F5, ledger-export). AWS Signature Version 4 for one S3 request, which
// every S3-compatible store checks: the request's canonical form (method, path, host, the payload's SHA-256 and the
// time) hashed and signed with a key derived from the secret, the day, the region and the service. The secret is used
// to derive that key and goes nowhere else.
package splice.server.export

import splice.core.util.FormEncoding
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class SigV4(private val access: String, private val secret: String, private val region: String) {
    /** The headers that sign a [method] of [payload] to [uri] at [now]: `x-amz-date`, `x-amz-content-sha256` and
     *  `Authorization`. The `Host` header the HTTP client sends itself is signed as it will send it. */
    fun headers(method: String, uri: URI, payload: ByteArray, now: Instant): Map<String, String> {
        val time = STAMP.format(now)
        val day = time.take(DAY_CHARS)
        val payloadHash = hex(sha256(payload))
        val signed = listOf(HOST to host(uri), CONTENT_HASH to payloadHash, DATE to time)
        val names = signed.joinToString(";") { it.first }
        val canonical = listOf(
            method,
            uri.rawPath.ifEmpty { "/" },
            uri.rawQuery.orEmpty(),
            signed.joinToString("") { (name, value) -> "$name:$value\n" },
            names,
            payloadHash,
        ).joinToString("\n")
        val scope = "$day/$region/$S3_SERVICE/$TERMINATOR"
        val toSign = listOf(SIGV4_ALGORITHM, time, scope, hex(sha256(canonical.toByteArray()))).joinToString("\n")
        val key = listOf(day, region, S3_SERVICE, TERMINATOR).fold("AWS4$secret".toByteArray(), ::hmac)
        val signature = hex(hmac(key, toSign))
        return mapOf(
            DATE to time,
            CONTENT_HASH to payloadHash,
            "Authorization" to "$SIGV4_ALGORITHM Credential=$access/$scope, SignedHeaders=$names, Signature=$signature",
        )
    }

    /** The Host header as the client sends it: the port only when it is not the scheme's own. */
    private fun host(uri: URI): String =
        if (uri.port == -1 || uri.port == uri.toURL().defaultPort) uri.host else "${uri.host}:${uri.port}"

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hmac(key: ByteArray, text: String): ByteArray =
        Mac.getInstance(MAC).apply { init(SecretKeySpec(key, MAC)) }.doFinal(text.toByteArray())

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** How a key is spelled in a request's path, as SigV4 signs it: each segment escaped but for the unreserved. */
    object Paths {
        fun of(key: String): String = key.split('/').joinToString("/", transform = FormEncoding::percentEncode)
    }
}

private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

// why: a SigV4 timestamp is `yyyyMMdd'T'HHmmss'Z'`, and its scope names the day, its first eight characters.
private const val DAY_CHARS = 8
private const val SIGV4_ALGORITHM = "AWS4-HMAC-SHA256"
private const val S3_SERVICE = "s3"
private const val TERMINATOR = "aws4_request"
private const val MAC = "HmacSHA256"
private const val HOST = "host"
private const val CONTENT_HASH = "x-amz-content-sha256"
private const val DATE = "x-amz-date"
