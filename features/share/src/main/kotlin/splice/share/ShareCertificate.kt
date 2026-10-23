// NEW: LAN share, phase 2, milestone 2 (docs/specs/lan-share.md). The certificate the share listener serves: an EC
// P-256 key and a self-signed certificate, minted once, on the first start with `[share] enabled`, and kept in
// <state>/share/tls.pem (0600: the key and the certificate as PEM, so `openssl x509 -in tls.pem -noout -fingerprint
// -sha256` shows what a client pins). A client pins its SHA-256 when it joins (trust on first contact, the LAN trust
// Marcos chose on 2026-10-22), so the certificate never changes on its own: a file that cannot be read stops sharing,
// said in the log, rather than minting a new one every joined machine would then refuse.
package splice.share

import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.extensions.HashAlgorithm
import io.ktor.network.tls.extensions.SignatureAlgorithm
import splice.core.util.Cancellables
import splice.core.util.PathProbe
import splice.core.util.SecureFile
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.security.auth.x500.X500Principal

/** What the listener serves: its key and certificate in [keyStore] under [SHARE_TLS_ALIAS], opened with [password],
 *  and the [fingerprint] a client pins. */
internal data class ShareTls(val keyStore: KeyStore, val password: CharArray, val fingerprint: String) {
    // The password opens the key; a log of this record names the certificate only.
    override fun toString(): String = "ShareTls(fingerprint=$fingerprint)"
}

public class ShareCertificate(private val file: Path) {

    /** The certificate's fingerprint, or null when none is minted yet or the file holds none: `splice share status`
     *  shows it, so a person can compare it with what a client printed when it joined. */
    public fun fingerprint(): String? = PathProbe.text(file)?.let { text ->
        Cancellables.runCatchingCancellable { ShareFingerprint.of(certificate(text)) }
            .fold(onSuccess = { it }, onFailure = { null })
    }

    /** The listener's key store, minting the certificate for [names] when the file is absent. Throws, naming the
     *  file, when it holds no usable key and certificate. */
    internal fun load(names: List<String>): ShareTls {
        val text = PathProbe.text(file) ?: minted(names)
        val password = randomPassword()
        val cert = certificate(text)
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(SHARE_TLS_ALIAS, key(text), password, arrayOf(cert))
        }
        return ShareTls(store, password, ShareFingerprint.of(cert))
    }

    /** A new key and certificate, written only when nothing is there yet: a daemon that lost the race reads the one
     *  the other wrote. */
    private fun minted(names: List<String>): String {
        val password = randomPassword()
        val built = buildKeyStore {
            certificate(SHARE_TLS_ALIAS) {
                this.password = String(password)
                hash = HashAlgorithm.SHA256
                sign = SignatureAlgorithm.ECDSA
                keySizeInBits = SHARE_TLS_KEY_BITS
                daysValid = SHARE_TLS_DAYS
                subject = X500Principal("CN=${names.firstOrNull() ?: "splice"}, O=splice share")
                domains = names
                ipAddresses = emptyList()
            }
        }
        val key = built.getKey(SHARE_TLS_ALIAS, password).encoded
        val pem = pem("PRIVATE KEY", key) + pem("CERTIFICATE", built.getCertificate(SHARE_TLS_ALIAS).encoded)
        // A first start: the share folder may not exist yet, and the key file holds its own mode.
        Files.createDirectories(file.toAbsolutePath().parent)
        try {
            SecureFile.createNew0600(file, pem.toByteArray(Charsets.US_ASCII))
        } catch (_: FileAlreadyExistsException) {
            return PathProbe.text(file) ?: pem
        }
        return pem
    }

    private fun certificate(text: String): Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(block(text, "CERTIFICATE").inputStream())

    private fun key(text: String): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(block(text, "PRIVATE KEY")))

    /** The DER bytes of [text]'s PEM block of [type]. */
    private fun block(text: String, type: String): ByteArray {
        val body = text.substringAfter("-----BEGIN $type-----", "").substringBefore("-----END $type-----", "")
        require(body.isNotBlank()) { "$file holds no $type" }
        return Base64.getMimeDecoder().decode(body)
    }

    private fun pem(type: String, der: ByteArray): String =
        "-----BEGIN $type-----\n" + Base64.getMimeEncoder(PEM_LINE, "\n".toByteArray()).encodeToString(der) +
            "\n-----END $type-----\n"

    /** The in-memory key store's password: the key on disk is protected by the file's mode, so this only opens the
     *  store this process builds, and is new on every start. */
    private fun randomPassword(): CharArray {
        val bytes = ByteArray(PASSWORD_BYTES).also(SecureRandom()::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toCharArray()
    }
}

/** A certificate as both ends name it: `sha256:` and the lowercase hex SHA-256 of its DER bytes, the shape
 *  `[remotes.<name>] fingerprint` holds. */
internal object ShareFingerprint {
    fun of(certificate: Certificate): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") {
            "%02x".format(it)
        }
}

internal const val SHARE_TLS_ALIAS: String = "splice-share"

// why: P-256, the curve every TLS 1.3 stack offers; an EC key of that size is the strength of RSA-3072.
private const val SHARE_TLS_KEY_BITS = 256

// why: ten years: clients pin the certificate and never check its dates, so expiry would only break a pin that holds,
// and a certificate that changes makes every joined machine add this one again.
private const val SHARE_TLS_DAYS = 3_650L

// why: PEM's own line length (RFC 7468).
private const val PEM_LINE = 64

// why: 256 random bits for a password nothing outside this process ever sees.
private const val PASSWORD_BYTES = 32
