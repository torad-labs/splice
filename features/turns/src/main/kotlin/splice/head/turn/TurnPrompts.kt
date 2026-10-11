// NEW: V4-160 — the per-turn system-prompt layers and the team-slot text, moved verbatim out of
// TurnPreparation.kt (concentration, 2026-09-18). TurnPreparation.kt's header states the V4-131 rule;
// the order stays its call: the head's layers first, then the slot text after them.
package splice.head.turn

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.perf.TurnPerf
import splice.core.prompt.EffectiveSystemPrompt
import splice.core.prompt.SYSTEM_PROMPT_APPLIED
import splice.core.prompt.SYSTEM_PROMPT_CWD_UNRESOLVED
import splice.core.prompt.SYSTEM_PROMPT_LAYERS
import splice.core.prompt.SystemPromptMode
import splice.core.turn.TurnSystemPrompt
import splice.head.HeadDeps
import splice.sessions.prompt.SLOT_PROMPT_CHANGED
import splice.upstream.BuiltTurn
import splice.upstream.Provider

internal class TurnPrompts(private val provider: Provider, private val deps: HeadDeps) {

    /** V4-131: the session's team-slot text, after the head's layers (see the header). Same honesty
     *  rule as the layers: a dialect that could not place it leaves the body alone and the meta says
     *  "(not applied)" rather than claiming text the wire never carried. */
    fun applySlotPrompt(turn: BuiltTurn, sessionId: String?, perf: TurnPerf): BuiltTurn {
        val slots = deps.seams.session.slotInstructions
        if (slots == null || sessionId == null) return turn
        val prompt = slots.forSession(sessionId)
        if (slots.changed(sessionId, prompt)) perf.setCount(SLOT_PROMPT_CHANGED, 1L)
        if (prompt == null) return turn
        val next = provider.withSystemPrompt(turn, prompt.text, SystemPromptMode.APPEND)
        val placed = next.requestBody != turn.requestBody
        val joined = listOfNotNull(turn.meta.standingPrompt.text, prompt.text).joinToString("\n\n")
        val text = if (placed) joined else turn.meta.standingPrompt.text
        val label = if (placed) prompt.source else "${prompt.source} (not applied)"
        val source = listOfNotNull(turn.meta.standingPrompt.source, label).joinToString("+")
        return next.copy(meta = next.meta.copy(standingPrompt = TurnSystemPrompt(text = text, source = source)))
    }

    /** The standing prompt layers ride on EVERY turn (not only compact ones), at the dialect's seam,
     *  one layer after another in the order [splice.core.prompt.SystemPromptLayers] resolved them.
     *  Same honesty rule as the compaction tail above: a dialect that could not place a layer
     *  returns the request as it was, and the meta then says so for that layer instead of claiming
     *  text the wire never carried. The session lookup runs only when a project is configured, so a
     *  topology without projects does no extra work and sends V4-36's bytes.
     *
     *  V4-172, TWO REPAIRS. (1) The turn's perf row now carries how many layers were CONFIGURED and
     *  how many changed the bytes: `systemPromptSource` had no production reader, so a seam's early
     *  return and a strip pattern gone stale on a client upgrade were equally invisible. A row
     *  reading `system_prompt_layers=1 system_prompt_applied=0` is the false landing, in one grep.
     *  (2) "Placed" is re-measured against the FINAL body whenever a strip layer is in the fold,
     *  because a strip edits every system block present at its point — including one an earlier
     *  append layer just added. Measured per layer as it was applied, the meta reported that append
     *  as carried while the wire no longer had it. */
    fun applySystemPrompt(turn: BuiltTurn, sessionId: String?, perf: TurnPerf): BuiltTurn {
        val layers = deps.policy.systemPrompt
        val cwd = if (layers.hasProjects) deps.seams.session.sessionProject(sessionId) else null
        if (layers.hasProjects && cwd == null) perf.setCount(SYSTEM_PROMPT_CWD_UNRESOLVED, 1L)
        val resolved = layers.resolve(cwd)
        if (resolved.isEmpty()) return turn
        val placed = BooleanArray(resolved.size)
        val prompted = resolved.foldIndexed(turn) { index, current, layer ->
            val next = provider.withSystemPrompt(current, layer.text, layer.mode)
            placed[index] = next.requestBody != current.requestBody
            next
        }
        survivedFinalBody(resolved, placed, prompted)
        perf.setCount(SYSTEM_PROMPT_LAYERS, resolved.size.toLong())
        perf.setCount(SYSTEM_PROMPT_APPLIED, placed.count { it }.toLong())
        val applied = resolved.filterIndexed { index, layer -> placed[index] && layer.mode != SystemPromptMode.STRIP }
        val source = resolved.withIndex().joinToString("+") { (index, layer) ->
            if (placed[index]) layer.source else "${layer.source} (not applied)"
        }
        return prompted.copy(
            meta = prompted.meta.copy(
                standingPrompt = TurnSystemPrompt(
                    text = applied.takeIf { it.isNotEmpty() }?.joinToString("\n\n") { it.text },
                    source = source,
                ),
            ),
        )
    }

