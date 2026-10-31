// NEW: projects the native source prefix without losing reasoning positions or message boundaries.
package splice.provider.codex.history

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars
import splice.dialect.responses.reasoning.ResponsesMessageText
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText
import splice.dialect.responses.request.ResponsesCodeModeProjection
import splice.provider.codex.CodeModeContinuity
import splice.provider.codex.CodeModeNativeSegment

internal object CodeModeContinuityCapture {
    fun success(outcome: TurnOutcome.Success): CodeModeContinuity = project(successItems(outcome)).copy(
        nativeAfterExec = outcome.handoffs.nativeAfterExec,
    )

    fun successItems(outcome: TurnOutcome.Success): List<JsonObject> = items(
        outcome.handoffs.nativePrefix,
        outcome.text.bodyText.takeIf { outcome.text.emittedText }.orEmpty(),
        outcome.handoffs.reasoningEnvelopes,
    )

    fun partialItems(partial: TurnOutcome.PartialRound): List<JsonObject> = items(
        partial.nativePrefix,
        partial.text.bodyText.takeIf { partial.text.emittedText }.orEmpty(),
        partial.reasoningEnvelopes,
    )

    /** Null keeps the legacy receipt's fallback; an empty prefix authoritatively contains no source history. */
    fun items(native: List<JsonObject>?, text: String, envelopes: List<String>): List<JsonObject> =
        native?.mapNotNull(::nativeItem) ?: buildList {
            addAll(envelopes.mapNotNull(ReasoningReplay::decodeReasoningEnvelope))
            if (text.isNotEmpty()) add(ResponsesAssistantText.item(text, AssistantPhase.COMMENTARY))
        }

    fun project(items: List<JsonObject>): CodeModeContinuity {
        val projected = ResponsesCodeModeProjection().project(JsonArray(items))
        return CodeModeContinuity(
            projected.logicalItems,
            projected.replayItems.map { CodeModeNativeSegment(it.logicalOffset, it.items) },
        )
    }

    /** Only assistant prose contributes to the visible echo; raw native payloads remain untouched. */
    fun text(items: List<JsonElement>): String = items.mapNotNull { it as? JsonObject }.joinToString("") { item ->
        if (JsonScalars.str(item, "role") == "assistant") messageText(item) else ""
    }

    fun followedBy(prior: TurnOutcome.Success, latest: TurnOutcome.Success): List<JsonObject>? =
        if (prior.handoffs.nativePrefix == null && latest.handoffs.nativePrefix == null) {
            null
        } else {
            successItems(prior) + successItems(latest)
        }

    fun followedBy(prior: TurnOutcome.Success, latest: TurnOutcome.PartialRound?): List<JsonObject>? =
        if (prior.handoffs.nativePrefix == null && latest?.nativePrefix == null) {
            null
        } else {
            successItems(prior) + latest?.let(::partialItems).orEmpty()
        }

    private fun nativeItem(item: JsonObject): JsonObject? {
        if (JsonScalars.str(item, "type") == "reasoning") return item
        if (JsonScalars.str(item, "role") != "assistant") return null
        return messageText(item).takeIf(String::isNotEmpty)?.let {
            ResponsesAssistantText.item(it, AssistantPhase.COMMENTARY)
        }
    }

    private fun messageText(item: JsonObject): String {
        val content = item["content"]
        if (content is JsonPrimitive && !content.isString) return ""
        return ResponsesMessageText.text(item)
    }
}
