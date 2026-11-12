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
import splice.core.model.ModelRates
import splice.core.model.ModelRuntimeMetadata
import splice.core.topology.ProviderConfig
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.nio.file.Files

public class RosterCache(
    private val statePaths: StatePaths,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /** Keep [found] as [headKey]'s last answer. Throws what the write throws: the caller logs it. */
    public fun write(headKey: String, found: Discovery.Found) {
        val roster =
            CachedRoster(found.url, found.models.map(::cached), found.metadata.map(::cached), MODEL_METADATA_VERSION)
        val text = json.encodeToString(CachedRoster.serializer(), roster)
        SecureFile.writeAtomic0600(statePaths.modelRosterFile(headKey), text)
    }

    /** What [headKey]'s endpoint last published for the URL [provider] is asked at now, or which of
     *  the three reasons there is none — each has a different fix, so the caller's line names it. */
    public fun read(headKey: String, provider: ProviderConfig): KeptRoster {
        val file = statePaths.modelRosterFile(headKey)
        if (!Files.isRegularFile(file)) return KeptRoster.None
        return Cancellables.runCatchingCancellable { decode(Files.readString(file), provider) }.fold(
            onSuccess = { it },
            onFailure = { KeptRoster.Unreadable("$file could not be read (${SafeFailureText.render(it)})") },
        )
    }

    /** Old picker rows remain usable, while absent versioned metadata is explicitly unknown. */
    internal fun decode(text: String, provider: ProviderConfig): KeptRoster {
        val cached = json.decodeFromString(CachedRoster.serializer(), text)
        return if (cached.url == provider.rosterUrl) {
            KeptRoster.Kept(
                cached.models.map(CachedModel::model),
                if (cached.metadataVersion == MODEL_METADATA_VERSION) {
                    (cached.metadata ?: cached.models).map(CachedModel::model)
                } else {
                    emptyList()
                },
            )
        } else {
            KeptRoster.OtherUrl(cached.url)
        }
    }

    private fun cached(model: DiscoveredModel): CachedModel =
        CachedModel(
            model.id,
            model.label,
            model.contextWindow,
            model.aliases,
            model.rates,
            model.toolMode,
            model.maxContextWindow,
            model.supportsImageDetailOriginal,
            model.modelMessages,
            model.useResponsesLite,
        )
}

@Serializable
private data class CachedRoster(
    val url: String,
    val models: List<CachedModel>,
    val metadata: List<CachedModel>? = null,
    @SerialName("metadata_version") val metadataVersion: Int = 0,
)

@Serializable
private data class CachedModel(
    val id: String,
    val label: String = "",
    @SerialName("context_window") val contextWindow: Long? = null,
    val aliases: List<String> = emptyList(),
    /** V4-438: the card the endpoint listed, kept so a start with the vendor down still prices the model.
     *  A file written before it decodes to null, which is "no card listed". */
    val rates: ModelRates? = null,
    /** V4-441: the endpoint's `tool_mode`, kept so a start with the backend down still knows which models run
     *  code mode. A file written before it decodes to null, which is "no flag". */
    @SerialName("tool_mode") val toolMode: String? = null,
    @SerialName("max_context_window") val maxContextWindow: Long? = null,
    @SerialName("supports_image_detail_original") val supportsImageDetailOriginal: Boolean = false,
    @SerialName("model_messages") val modelMessages: kotlinx.serialization.json.JsonObject? = null,
    @SerialName("use_responses_lite") val useResponsesLite: Boolean = false,
) {
    fun model(): DiscoveredModel = DiscoveredModel(
        id,
        label,
        contextWindow,
        aliases,
        rates,
        maxContextWindow,
        ModelRuntimeMetadata(supportsImageDetailOriginal, modelMessages, useResponsesLite, toolMode),
    )
}

// why: the first versioned layout of a kept roster's runtime metadata. A file written before it has no
// metadata_version, which reads as 0, so its picker rows stay usable and its metadata reads as unknown.
private const val MODEL_METADATA_VERSION = 1
