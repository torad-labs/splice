// NEW: custom-provider (issue #680, cp-tls). A provider reached through a company's TLS-inspecting proxy presents a
// certificate its company's own root signed, which the roots Java ships with do not know, so every turn failed with a
// bare PKIX error. splice's upstream client now trusts the roots Java ships with, the operating system's own trust
// store read at start, and, for one provider, the PEM bundle its `ca_file` names. Hostname verification stays on: this
// only widens whom a certificate may be signed by, never whether it must name the host.
package splice.upstream.transport.tls

import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** The roots every provider is trusted by: Java's own and the operating system's, read once per process. */
public class TrustRoots(private val log: LogSink, private val env: EnvReader = EnvReader(System::getenv)) {
    /** The trust a provider's client checks certificates with: the shared roots and [extra], its `ca_file`. */
    public fun manager(extra: List<X509Certificate> = emptyList()): X509TrustManager {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        (base() + extra).forEachIndexed { i, cert -> store.setCertificateEntry("root-$i", cert) }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun base(): List<X509Certificate> = SharedRoots.roots ?: synchronized(SharedRoots) {
        SharedRoots.roots ?: (java() + system()).also { SharedRoots.roots = it }
    }

    /** The roots Java ships with: its default trust manager's accepted issuers. */
    private fun java(): List<X509Certificate> {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().flatMap { it.acceptedIssuers.toList() }
    }

    /** The operating system's trust store: the bundle SSL_CERT_FILE names, else the first distribution bundle that
     *  exists; on macOS its bundle and the system keychain. One that cannot be read is skipped with one log line: the
     *  person did not write it, so it is never a reason to refuse anything. */
    private fun system(): List<X509Certificate> {
        val mac = System.getProperty("os.name").orEmpty().startsWith("Mac")
        val named = env(SSL_CERT_FILE)?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
        val bundles = if (mac) listOf(MAC_BUNDLE) else LINUX_BUNDLES
        val bundle = named ?: bundles.map { Paths.get(it) }.firstOrNull(Files::exists)
        val fromBundle = bundle?.let(::pem).orEmpty()
        return if (mac) fromBundle + keychain() else fromBundle
    }

    private fun pem(path: Path): List<X509Certificate> = Cancellables.runCatchingCancellable {
        CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(Files.readAllBytes(path)))
            .filterIsInstance<X509Certificate>()
    }.getOrElse { failure ->
        val why = SafeFailureText.render(failure)
        log("[tls] the trust store $path could not be read, so its roots are not trusted: $why\n")
        emptyList()
    }

    private fun keychain(): List<X509Certificate> = Cancellables.runCatchingCancellable {
        val store = KeyStore.getInstance(MAC_KEYCHAIN).apply { load(null, null) }
        store.aliases().toList().mapNotNull { store.getCertificate(it) as? X509Certificate }
    }.getOrElse { failure ->
        val why = SafeFailureText.render(failure)
        log("[tls] the system keychain could not be read, so its roots are not trusted: $why\n")
        emptyList()
    }
}

/** The process's shared roots, read by the first client that asks: the OS store is read at start, once. */
private object SharedRoots {
    @Volatile
    var roots: List<X509Certificate>? = null
}

private const val SSL_CERT_FILE = "SSL_CERT_FILE"
private const val MAC_BUNDLE = "/etc/ssl/cert.pem"
private const val MAC_KEYCHAIN = "KeychainStore"
private val LINUX_BUNDLES =
    listOf("/etc/ssl/certs/ca-certificates.crt", "/etc/pki/tls/certs/ca-bundle.crt", "/etc/ssl/cert.pem")
