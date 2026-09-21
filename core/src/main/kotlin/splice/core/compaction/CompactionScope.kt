// NEW: v0.4.0 FEATURES.md §7 — the [compaction] table of splice.toml as data.
package splice.core.compaction

import kotlinx.serialization.Serializable

/** The `[compaction]` table in splice.toml. Singular list names mirror TOML array tables. */
@Serializable
public data class CompactionConfig(
    val instructions: String? = null,
    val file: String? = null,
    val model: List<CompactionModelConfig> = emptyList(),
    val project: List<CompactionProjectConfig> = emptyList(),
)

@Serializable
public data class CompactionModelConfig(
    val model: String,
    val instructions: String? = null,
    val file: String? = null,
)

@Serializable
public data class CompactionProjectConfig(
    val path: String,
    val model: String? = null,
    val instructions: String? = null,
    val file: String? = null,
)

public enum class CompactionScope(public val wire: String) {
    CLIENT("client"),
    GLOBAL("global"),
    MODEL("model"),
    PROJECT("project"),
    PROJECT_MODEL("project-model"),
}

/** Effective text and its inspectable provenance. Null text preserves the client's request;
 *  empty text is an explicit opt-out and also preserves it, but retains the configured source. */
public data class EffectiveCompactionInstructions(
    val text: String?,
    val scope: CompactionScope,
    val source: String,
) {
    public val tailText: String? get() = text?.takeIf(String::isNotEmpty)
}
