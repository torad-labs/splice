// NEW: the JSON an out-of-process hook answers in (modes/README.md, "Hooks"): `{"answer": "pass"}`, `context`, `model`,
// `refuse`, `rewrite` (with `body`), `replace` or `continue` (with `text`). A hook may answer only for its own moment.
package splice.harness.hook

import kotlinx.serialization.json.JsonObject
import splice.core.parse.lenientJson
import splice.core.util.JsonScalars

internal class HookWire {
    /** The answer [text] holds, an unreadable or unknown answer being [HookAnswer.Pass]. */
    fun answer(text: String, mode: String): HookAnswer {
        val obj = JsonScalars.objectOrNull(lenientJson, text.trim()) ?: return HookAnswer.Pass
        val name = JsonScalars.str(obj, HOOK_ANSWER_KEY) ?: return HookAnswer.Pass
        return read(name, obj, mode)
    }

    /** [answer] when its moment takes it, else null. */
    fun valid(answer: HookAnswer, moment: HookMoment): HookAnswer? = answer.takeIf { moment in moments(it) }

    private fun read(name: String, obj: JsonObject, mode: String): HookAnswer = when (name) {
        "context" -> HookAnswer.AddContext(JsonScalars.str(obj, HOOK_TEXT_KEY).orEmpty())
        "model" -> HookAnswer.ChooseModel(
            JsonScalars.str(obj, HOOK_MODEL_KEY) ?: JsonScalars.str(obj, HOOK_TEXT_KEY).orEmpty(),
        )
        "refuse" -> reason(obj, mode)
        "rewrite" -> rewritten(obj)
        "replace" -> HookAnswer.Replace(JsonScalars.str(obj, HOOK_TEXT_KEY).orEmpty())
        "continue" -> HookAnswer.Continue(JsonScalars.str(obj, HOOK_TEXT_KEY).orEmpty())
        else -> HookAnswer.Pass
    }

    private fun reason(obj: JsonObject, mode: String): HookAnswer {
        val reason = JsonScalars.str(obj, HOOK_REASON_KEY) ?: JsonScalars.str(obj, HOOK_TEXT_KEY)
        return HookAnswer.Refuse(reason ?: "mode $mode refused the turn")
    }

    private fun rewritten(obj: JsonObject): HookAnswer =
        (obj[HOOK_BODY_KEY] as? JsonObject)?.let(HookAnswer::Rewrite) ?: HookAnswer.Pass

    /** The moments each answer is taken at (modes/README.md, "Hooks"): a hook may answer for its own moment only. */
    private fun moments(answer: HookAnswer): Set<HookMoment> = when (answer) {
        HookAnswer.Pass, is HookAnswer.Refuse -> HookMoment.entries.toSet()
        is HookAnswer.AddContext, is HookAnswer.ChooseModel -> setOf(HookMoment.SESSION_START)
        is HookAnswer.Rewrite -> setOf(HookMoment.BEFORE_TOOL_CALL, HookMoment.REQUEST, HookMoment.RESPONSE)
        is HookAnswer.Replace -> setOf(HookMoment.AFTER_TOOL_CALL, HookMoment.ON_STOP)
        is HookAnswer.Continue -> setOf(HookMoment.ON_STOP)
    }
}

private const val HOOK_ANSWER_KEY = "answer"
private const val HOOK_TEXT_KEY = "text"
internal const val HOOK_MODEL_KEY = "model"
private const val HOOK_REASON_KEY = "reason"
private const val HOOK_BODY_KEY = "body"
