// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, "What agy offers"). The models `agy acp` offers an
// account, read from its answer to `session/new`: the `model` option names each base model, the `thought_level` option
// names the levels a turn can ask, and `models.availableModels` lists each model once per level it takes (its id plus
// a level suffix, `gemini-4-neon-high`), so a model's levels are the suffixes listed for it. A model listed with no
// suffix (Orchestrator, GPT-OSS) takes none.
package splice.provider.antigravity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

/** One model agy offers: its id, its name, the reasoning levels it takes and the one a session starts it at. */
internal data class AgyModel(val id: String, val label: String, val efforts: List<String>, val defaultEffort: String?)

/** What agy offers this account: its models, the context window it gives every one of them, and the model a new
 *  session starts on. */
internal data class AgyRoster(val models: List<AgyModel>, val contextWindow: Long, val current: String?)

/** Reads an [AgyRoster] out of agy's answer to `session/new`. */
internal class AgyRosterReader {
    /** The roster [session] (the `session/new` result) offers, each model given [contextWindow]. */
    fun of(session: JsonObject, contextWindow: Long): AgyRoster {
        val options = (session["configOptions"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val model = options.firstOrNull { JsonScalars.str(it, "id") == MODEL_OPTION }
        val thought = options.firstOrNull { JsonScalars.str(it, "id") == THOUGHT_OPTION }
        val levels = valuesOf(thought).map { it.first }
        val listed = ((session["models"] as? JsonObject)?.get("availableModels") as? JsonArray).orEmpty()
            .mapNotNull { JsonScalars.str(it as? JsonObject, "modelId") }
            .toSet()
        val startsAt = JsonScalars.str(thought, "currentValue")
        val models = valuesOf(model).map { (id, label) ->
            val efforts = levels.filter { "$id-$it" in listed }
            AgyModel(id, label, efforts, startsAt?.takeIf { it in efforts })
        }
        return AgyRoster(models, contextWindow, JsonScalars.str(model, "currentValue"))
    }

    /** [option]'s values, each with its name. */
    private fun valuesOf(option: JsonObject?): List<Pair<String, String>> =
        (option?.get("options") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { value ->
            JsonScalars.str(value, "value")?.let { it to (JsonScalars.str(value, "name") ?: it) }
        }
}

internal const val MODEL_OPTION: String = "model"
internal const val THOUGHT_OPTION: String = "thought_level"
