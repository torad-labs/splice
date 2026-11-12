package splice.provider.codex.stream.source

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText
import splice.provider.codex.history.CodeModeContinuityCapture

/** One range of the switching sink's bounded, actually delivered text; overlapping samples are idempotent. */
internal data class CodeModeDeliveredText(val offset: Int, val text: String?)

/** Buffered writes are not delivery. Only the confirmed prefix can promote completed messages to raw native items. */
internal class CodeModeSourceDelivery {
    private val delivered = StringBuilder()
    private var known = true

    fun accept(witness: CodeModeDeliveredText) {
        val text = witness.text
        if (text == null || witness.offset > delivered.length) {
            known = false
        } else if (known) {
            val overlap = (delivered.length - witness.offset).coerceIn(0, text.length)
            delivered.append(text, overlap, text.length)
        }
    }

    fun tail(before: String, native: List<JsonObject>?): List<JsonElement>? {
        val actual = if (delivered.startsWith(before)) delivered.substring(before.length) else ""
        var consumed = 0
        val kept = mutableListOf<JsonElement>()
        for (item in native.orEmpty()) {
            val prose = prose(item)
            when {
                prose == null -> kept += item
                actual.startsWith(prose, consumed) -> {
                    kept += item
                    consumed += prose.length
                }
                else -> return partial(
                    kept,
                    actual.substring(consumed).takeIf { prose.startsWith(it) }.orEmpty(),
                    native != null,
                )
            }
        }
        return partial(kept, actual.substring(consumed), native != null)
    }

    private fun partial(kept: MutableList<JsonElement>, rest: String, captured: Boolean): List<JsonElement>? {
        if (rest.isNotEmpty()) kept += ResponsesAssistantText.item(rest, AssistantPhase.COMMENTARY)
        return kept.takeIf { captured || it.isNotEmpty() }
    }

    private fun prose(item: JsonObject): String? =
        if (JsonScalars.str(item, "role") == "assistant") CodeModeContinuityCapture.text(listOf(item)) else null
}
