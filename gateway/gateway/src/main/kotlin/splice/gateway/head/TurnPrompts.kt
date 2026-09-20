// NEW: V4-160 — the per-turn system-prompt layers and the team-slot text, moved verbatim out of
// TurnPreparation.kt (concentration, 2026-09-18). TurnPreparation.kt's header states the V4-131 rule;
// the order stays its call: the head's layers first, then the slot text after them.
package splice.gateway.head

import splice.core.perf.TurnPerf
import splice.core.prompt.SLOT_PROMPT_CHANGED
import splice.core.prompt.SystemPromptMode
import splice.spi.BuiltTurn
import splice.spi.Provider

internal class TurnPrompts(private val provider: Provider, private val deps: HeadDeps) {

    /** V4-131: the session's team-slot text, after the head's layers (see the header). Same honesty
     *  rule as the layers: a dialect that could not place it leaves the body alone and the meta says
     *  "(not applied)" rather than claiming text the wire never carried. */
    fun applySlotPrompt(turn: BuiltTurn, sessionId: String?, perf: TurnPerf): BuiltTurn {
        val slots = deps.seams.slotInstructions
        if (slots == null || sessionId == null) return turn
        val prompt = slots.forSession(sessionId)
        if (slots.changed(sessionId, prompt)) perf.setCount(SLOT_PROMPT_CHANGED, 1L)
        if (prompt == null) return turn
        val next = provider.withSystemPrompt(turn, prompt.text, SystemPromptMode.APPEND)
        val placed = next.requestBody != turn.requestBody
        val joined = listOfNotNull(turn.meta.systemPrompt, prompt.text).joinToString("\n\n")
        val text = if (placed) joined else turn.meta.systemPrompt
        val label = if (placed) prompt.source else "${prompt.source} (not applied)"
        val source = listOfNotNull(turn.meta.systemPromptSource, label).joinToString("+")
        return next.copy(meta = next.meta.copy(systemPrompt = text, systemPromptSource = source))
    }

    /** The standing prompt layers ride on EVERY turn (not only compact ones), at the dialect's seam,
     *  one layer after another in the order [splice.core.prompt.SystemPromptLayers] resolved them.
     *  Same honesty rule as the compaction tail above: a dialect that could not place a layer
     *  returns the request as it was, and the meta then says so for that layer instead of claiming
     *  text the wire never carried. The session lookup runs only when a project is configured, so a
     *  topology without projects does no extra work and sends V4-36's bytes. */
    fun applySystemPrompt(turn: BuiltTurn, sessionId: String?): BuiltTurn {
        val layers = deps.policy.systemPrompt
        val cwd = if (layers.hasProjects) deps.seams.sessionProject(sessionId) else null
        val resolved = layers.resolve(cwd)
        if (resolved.isEmpty()) return turn
        val placed = BooleanArray(resolved.size)
        val prompted = resolved.foldIndexed(turn) { index, current, layer ->
            val next = provider.withSystemPrompt(current, layer.text, layer.mode)
            placed[index] = next.requestBody != current.requestBody
            next
        }
        // V4-170: a strip layer's text is its pattern list, never prompt text the wire carried.
        val applied = resolved.filterIndexed { index, layer -> placed[index] && layer.mode != SystemPromptMode.STRIP }
        val source = resolved.withIndex().joinToString("+") { (index, layer) ->
            if (placed[index]) layer.source else "${layer.source} (not applied)"
        }
        return prompted.copy(
            meta = prompted.meta.copy(
                systemPrompt = applied.takeIf { it.isNotEmpty() }?.joinToString("\n\n") { it.text },
                systemPromptSource = source,
            ),
        )
    }
}
