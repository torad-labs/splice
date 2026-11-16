// NEW: server mode (docs/specs/server-mode.md, F1 `tls_cert` and `tls_key`). The listener's TLS identity, read from
// the two PEM files an ACME client writes (certbot's fullchain.pem and privkey.pem): the certificate chain, leaf
// first, and its private key as unencrypted PKCS#8. Read once when the listener opens; a renewed certificate is taken
// up at the next `splice restart`.
package splice.server

import splice.core.topology.ServerPem
import splice.core.topology.TLS_KEY_FORM
import java.nio.file.Files
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

internal class PemTls(private val pem: ServerPem) {
    private val pkcs8 = Regex("-----BEGIN PRIVATE KEY-----(.*?)-----END PRIVATE KEY-----", RegexOption.DOT_MATCHES_ALL)

    /** A key store holding the chain and its key under [PEM_ALIAS]. Throws, saying which file and why, for a file
     *  that holds no certificate, a key in another format, or a key that is not the certificate's. */
    fun keyStore(): KeyStore {
        val certificates = CertificateFactory.getInstance("X.509")
        val chain = Files.newInputStream(pem.cert).use(certificates::generateCertificates)
            .filterIsInstance<X509Certificate>()
        require(chain.isNotEmpty()) { "tls_cert holds no certificate" }
        val key = privateKey(Files.readString(pem.key), chain.first().publicKey.algorithm)
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        store.load(null, null)
        store.setKeyEntry(PEM_ALIAS, key, PEM_PASSWORD.toCharArray(), chain.toTypedArray())
        return store
    }

    private fun privateKey(text: String, algorithm: String): PrivateKey {
        val body = pkcs8.find(text)?.groupValues?.get(1)
        requireNotNull(body) { "tls_key $TLS_KEY_FORM" }
        val encoded = PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(body))
        return KeyFactory.getInstance(algorithm).generatePrivate(encoded)
    }
}

/** The one entry of the listener's key store. */
internal const val PEM_ALIAS = "server"

// The key store lives in this process's memory only and is never written, so its password guards nothing: the files
// it was read from are what the operator keeps private.
internal const val PEM_PASSWORD = "in-memory"
