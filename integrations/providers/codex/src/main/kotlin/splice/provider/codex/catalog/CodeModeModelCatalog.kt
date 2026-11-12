package splice.provider.codex.catalog

import kotlinx.serialization.json.JsonObject

/** The source model facts needed by Code Mode, captured from one current catalog snapshot. */
public data class CodeModeModelMetadata(
    val messages: JsonObject? = null,
    val supportsOriginal: Boolean = false,
    val useResponsesLite: Boolean = false,
)

/** Current catalog metadata keyed by every published model spelling, including hidden aliases. */
public fun interface CodeModeModelCatalog {
    public fun catalog(): Map<String, CodeModeModelMetadata>
}

internal object NoCodeModeModelCatalog : CodeModeModelCatalog {
    override fun catalog(): Map<String, CodeModeModelMetadata> = emptyMap()
}
