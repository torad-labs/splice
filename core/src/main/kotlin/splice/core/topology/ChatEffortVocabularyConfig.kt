package splice.core.topology

import kotlinx.serialization.Serializable

/** Optional chat effort mapping. Its absence preserves the provider's budget-derived requests. */
@Serializable
public data class ChatEffortVocabularyConfig(
    val default: String,
    val levels: Map<String, String>,
) {
    init {
        require(default.isNotBlank()) { "chat_effort_vocabulary.default must not be blank" }
        require(levels.all { (key, value) -> key.isNotBlank() && value.isNotBlank() }) {
            "chat_effort_vocabulary.levels must contain nonblank effort names and values"
        }
    }
}
