// NEW: 2026-09-22 — the daemon's half of model discovery: ask a head's provider what it serves and
// hand its catalog the models that can run a turn.
//
// The operator: "make sure that splice probes the head endpoint for available models instead of
// having to hardcode them on the toml file". The question is the one `splice models` already asks
// (ModelsProbe, same URL, same credential, same parser), so there is one probe and two readers of
// its answer: the verb REPORTS it against splice.toml, and this TURNS IT INTO ROWS. What the rows
// mean next to the declared ones is the catalog's decision (ProviderConfig.catalogFor), not this
// file's: here is only "what the endpoint says it serves, minus what it says cannot serve a turn".
//
// A LOCAL RUNTIME IS NOT ASKED. llama-server lists the .gguf path it loaded (measured 2026-09-22 on
// 127.0.0.1:8099) and serves that model whatever id a request names, so its list names a file, not
// a model an operator would pick, and the rows splice.toml declares for it are already checked
// against the runtime at boot (LocalProbeInputs).
//
// BLOCKING. One HTTP GET per call, bounded by ModelsHttp's timeout; the daemon runs these off every
// request path, before any head starts.
package splice.models.discovery

import splice.core.model.DiscoveredModel
import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader
import splice.models.list.ModelCredentialSource
import splice.models.list.ModelsProbe
import splice.models.list.ProbedProvider
import splice.models.list.UpstreamModel
import splice.models.list.UpstreamRoster

/** What asking one head's provider for its models yielded. */
public sealed class Discovery {
    /** The endpoint at [url] answered; [models] are those it does not itself rule out. May be empty.
     *  [ruledOut] counts the rest: rows the endpoint says cannot run a turn (hidden, no text, no tools). */
    public data class Found(val url: String, val models: List<DiscoveredModel>, val ruledOut: Int = 0) : Discovery()

    /** No list this time: [reason], in the operator's terms. [url] is where it was asked, or null
     *  when it was not asked at all. */
    public data class Unavailable(val url: String?, val reason: String) : Discovery()
}

public class ModelDiscovery(credentials: ModelCredentialSource) {

    private val probe = ModelsProbe(credentials = credentials)

    /** Ask the endpoint behind [provider] as head [key] would authenticate to it. */
    public fun discover(key: String, provider: ProviderConfig, env: EnvReader): Discovery {
        if (provider.isLocal) return Discovery.Unavailable(null, LOCAL_NOT_ASKED)
        return answer(probe.probe(key, provider, env))
    }

    /** What one probe's answer means for a catalog — the whole decision, apart from the socket. */
    internal fun answer(probed: ProbedProvider): Discovery = when (val roster = probed.roster) {
        is UpstreamRoster.Published -> roster.models.mapNotNull(::usable).let { usable ->
            Discovery.Found(probed.url, usable, ruledOut = roster.models.size - usable.size)
        }
        is UpstreamRoster.Unpublished -> Discovery.Unavailable(probed.url, roster.reason)
        is UpstreamRoster.Unreadable -> Discovery.Unavailable(probed.url, roster.detail)
    }

    private fun usable(model: UpstreamModel): DiscoveredModel? =
        model.takeIf { it.unusable == null && it.id.isNotBlank() }
            ?.let { DiscoveredModel(it.id, it.label, it.contextWindow, it.aliases) }
}

private const val LOCAL_NOT_ASKED =
    "a local runtime lists the file it loaded, not a model to pick — its declared rows are the picker"
