// NEW: optional provider effort vocabulary keeps explicit client effort inside declared upstream model scope.
package splice.core.topology

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Optional chat effort mapping. Its absence preserves the provider's budget-derived requests. */
@Serializable
public data class ChatEffortVocabularyConfig(
    val default: String,
    val levels: Map<String, String>,
    /** Optional upstream model scope; a nonmatching model keeps the legacy budget tiers. */
    @SerialName("model_pattern") val modelPattern: String? = null,
) {
    init {
        require(default.isNotBlank()) { "chat_effort_vocabulary.default must not be blank" }
        val normalized = normalizedLevels()
        require(normalized.all { (key, value) -> key.isNotBlank() && value.isNotBlank() }) {
            "chat_effort_vocabulary.levels must contain nonblank effort names and values"
        }
        require(normalized.size == levels.size) {
            "chat_effort_vocabulary.levels contains duplicate normalized effort names"
        }
        modelPattern?.let { Regex(it) }
    }

    /** Canonical TOML effort names, shared by validation and production vocabulary assembly. */
    public fun normalizedLevels(): Map<String, String> {
        val keys = TomlKeys()
        return levels.mapKeys { keys.unquote(it.key).trim().lowercase() }
    }
}
