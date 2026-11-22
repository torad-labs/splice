// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 3). What agy reports, in the shapes Claude
// Desktop reads: its models as the `initialize` answer lists them (under the names Desktop knows them by), its slash
// commands, and a prompt's token usage as an Anthropic usage.
package splice.provider.antigravity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.model.ModelCatalog
import splice.core.util.JsonScalars

/** Reads agy's models, commands and usage as Desktop takes them. */
internal class DesktopLists {
    /** [catalog]'s models as `initialize` lists them; none without a catalog. */
    fun models(catalog: ModelCatalog?): JsonArray = buildJsonArray {
        catalog?.models.orEmpty().forEach { model ->
            val efforts = model.listed?.listing?.efforts.orEmpty()
            add(
                buildJsonObject {
                    put("value", catalog?.names?.nameOf(model.id) ?: model.id)
                    put("displayName", model.label)
                    put("description", model.label)
                    put("supportsEffort", efforts.isNotEmpty())
                    put("supportedEffortLevels", JsonArray(efforts.map(::JsonPrimitive)))
                },
            )
        }
    }

    /** The commands of an `available_commands_update`. */
    fun commands(update: JsonObject): List<AgyCommand> =
        (update["availableCommands"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { command ->
            JsonScalars.str(command, "name")?.let { name ->
                AgyCommand(
                    name,
                    JsonScalars.str(command, "description").orEmpty(),
                    JsonScalars.str(command["input"] as? JsonObject, "hint").orEmpty(),
                )
            }
        }

    /** A prompt's ACP usage (its `usage`) as Anthropic's; zeros for what agy did not report. */
    fun usage(usage: JsonObject?): JsonObject = buildJsonObject {
        put("input_tokens", JsonScalars.long(usage, "inputTokens") ?: 0)
        put("output_tokens", JsonScalars.long(usage, "outputTokens") ?: 0)
        put("cache_creation_input_tokens", JsonScalars.long(usage, "cachedWriteTokens") ?: 0)
        put("cache_read_input_tokens", JsonScalars.long(usage, "cachedReadTokens") ?: 0)
    }
}
