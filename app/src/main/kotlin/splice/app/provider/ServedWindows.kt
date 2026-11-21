// NEW: custom-provider (issue #680, cp-window). The window each provider with a `served_window` reports, read once per
// daemon start, the first time one of its heads builds its catalog, and kept: a picked model's window is then the one
// its provider really serves, and the door scales a session's counts to it (ModelCatalog.usageScale). A read that
// fails leaves the declared windows in force and says why in one log line; `splice doctor` says it too.
package splice.app.provider

import splice.core.topology.ProviderConfig
import splice.core.util.DaemonLog
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.upstream.local.ServedWindowProbe
import java.util.concurrent.ConcurrentHashMap

internal class ServedWindows(
    private val log: LogSink = LogSink(DaemonLog::write),
    private val inputs: LocalProbeInputs = LocalProbeInputs(),
    private val env: EnvReader = EnvReader(System::getenv),
) {
    // The window each provider reported, by provider key; 0 for a read that failed, so it is not asked again.
    private val windows = ConcurrentHashMap<String, Long>()

    /** [provider] with its served window as every model's, when it names one and the read found it; else itself.
     *  [headKey] is the head whose key the provider's headers are read with. Blocking network on the first call per
     *  provider: never on a request thread. */
    fun applied(providerKey: String, headKey: String, provider: ProviderConfig): ProviderConfig {
        val served = provider.servedWindow ?: return provider
        val window = windows.computeIfAbsent(providerKey) {
            val http = JdkLocalHttp(inputs.headers(provider, inputs.bearer(headKey, provider, env)))
            when (val read = ServedWindowProbe(http).read(served)) {
                is ServedWindowProbe.Read.Window -> read.tokens
                is ServedWindowProbe.Read.Failed -> {
                    log("[window] ${read.said(providerKey, provider)}\n")
                    0L
                }
            }
        }
        // cp-window: what the provider serves is the real ceiling of each of its models
        return if (window > 0) {
            provider.copy(
                models = provider.models.map { it.copy(contextWindow = window) },
                defaultContextWindow = window,
            )
        } else {
            provider
        }
    }
}
