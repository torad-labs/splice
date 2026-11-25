package splice.app.provider.tls

import splice.core.topology.ProviderCaFile
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.upstream.transport.tls.TrustRoots
import java.nio.file.Path
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/** The same TLS roots for a provider's turns and completion probes, including its own `ca_file`. */
internal class ProviderTrust(private val log: LogSink) {
    fun manager(provider: String, caFile: String?, configDir: Path?): X509TrustManager =
        TrustRoots(log).manager(caFile?.let { certificates(provider, it, configDir) }.orEmpty())

    /** Boot refuses an unreadable bundle. If it disappears after boot, log that loss and keep the shared roots. */
    private fun certificates(provider: String, caFile: String, configDir: Path?): List<X509Certificate> =
        Cancellables.runCatchingCancellable { ProviderCaFile(caFile, configDir).certificates() }.getOrElse { failure ->
            log("[tls] providers.$provider.ca_file could not be read: ${SafeFailureText.render(failure)}\n")
            emptyList()
        }
}
