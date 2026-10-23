// NEW: LAN share, phase 2, milestone 2 (docs/specs/lan-share.md). What this machine trusts of a sharing machine's
// listener: the one certificate it pinned when it joined, by its SHA-256, and nothing else. No CA and no name check:
// the pin is a stronger claim than either, and a machine reached at a new address or by another of its names keeps
// its pin. A first contact pins nothing yet: it is how `splice remote` reads what a machine offers, and the join that
// follows is pinned to the fingerprint that machine stated, so the token is only ever sent to that certificate.
// Every client path to a remote (RemoteHttp, the relay's upstream client) trusts through one of these.
package splice.share.remote

import splice.share.ShareFingerprint
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/** Trusts the certificate whose fingerprint is [pin]; a null [pin] is a first contact, which accepts any. [machine]
 *  names the remote in a refusal. */
public class RemoteTrust(private val pin: String?, private val machine: String) : X509ExtendedTrustManager() {

    /** A TLS context that trusts through this, for a JDK or OkHttp client. */
    public val context: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(this@RemoteTrust), null) }
    }

    /** OkHttp checks a name after the handshake; here that check is the pin again, since names prove nothing a pinned
     *  certificate does not (and a first contact sends no secret). */
    public val verifier: HostnameVerifier = HostnameVerifier { _, session ->
        pin == null || session.peerCertificates.firstOrNull()?.let(ShareFingerprint::of) == pin
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine): Unit =
        judged(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket): Unit =
        judged(chain)

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String): Unit = judged(chain)

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine): Unit =
        refusedClient()

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket): Unit =
        refusedClient()

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String): Unit = refusedClient()

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun judged(chain: Array<out X509Certificate>) {
        val shown = chain.firstOrNull()?.let(ShareFingerprint::of)
            ?: throw CertificateException("$machine presented no certificate")
        if (pin != null && shown != pin) {
            val pinned = pin.ifEmpty { "none" }
            // What to do comes first: a turn's failure line is cut to a couple of hundred characters, and the two
            // fingerprints that follow are only for comparing.
            throw CertificateException(
                "$machine's certificate changed; to trust the new one, run `splice remote add` for it " +
                    "(it shows $shown, this machine pinned $pinned)",
            )
        }
    }

    private fun refusedClient(): Nothing = throw CertificateException("a share client takes no client certificate")
}
