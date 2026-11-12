package splice.models.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.config.StatePaths
import splice.core.model.DiscoveredModel
import splice.core.topology.ProviderConfig
import splice.core.util.JsonWire
import splice.models.list.ModelCredentialSource
import splice.models.list.ProbedProvider
import splice.models.list.UpstreamRosterParser
import java.nio.file.Files

/** Diagnostic: published and hidden metadata passes through the parser and a temporary production cache.
 *  The caller supplies the provider configuration; this helper selects no vendor or authentication policy. */
public object ModelMetadataProbe {
    public fun evaluate(request: JsonObject): JsonObject {
        val provider = Json.decodeFromJsonElement(
            ProviderConfig.serializer(),
            requireNotNull(request["provider"]) { "Model metadata probe requires a provider configuration" },
        )
        val catalog = request["catalog"] ?: buildJsonObject {
            put(
                MODELS_FIELD,
                request[MODELS_FIELD] ?: JsonArray(emptyList()),
            )
        }
        val roster = UpstreamRosterParser().parse(JsonWire.string(catalog), provider.rosterUrl)
        val found = ModelDiscovery(ModelCredentialSource { _, _, _ -> "unused" })
            .answer(
                ProbedProvider(PROBE_HEAD, provider, provider.rosterUrl, roster),
            ) as? Discovery.Found
            ?: error("Model metadata probe requires a published catalog")
        val temporary = Files.createTempDirectory("splice-model-metadata-")
        try {
            val paths = StatePaths(baseOverride = temporary)
            RosterCache(paths).write(PROBE_HEAD, found)
            val kept = RosterCache(paths).read(PROBE_HEAD, provider) as? KeptRoster.Kept
                ?: error("Model metadata probe did not restore its temporary cache")
            val current =
                checkNotNull(
                    Json.parseToJsonElement(
                        Files.readString(paths.modelRosterFile(PROBE_HEAD)),
                    ) as? JsonObject,
                )
            val legacy = legacy(current, provider, paths)
            return buildJsonObject {
                put("visible", JsonArray(found.models.map { JsonPrimitive(it.id) }))
                put("metadata", wire(found.metadata)); put("cached_metadata", wire(kept.metadata))
                put("legacy_visible", JsonArray(legacy.models.map { JsonPrimitive(it.id) }))
                put("legacy_metadata", wire(legacy.metadata))
            }
        } finally {
            Files.walk(
                temporary,
            ).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private fun legacy(current: JsonObject, provider: ProviderConfig, paths: StatePaths): KeptRoster.Kept {
        val legacyRows = checkNotNull(current[MODELS_FIELD] as? JsonArray).map { row ->
            JsonObject(
                checkNotNull(
                    row as? JsonObject,
                ) - setOf("supports_image_detail_original", "model_messages", "use_responses_lite"),
            )
        }
        return RosterCache(paths).decode(
            JsonWire.string(
                JsonObject(
                    current - setOf("metadata", "metadata_version") +
                        (MODELS_FIELD to JsonArray(legacyRows)),
                ),
            ),
            provider,
        ) as? KeptRoster.Kept
            ?: error("Model metadata probe did not decode its legacy cache")
    }

    private fun wire(models: List<DiscoveredModel>): JsonArray = JsonArray(
        models.map { model ->
            buildJsonObject {
                put("id", model.id); put("spellings", JsonArray(model.spellings.map(::JsonPrimitive)))
                put("supports_original", model.supportsImageDetailOriginal)
                put("use_responses_lite", model.useResponsesLite)
                put("model_messages", model.modelMessages ?: JsonNull)
            }
        },
    )
}

private const val MODELS_FIELD = "models"
private const val PROBE_HEAD = "metadata-probe"
