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
import splice.core.util.SecureFile
import splice.models.list.UpstreamRosterUrl
import java.nio.file.Files

public class RosterCache(
    private val statePaths: StatePaths,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /** Keep [found] as [headKey]'s last answer. Throws what the write throws: the caller logs it. */
    public fun write(headKey: String, found: Discovery.Found) {
        val roster = CachedRoster(found.url, found.models.map(::cached))
        SecureFile.writeAtomic0600(statePaths.modelRosterFile(headKey), json.encodeToString(CachedRoster.serializer(), roster))
    }

    /** What [headKey]'s endpoint last published, or null when nothing was kept for the URL [provider]
     *  is asked at now. */
    public fun read(headKey: String, provider: ProviderConfig): List<DiscoveredModel>? {
        val url = UpstreamRosterUrl.of(provider)
        val file = statePaths.modelRosterFile(headKey)
        if (!Files.isRegularFile(file)) return null
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-22: a cache that cannot be read is a cache that is not there; the caller logs that it fell back to declared rows, which is the whole observable difference.
        val cached = Cancellables.runCatchingCancellable { json.decodeFromString(CachedRoster.serializer(), Files.readString(file)) }
            .getOrNull()
        return cached?.takeIf { it.url == url }?.models?.map(CachedModel::model)
    }
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

private fun cached(model: DiscoveredModel): CachedModel = CachedModel(model.id, model.label, model.contextWindow, model.aliases)
