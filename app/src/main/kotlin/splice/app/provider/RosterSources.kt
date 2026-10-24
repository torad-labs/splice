// NEW: LAN share, phase 2, milestone 3 (docs/specs/lan-share.md). Where each head's discovered models come from. A
// `splice-remote` head relays to a head on the machine it joined, so its list is that head's picker there
// (`<base>/roster`, the SharedHead the import read): its own rows under their own ids, never the wrapped ids a
// passthrough `/v1/models` would list. Every other head asks its provider's endpoint, as before.
package splice.app.provider

import splice.core.model.DiscoveredModel
import splice.core.topology.AuthKind
import splice.core.topology.ProviderConfig
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.models.discovery.Discovery
import splice.share.remote.RemoteLinks
import splice.share.remote.RemoteUnanswered

internal class RosterSources(
    /** The machines this one joined; null where no daemon built them, and a remote head then lists nothing. */
    private val remotes: RemoteLinks?,
    private val endpoints: HeadModelsSource = EndpointModels(),
) : HeadModelsSource {

    override fun discover(key: String, provider: ProviderConfig): Discovery {
        val remote = provider.auth.remote?.takeIf { provider.auth.kind == AuthKind.SpliceRemote.wire }
            ?: return endpoints.discover(key, provider)
        val api = remotes?.api(remote) ?: return Discovery.Unavailable(null, "$remote is not a machine this one joined")
        return Cancellables.runCatchingCancellable { api.roster(provider.baseUrl) }.fold(
            onSuccess = { head ->
                val models = head.models.map { DiscoveredModel(it.id, it.label, it.contextWindow, rates = it.rates) }
                Discovery.Found(provider.rosterUrl, models)
            },
            onFailure = {
                Discovery.Unavailable(provider.rosterUrl, (it as? RemoteUnanswered)?.said ?: SafeFailureText.render(it))
            },
        )
    }
}
