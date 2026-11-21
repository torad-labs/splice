// NEW: custom-provider (issue #680, cp-output). The most a model is asked to write in one turn: its row's
// `max_output_tokens` in splice.toml, which every dialect's provider caps a turn's request to.
package splice.core.model

/** The output caps [catalog]'s rows declare. */
public class OutputCap(private val catalog: ModelCatalog) {
    /** The most [upstreamModel] is asked to write in one turn; null when its row declares none. */
    public fun of(upstreamModel: String): Long? {
        val rows = catalog.live().models
        val bare = ModelTierSuffix.strip(upstreamModel)
        val row = rows.firstOrNull { it.id == upstreamModel }
            ?: rows.firstOrNull { ModelTierSuffix.strip(it.id) == bare }
        return row?.maxOutputTokens
    }
}