    /** Whether [layer]'s own text is still in the finished [body]. Only an APPEND layer's text can be
     *  looked for there: a replace layer's text IS the field, and a strip layer's text is a pattern
     *  list that was never on the wire — neither can be missing, so neither is ever un-placed.
     *  [body] is the request TREE and the text is looked for raw, which is why there is no escape step
     *  here any more: a string primitive's content is unescaped, so a multi-line append's real newline
     *  matches the newline the body carries. Searching the SERIALISED request instead read every
     *  multi-line append as deleted until its needle was escaped too (v0.4.0 prompt-review), and it
     *  cost one body-sized String per round on any head configured with a strip layer. */
    private fun survived(layer: EffectiveSystemPrompt, body: JsonElement): Boolean =
        layer.mode != SystemPromptMode.APPEND || PromptSurvival.present(body, layer.text)

    /** A strip layer can delete text an earlier APPEND layer placed, so an append whose text is no
     *  longer in the finished body did not reach the wire whatever the fold measured at the time.
     *  Only runs when a strip layer is present — no strip, nothing can have removed anything. */
    private fun survivedFinalBody(layers: List<EffectiveSystemPrompt>, placed: BooleanArray, turn: BuiltTurn) {
        if (layers.none { it.mode == SystemPromptMode.STRIP }) return
        layers.forEachIndexed { index, layer ->
            if (placed[index]) placed[index] = survived(layer, turn.requestBody)
        }
    }
}

/** Whether a system-prompt layer's text is still in the finished request, asked of the TREE. It lives
 *  beside TurnPrompts, its one caller, and is internal so features/turns' tests can pin parity and a
 *  mutation against it (PromptSurvivalTest).
 *
 *  It replaced serialising the whole request body to a String to run `contains` over it, which cost one
 *  body-sized String per round on any head configured with a strip layer: 2,259,840 bytes against
 *  110,144 on a 1.05 MB body. Nothing about the answer reaches the wire. Asking the tree also retired a
 *  class of mismatch: against a serialised body the needle had to be escaped too, or a multi-line
 *  append's real newline never matched the `\n` the serialiser wrote (v0.4.0 prompt-review). A string
 *  primitive's content is already unescaped, so raw matches raw. */
internal object PromptSurvival {
    /** True when some string value anywhere in [body] contains [text]. Walks the tree and copies no
     *  value: a primitive's content is the stored String. A key or a number never matches. */
    fun present(body: JsonElement, text: String): Boolean = when (body) {
        is JsonPrimitive -> body.isString && body.content.contains(text)
        is JsonObject -> body.values.any { present(it, text) }
        is JsonArray -> body.any { present(it, text) }
    }
}
