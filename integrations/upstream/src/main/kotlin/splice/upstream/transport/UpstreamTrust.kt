// NEW: LAN share, phase 2, milestone 2 (docs/specs/lan-share.md). The one upstream whose TLS trust is not the
// platform's: a shared head on another machine, reached over its own self-signed certificate and trusted by its pin.
package splice.upstream.transport

import javax.net.ssl.HostnameVerifier
import javax.net.ssl.X509TrustManager

/** A TLS trust of its own for one upstream: [manager] judges the certificate and [verifier] stands in for OkHttp's name
 *  check. LAN share's relay passes the pin of the machine it relays to (RemoteTrust). */
public data class UpstreamTrust(public val manager: X509TrustManager, public val verifier: HostnameVerifier)
