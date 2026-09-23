// NEW: 2026-09-22 — the model list each head's endpoint last published, kept across daemon starts.
//
// WHY IT IS LOAD-BEARING, not a speed-up. A catalog is also the gate every turn passes ("proxies its
// own models only", TurnPreparation). A session running on a DISCOVERED model survives a daemon
// restart only if that model is still in the catalog afterwards — so a start where the vendor is
// slow or down must not shrink the picker back to splice.toml's rows. The last answer stands in for
// the live one, and only for the URL that gave it: a moved base_url or models_url is a different
// endpoint, whose old answer says nothing.
package splice.models.discovery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.config.StatePaths
import splice.core.model.DiscoveredModel
import splice.core.topology.ProviderConfig
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.models.list.UpstreamRosterUrl
import java.nio.file.Files
import java.nio.file.Path

public class RosterCache(
    private val statePaths: StatePaths,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /** Keep [found] as [headKey]'s last answer. Throws what the write throws: the caller logs it. */
    public fun write(headKey: String, found: Discovery.Found) {
        val roster = CachedRoster(found.url, found.models.map(::cached))
        val text = json.encodeToString(CachedRoster.serializer(), roster)
        SecureFile.writeAtomic0600(statePaths.modelRosterFile(headKey), text)
    }

    /** What [headKey]'s endpoint last published for the URL [provider] is asked at now, or which of
     *  the three reasons there is none — each has a different fix, so the caller's line names it. */
    public fun read(headKey: String, provider: ProviderConfig): KeptRoster {
        val url = UpstreamRosterUrl.of(provider)
        val file = statePaths.modelRosterFile(headKey)
        if (!Files.isRegularFile(file)) return KeptRoster.None
        return Cancellables.runCatchingCancellable { decode(file) }.fold(
            onSuccess = { cached ->
                if (cached.url == url) {
                    KeptRoster.Kept(cached.models.map(CachedModel::model))
                } else {
                    KeptRoster.OtherUrl(cached.url)
                }
            },
            onFailure = { KeptRoster.Unreadable("$file could not be read (${SafeFailureText.render(it)})") },
        )
    }

    private fun decode(file: Path): CachedRoster =
        json.decodeFromString(CachedRoster.serializer(), Files.readString(file))

    private fun cached(model: DiscoveredModel): CachedModel =
        CachedModel(model.id, model.label, model.contextWindow, model.aliases)
}

/** A head's kept list, or why there is none to stand in for its endpoint. */
public sealed class KeptRoster {
    /** The list the endpoint published last, at the URL it is asked at now. */
    public data class Kept(val models: List<DiscoveredModel>) : KeptRoster()

    /** No list was ever kept for this head. */
    public data object None : KeptRoster()

    /** The list kept was published at [url], a different endpoint, and says nothing about this one. */
    public data class OtherUrl(val url: String) : KeptRoster()

    /** The kept file is there and could not be read: [reason]. */
    public data class Unreadable(val reason: String) : KeptRoster()
}

@Serializable
private data class CachedRoster(val url: String, val models: List<CachedModel>)

@Serializable
private data class CachedModel(
    val id: String,
    val label: String = "",
    @SerialName("context_window") val contextWindow: Long? = null,
    val aliases: List<String> = emptyList(),
) {
    fun model(): DiscoveredModel = DiscoveredModel(id, label, contextWindow, aliases)
}
