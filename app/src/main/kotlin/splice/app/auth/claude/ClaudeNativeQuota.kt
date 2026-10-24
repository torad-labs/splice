// NEW: a native place reads only its effective credential's quota; each probe captures its own sending key.
package splice.app.auth.claude

import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.usage.QuotaSnapshot
import splice.head.usage.CredentialQuotaFiles
import splice.head.usage.CredentialQuotaListener
import splice.upstream.credentials.AccountQuotaSource
import splice.upstream.transport.UpstreamHeaders
import splice.usage.quota.ClientUserAgent
import splice.usage.quota.QuotaProbe
import splice.usage.quota.QuotaProbes
import java.util.concurrent.atomic.AtomicReference

internal class ClaudeNativeQuota(
    private val auth: ClaudeNativeAuth,
    private val files: CredentialQuotaFiles,
    private val live: AccountQuotaSource? = null,
) : AccountQuotaSource, CredentialQuotaListener {
    private data class Observation(val key: String, val snapshot: QuotaSnapshot)

    // A display snapshot has no credential stamp. Seed only from keyed observations, never a captured place view.
    private val latest = AtomicReference<Observation?>(null)

    override val held: Boolean get() = live?.held == true

    override fun snapshot(): QuotaSnapshot? {
        val key = auth.credentialKey ?: return null
        val observed = latest.get()?.takeIf { it.key == key }?.snapshot ?: files.read(key)
        return listOfNotNull(observed, live?.snapshot()).maxByOrNull(QuotaSnapshot::updatedAt)
            ?.takeIf { auth.credentialKey == key }
    }

    override fun observed(key: String, snapshot: QuotaSnapshot) {
        if (snapshot.isEmpty && !snapshot.answeredEmpty) return
        // The file first, then the view: a reader that sees this reading in memory must find it on disk too. The other
        // order left a window where the view held the new reading and the file still held the old one.
        files.observed(key, snapshot)
        latest.updateAndGet { previous ->
            if (previous?.key == key && previous.snapshot.updatedAt > snapshot.updatedAt) {
                previous
            } else {
                Observation(key, snapshot)
            }
        }
    }

    fun probe(probes: QuotaProbes, kind: String, baseUrl: String, userAgent: ClientUserAgent): QuotaProbe =
        QuotaProbe {
            var sendingKey: String? = null
            val sending = object : RefreshableAuthProvider by auth {
                override suspend fun credentials(): Credentials? = auth.credentials()?.also {
                    sendingKey = CredentialKey.fromHeaders(UpstreamHeaders.compose(it, emptyMap()))
                }
            }
            val snapshot = probes.forHead(kind, baseUrl, sending, null, userAgent)?.probe()
            sendingKey?.let { key -> snapshot?.let { observed(key, it) } }
            snapshot
        }
}
