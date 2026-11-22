// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md, decision 3). agy's permission requests as Claude
// Desktop's own prompt: each `session/request_permission` becomes a `can_use_tool` control request on the call's card,
// and Desktop's allow or deny is answered with the option of that kind agy offered, once before always. A Stop drops
// every open question, answered to agy as cancelled and withdrawn from Desktop. In `bypassPermissions` agy is allowed
// at once and Desktop is not asked.
package splice.provider.antigravity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import java.util.UUID

/** A question agy asked that Desktop has not answered yet: agy's request id and the options it offered. */
private data class Asked(val request: JsonElement, val options: JsonArray)

/** The permission questions of one session, asked through [frames] on [out], each call's card drawn by [turn]. */
internal class AgyQuestions(
    private val frames: DesktopFrames,
    private val out: DesktopOut,
    private val turn: AgyTurn,
) {
    private val asked = HashMap<String, Asked>()

    /** agy's [request] with [params] on [session]: allowed at once when [bypassing], else asked of Desktop. */
    fun ask(session: AgySession, request: JsonElement, params: JsonObject, bypassing: Boolean) {
        val options = params["options"] as? JsonArray ?: JsonArray(emptyList())
        val call = params["toolCall"] as? JsonObject
        val card = call?.takeUnless { bypassing }?.let(turn::asking)
        val toolUseId = JsonScalars.str(call, "toolCallId")
        if (card == null || toolUseId == null) {
            session.answer(request, if (bypassing) optionOf(options, ALLOW) else null)
            return
        }
        val question = UUID.randomUUID().toString()
        asked[question] = Asked(request, options)
        out(frames.canUseTool(question, toolUseId, card))
    }

    /** Desktop's [response] to a question it was asked, answered to agy on [session]. */
    fun answered(session: AgySession?, response: JsonObject?) {
        val open = asked.remove(JsonScalars.str(response, "request_id")) ?: return
        val decision = response?.get("response") as? JsonObject
        val success = JsonScalars.str(response, "subtype") == "success"
        val allowed = success && JsonScalars.str(decision, "behavior") == "allow"
        session?.answer(open.request, optionOf(open.options, if (allowed) ALLOW else REJECT))
    }

    /** Every open question dropped: cancelled to agy on [session], withdrawn from Desktop. */
    fun dropped(session: AgySession?) {
        asked.forEach { (question, open) ->
            session?.answer(open.request, null)
            out(frames.cancelled(question))
        }
        asked.clear()
    }

    /** The option of [options] of the [kind] wanted (`allow` or `reject`), once before always; null for none. */
    private fun optionOf(options: JsonArray, kind: String): String? {
        val offered = options.mapNotNull { it as? JsonObject }
        val pick = offered.firstOrNull { JsonScalars.str(it, "kind") == "${kind}_once" }
            ?: offered.firstOrNull { JsonScalars.str(it, "kind")?.startsWith(kind) == true }
        return JsonScalars.str(pick, "optionId")
    }
}

private const val ALLOW = "allow"
private const val REJECT = "reject"
