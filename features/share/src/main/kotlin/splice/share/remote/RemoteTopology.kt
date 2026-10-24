// NEW: LAN share, phase 2 (docs/specs/lan-share.md). What `splice remote`, and the console's Sharing tab after it,
// changes in splice.toml, as edits of a Topology the caller writes through TopologyWriter (backup first, refused
// on a finding): the `[remotes]` entry a join adds, the rows a sync refreshes, everything a removal drops, and the
// phase-1 imports (api-key providers pointed at a share relay) a re-join moves onto their machine. No file here. Since
// round 15 of PR #419's review, a machine is one entry by its certificate: a second machine whose name comes to the
// same key is joined under a key of its own (keyFor), and the first keeps its address, pin and token.
package splice.share.remote

import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.AuthKind
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.RemoteConfig
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.share.SHARE_HEADS_PATH
import splice.share.SharedHead
import java.net.URI

public class RemoteTopology(private val topology: Topology) {
    /** [name] at [url], pinned to [fingerprint], replacing the entry it had; every command relayed to it moves to
     *  [url] with it, so a machine that changed address is reached at the new one. */
    public fun joined(name: String, url: String, fingerprint: String?): Topology = topology.copy(
        remotes = topology.remotes + (name to RemoteConfig(url, fingerprint)),
        providers = topology.providers.mapValues { (_, provider) ->
            val head = provider.baseUrl.trimEnd('/').substringAfter("$SHARE_HEADS_PATH/", "")
            val moves = relaysTo(provider, name) && head.isNotEmpty()
            if (moves) provider.copy(baseUrl = RemoteRelay.url(url, head)) else provider
        },
    )

    /** The `[remotes]` key a machine named [server], reached at [url] and showing [pin], is joined under: the entry
     *  pinned to [pin] when there is one, since a certificate is one machine's; else its name ([RemoteNames]), unless
     *  that entry is another machine's, at another address with another certificate, which keeps it, and then the name
     *  with the first number free. A machine with a new certificate at the address it was joined at is the same
     *  machine. */
    public fun keyFor(server: String, url: String, pin: String): String {
        val name = RemoteNames.of(server)
        val keys = sequenceOf(name) + generateSequence(2) { it + 1 }.map { "$name-$it" }
        return topology.remotes.entries.firstOrNull { it.value.fingerprint == pin }?.key
            ?: keys.first { key -> topology.remotes[key]?.let { it.url == url } ?: true }
    }

    /** The heads relayed to [name], by key. */
    public fun heads(name: String): Map<String, HeadConfig> =
        topology.heads.filter { (_, head) -> topology.providers[head.provider]?.let { relaysTo(it, name) } == true }

    /** The head here that relays [remoteKey] on [name], or null when none does. */
    public fun headFor(name: String, remoteKey: String): String? = heads(name).entries.firstOrNull { (_, head) ->
        topology.providers[head.provider]?.baseUrl?.trimEnd('/')?.endsWith("$SHARE_HEADS_PATH/$remoteKey") == true
    }?.key

    /** Phase 1's imports from the machine reachable as any of [hosts]: api-key providers on a share relay there, by
     *  provider key, with the head key each relays on the sharing machine. */
    public fun phaseOne(hosts: Set<String>): Map<String, String> {
        val names = hosts.map { it.lowercase() }.toSet()
        return topology.providers.mapNotNull { (key, provider) ->
            val uri = parsed(provider.baseUrl) ?: return@mapNotNull null
            val path = uri.path.orEmpty().trimEnd('/')
            val relayed = path.substringAfter("$SHARE_HEADS_PATH/", "").takeIf { it.isNotEmpty() && '/' !in it }
            val here = relayed?.takeIf { uri.host?.lowercase()?.trim('[', ']') in names }
            if (provider.auth.isApiKey && here != null) key to here else null
        }.toMap()
    }

    /** [providers] (provider key to the head key it relays there) moved onto [name] at [url]: `splice-remote` auth,
     *  the relay re-pointed at [url], everything else as it was. */
    public fun migrated(providers: Map<String, String>, name: String, url: String): Topology = topology.copy(
        providers = topology.providers + providers.mapNotNull { (key, remoteKey) ->
            val provider = topology.providers[key] ?: return@mapNotNull null
            key to provider.copy(
                baseUrl = RemoteRelay.url(url, remoteKey),
                auth = AuthConfig(kind = AuthKind.SpliceRemote.wire, remote = name),
            )
        },
    )

    /** Without [name]: its entry, the providers relayed to it, their heads, and those heads in any project. */
    public fun without(name: String): Topology {
        val providers = topology.providers.filterValues { relaysTo(it, name) }.keys
        val heads = topology.heads.filterValues { it.provider in providers }.keys
        return topology.copy(
            remotes = topology.remotes - name,
            providers = topology.providers - providers,
            heads = topology.heads - heads,
            projects = topology.projects.mapValues { (_, project) -> project.copy(heads = project.heads - heads) },
        )
    }

    /** [key]'s rows (windows, reserves and cards), pin and tiers as the sharing machine describes [shared] now, and its
     *  family. */
    public fun synced(key: String, shared: SharedHead): Topology {
        val head = topology.heads[key] ?: return topology
        val provider = topology.providers[head.provider]
        val pinned = shared.models.firstOrNull()
        if (provider == null || pinned == null) return topology
        val rows = shared.models.map {
            ModelEntry(
                id = it.id,
                label = it.label,
                contextWindow = it.contextWindow,
                rates = it.rates,
                compactionReserveTokens = it.compactionReserve,
            )
        }
        val slots = shared.models.flatMap { model -> model.slots.map { slot -> slot to model.id } }.toMap()
        val discovery = provider.discovery.copy(family = shared.family ?: provider.discovery.family)
        // Tiers go in model_slots and every row keeps its own window, as `splice remote` adds a head; a phase-1 head's
        // models allowlist and head-wide window would compete with that, so they go.
        val tiered = head.copy(pinnedModel = pinned.id, modelSlots = slots, models = null, contextWindow = null)
        return topology.copy(
            providers = topology.providers + (head.provider to provider.copy(models = rows, discovery = discovery)),
            heads = topology.heads + (key to tiered),
        )
    }

    private fun relaysTo(provider: ProviderConfig, name: String): Boolean =
        provider.auth.kind == AuthKind.SpliceRemote.wire && provider.auth.remote == name

    /** A base_url that does not parse is simply not a relay, which is the whole answer here. */
    private fun parsed(url: String): URI? = Cancellables.runCatchingCancellable { URI.create(url) }
        .fold(onSuccess = { it }, onFailure = { null })
}

/** Where a shared head's relay sits on a remote at [url]. */
public object RemoteRelay {
    public fun url(remote: String, head: String): String = "${remote.trimEnd('/')}$SHARE_HEADS_PATH/$head"
}
