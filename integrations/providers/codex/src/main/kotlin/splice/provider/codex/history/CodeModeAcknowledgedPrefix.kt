package splice.provider.codex.history

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.perf.InputDigest
import splice.core.util.JsonScalars

/** Stateful cursor over the caller's display blocks; it never authors replacement model items. */
internal class CodeModeAcknowledgedPrefix(
    private val input: List<JsonElement>,
    private val clearedCallbacks: Set<String>,
) {
    private var cursor = CodeModeClientCursor(0)

    fun consume(items: List<CodeModeAcknowledgedItem>): CodeModeClientCursor? {
        val matched = items.withIndex().all { (index, item) ->
            val next = if (item.characters == null) ordinary(item) else text(item, index == items.lastIndex)
            next?.also { cursor = it } != null
        }
        return cursor.takeIf { matched }
    }

    private fun ordinary(item: CodeModeAcknowledgedItem): CodeModeClientCursor? {
        val actual = input.getOrNull(cursor.item) ?: return null
        val matched = cleared(item, actual as? JsonObject) || InputDigest.hex(actual) == item.digest
        return CodeModeClientCursor(cursor.item + 1).takeIf { matched }
    }

    private fun cleared(item: CodeModeAcknowledgedItem, actual: JsonObject?): Boolean {
        val authorized = item.callbackId != null && item.callbackId in clearedCallbacks
        val identity = JsonScalars.str(actual, "type") == "function_call_output" &&
            JsonScalars.str(actual, "call_id") == item.callbackId
        return authorized && identity && JsonScalars.str(actual, "output") == "[Old tool result content cleared]"
    }

    private fun text(item: CodeModeAcknowledgedItem, last: Boolean): CodeModeClientCursor? {
        val count = checkNotNull(item.characters)
        if (count == 0) return empty(item)
        val text = StringBuilder()
        var at = cursor.item
        var split = 0
        var reading = true
        while (text.length < count && reading) {
            val part = CodeModeClientAcknowledgements.assistantText(input.getOrNull(at))
            val remaining = count - text.length
            when {
                part == null -> reading = false
                part.length > remaining -> {
                    if (last) {
                        text.append(part.take(remaining))
                        split = remaining
                    }
                    reading = false
                }
                else -> {
                    text.append(part)
                    at++
                }
            }
        }
        return CodeModeClientCursor(at, split).takeIf { InputDigest.hex(text.toString()) == item.digest }
    }

    private fun empty(item: CodeModeAcknowledgedItem): CodeModeClientCursor? {
        val consumed = if (CodeModeClientAcknowledgements.assistantText(input.getOrNull(cursor.item)) == "") 1 else 0
        return CodeModeClientCursor(cursor.item + consumed).takeIf { InputDigest.hex("") == item.digest }
    }
}
