// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 3). The agy side of a Desktop session: the ACP
// session it runs on and the choices it was given (AgyOpener opens it). Desktop's model, effort and permission mode
// become agy's `model` and `thought_level` options and its mode: a Desktop model name maps back to agy's id through the
// catalog's readable names, an effort agy's model does not offer becomes the highest it does, and `bypassPermissions`
// has no mode of agy's (agy is started with `--dangerously-skip-permissions` for it instead).
package splice.provider.antigravity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.model.ModelCatalog
import splice.core.model.ModelTierSuffix
import splice.core.util.JsonScalars

/** What a session opened with: agy's options and mode, the catalog its model names map through, and how Desktop's
 *  messages become its prompts. */
internal data class AgySetup(
    val options: JsonArray,
    val mode: String?,
    val catalog: ModelCatalog?,
    val blocks: PromptBlocks,
)

/** agy's session [id] on [agy], opened with [setup]. */
internal class AgySession(private val agy: AcpConnection, val id: String, private val setup: AgySetup) {
    /** agy's options and mode as they stand now: what it opened with, as agy and Desktop changed them since. */
    private var offered: JsonArray = setup.options
    private var agyMode: String? = setup.mode
    private val requests = AcpRequests()

    /** The catalog Desktop's model names map through; null when agy listed no model splice lists. */
    val catalog: ModelCatalog? get() = setup.catalog

    /** agy's messages: its updates, its own requests, and the answers to prompts. */
    val incoming get() = agy.incoming

    /** Runs Desktop's model [desktopModel]; null when it runs, else why not. */
    suspend fun model(desktopModel: String): String? {
        val named = ModelTierSuffix.strip(desktopModel)
        val model = catalog?.unwrap(named) ?: named.substringAfter("--")
        if (model == current(MODEL_OPTION)) return null
        if (model !in values(MODEL_OPTION)) return "agy offers no model $model"
        return set(MODEL_OPTION, model)
    }

    /** Thinks at Desktop's [effort]; null when agy took it (or its model takes no level), else why not. */
    suspend fun effort(effort: String): String? {
        val levels = values(THOUGHT_OPTION)
        val level = effort.takeIf { it in levels } ?: levels.lastOrNull()
        if (level == null || level == current(THOUGHT_OPTION)) return null
        return set(THOUGHT_OPTION, level)
    }

    /** Desktop's permission [mode] as agy's; null when agy took it, or it has none of agy's. */
    suspend fun mode(desktopMode: String): String? {
        val wanted = MODES[desktopMode]
        if (wanted == null || wanted == agyMode) return null
        val params = buildJsonObject {
            put(SESSION_ID, id)
            put("modeId", wanted)
        }
        val answer = agy.request("session/set_mode", params)
        if (answer is AcpAnswer.Result) agyMode = wanted
        return refusal(answer)
    }

    /** agy's own change of its options (`config_option_update`) or mode (`current_mode_update`) in [update]. */
    fun heard(update: JsonObject) {
        when (JsonScalars.str(update, "sessionUpdate")) {
            "config_option_update" -> (update["configOptions"] as? JsonArray)?.let { offered = it }
            "current_mode_update" -> JsonScalars.str(update, "currentModeId")?.let { agyMode = it }
        }
    }

    /** Sends [content] (Desktop's message content) as a prompt; its id, whose answer arrives on [incoming], or null
     *  once agy has ended. */
    fun prompt(content: JsonElement): Long? {
        val params = buildJsonObject {
            put(SESSION_ID, id)
            put("prompt", setup.blocks.of(content))
        }
        return agy.post("session/prompt", params)
    }

    fun cancel() {
        agy.notify("session/cancel", requests.sessionOf(id))
    }

    /** Answers agy's permission request [request] with [option], or as cancelled when [option] is null. */
    fun answer(request: JsonElement, option: String?) {
        val outcome = buildJsonObject {
            putJsonObject("outcome") {
                if (option == null) {
                    put("outcome", "cancelled")
                } else {
                    put("outcome", "selected")
                    put("optionId", option)
                }
            }
        }
        agy.respond(request, outcome)
    }

    /** Refuses agy's request [request], which a session in Desktop does not serve. */
    fun refuse(request: JsonElement, method: String) {
        agy.refuse(request, ACP_METHOD_NOT_FOUND, "$method is not offered by this client")
    }

    private suspend fun set(option: String, value: String): String? {
        val params = buildJsonObject {
            put(SESSION_ID, id)
            put("configId", option)
            put("value", value)
        }
        val answer = agy.request("session/set_config_option", params)
        ((answer as? AcpAnswer.Result)?.result?.get("configOptions") as? JsonArray)?.let { offered = it }
        return refusal(answer)
    }

    private fun option(name: String): JsonObject? =
        offered.mapNotNull { it as? JsonObject }.firstOrNull { JsonScalars.str(it, "id") == name }

    private fun current(name: String): String? = JsonScalars.str(option(name), "currentValue")

    private fun values(name: String): List<String> =
        (option(name)?.get("options") as? JsonArray).orEmpty()
            .mapNotNull { JsonScalars.str(it as? JsonObject, "value") }

    /** Why agy did not take a change; null when it did. */
    private fun refusal(answer: AcpAnswer): String? = when (answer) {
        is AcpAnswer.Result -> null
        is AcpAnswer.Refused -> "agy refused: ${answer.message}"
        AcpAnswer.Ended -> "agy has ended"
    }
}

private const val SESSION_ID = "sessionId"

/** JSON-RPC's "method not found". */
private const val ACP_METHOD_NOT_FOUND = -32601

/** Desktop's permission modes as agy's; `bypassPermissions` has none (agy starts with its skip flag instead). */
private val MODES = mapOf(
    DEFAULT_MODE to "default",
    "dontAsk" to "default",
    "acceptEdits" to "accept-edits",
    "plan" to "plan",
    "auto" to "auto",
)
