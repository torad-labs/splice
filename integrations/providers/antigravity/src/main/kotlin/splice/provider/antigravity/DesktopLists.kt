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
import splice.provider.antigravity.skills.LocalSkillItem

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

    /** The roster of an `available_commands_update`, or null when no list was announced. */
    fun commands(update: JsonObject): List<AgyCommand>? {
        val commands = update["availableCommands"] as? JsonArray ?: return null
        return commands.mapNotNull { it as? JsonObject }.mapNotNull { command ->
            JsonScalars.str(command, "name")?.let { name ->
                AgyCommand(
                    name,
                    JsonScalars.str(command, "description").orEmpty(),
                    JsonScalars.str(command["input"] as? JsonObject, "hint").orEmpty(),
                )
            }
        }
    }

    /** Merges [agy] native slash commands with [local] Claude skills and commands, keeping the first entry per name. */
    fun mergeCommands(agy: List<AgyCommand>, local: List<LocalSkillItem>): List<AgyCommand> {
        val merged = LinkedHashMap<String, AgyCommand>()
        agy.forEach { merged.putIfAbsent(it.name, it) }
        local.forEach { item ->
            merged.putIfAbsent(item.name, AgyCommand(item.name, item.menuDescription, item.hint))
        }
        return merged.values.toList()
    }

    /** A prompt's ACP usage (its `usage`) as Anthropic's; zeros for what agy did not report. */
    fun usage(usage: JsonObject?): JsonObject = buildJsonObject {
        put("input_tokens", JsonScalars.long(usage, "inputTokens") ?: 0)
        put("output_tokens", JsonScalars.long(usage, "outputTokens") ?: 0)
        put("cache_creation_input_tokens", JsonScalars.long(usage, "cachedWriteTokens") ?: 0)
        put("cache_read_input_tokens", JsonScalars.long(usage, "cachedReadTokens") ?: 0)
    }
}
