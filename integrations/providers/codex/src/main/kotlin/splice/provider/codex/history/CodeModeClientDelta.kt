package splice.provider.codex.history

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import splice.dialect.responses.request.ResponsesCodeModeInput
import splice.provider.codex.CodexCodeModeHistoryCodec

/** Advance an acknowledged client prefix. Replayed display items can only acknowledge retained native authority. */
internal class CodeModeClientDelta(private val codec: CodexCodeModeHistoryCodec) {
    fun after(
        entry: CodeModeLedgerEntry,
        conversation: List<JsonElement>,
        clearedCallbacks: Set<String> = emptySet(),
    ): List<JsonElement>? = after(entry, conversation, CodeModeCallbackProof(verified = clearedCallbacks))

    fun after(
        entry: CodeModeLedgerEntry,
        conversation: List<JsonElement>,
        proof: CodeModeCallbackProof,
    ): List<JsonElement>? {
        val normalized = normalized(entry, conversation, proof)
        // The ordinary request preserves its raw prefix. Re-proving it through display-block hashes would
        // encode every old item again; only regrouped or coalesced client histories need that projection.
        val appended = CodeModeWirePrefix.after(entry.covered, normalized)
        if (appended != null) return afterWirePrefix(entry, appended)
        val input = codec.projection.project(JsonArray(normalized))
        val covered = CodeModeClientAcknowledgements.capture(entry.covered)
        val cursor = covered.consume(input) ?: return null
        return if (unknownHistory(input, cursor, entry)) null else afterPrefix(entry, input, cursor)
    }

    fun normalized(
        entry: CodeModeLedgerEntry,
        conversation: List<JsonElement>,
        proof: CodeModeCallbackProof,
    ): List<JsonElement> = CodeModeClearedHistory.restore(conversation, entry, proof)

    private fun afterWirePrefix(entry: CodeModeLedgerEntry, appended: List<JsonElement>): List<JsonElement>? =
        if (appended.isEmpty()) {
            emptyList()
        } else {
            afterPrefix(entry, codec.projection.project(JsonArray(appended)), CodeModeClientCursor(0))
        }

    private fun afterPrefix(
        entry: CodeModeLedgerEntry,
        input: ResponsesCodeModeInput,
        cursor: CodeModeClientCursor,
    ): List<JsonElement>? {
        val tail = suffix(input, cursor, entry.covered + entry.sent)
        return if (tail.logicalItems.isEmpty() && tail.replayItems.isEmpty()) emptyList() else echoed(entry, tail)
    }

    private fun echoed(entry: CodeModeLedgerEntry, tail: ResponsesCodeModeInput): List<JsonElement>? {
        val expected = codec.projection.project(JsonArray(entry.echoes))
        val echoed = CodeModeClientAcknowledgements.capture(expected).consume(tail)
        val suppliedCopy = CodeModeClientAcknowledgements.assistantText(tail.logicalItems.firstOrNull()) != null
        if (echoed == null && suppliedCopy) return null
        val cursor = echoed ?: CodeModeClientCursor(0)
        return codec.projection.rebuild(suffix(tail, cursor, entry.sent + entry.echoes)).toList()
    }

    private fun unknownHistory(
        input: ResponsesCodeModeInput,
        cursor: CodeModeClientCursor,
        entry: CodeModeLedgerEntry,
    ): Boolean {
        val authority = entry.covered + entry.sent + entry.echoes
        val known = codec.projection.project(JsonArray(authority)).replayItems
            .flatMap { it.items }.mapTo(mutableSetOf(), CodeModeReasoningIdentity::key)
        return input.replayItems.any { segment ->
            segment.logicalOffset < cursor.item && segment.items.any { CodeModeReasoningIdentity.key(it) !in known }
        }
    }

    private fun suffix(
        input: ResponsesCodeModeInput,
        cursor: CodeModeClientCursor,
        authority: List<JsonElement>,
    ): ResponsesCodeModeInput {
        val logical = input.logicalItems.drop(cursor.item).toMutableList()
        if (cursor.characters > 0) {
            val first = logical.first() as JsonObject
            val text = checkNotNull(JsonScalars.strIfString(first["content"]))
            logical[0] = JsonObject(first + ("content" to JsonPrimitive(text.drop(cursor.characters))))
        }
        val known = codec.projection.project(JsonArray(authority)).replayItems.flatMap { it.items }
            .mapTo(mutableSetOf(), CodeModeReasoningIdentity::key)
        val replay = input.replayItems.filter { it.logicalOffset >= cursor.item }.mapNotNull { segment ->
            val unseen = segment.items.filter { CodeModeReasoningIdentity.key(it) !in known }
            segment.copy(logicalOffset = segment.logicalOffset - cursor.item, items = unseen)
                .takeIf { it.items.isNotEmpty() }
        }
        return ResponsesCodeModeInput(logical, replay)
    }
}

/** Compare the same JSON spelling without allocating encoded copies; object field order still matters. */
private object CodeModeWirePrefix {
    fun after(prefix: List<JsonElement>, items: List<JsonElement>): List<JsonElement>? {
        if (items.size < prefix.size) return null
        if (prefix.indices.any { !same(prefix[it], items[it]) }) return null
        return items.subList(prefix.size, items.size)
    }

    private fun same(expected: JsonElement, actual: JsonElement): Boolean = when {
        expected === actual -> true
        expected is JsonObject && actual is JsonObject -> objects(expected, actual)
        expected is JsonArray && actual is JsonArray ->
            expected.size == actual.size &&
                expected.indices.all { same(expected[it], actual[it]) }
        else -> expected == actual
    }

    private fun objects(expected: JsonObject, actual: JsonObject): Boolean {
        if (expected.size != actual.size) return false
        val received = actual.entries.iterator()
        return expected.entries.all { before ->
            val item = received.next()
            before.key == item.key && same(before.value, item.value)
        }
    }
}
