package splice.dialect.responses.reasoning

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.turn.GatewayCustomCall
import splice.core.util.JsonScalars
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText

internal const val NATIVE_MESSAGE_TYPE = "message"

/** Completed native items retain their message boundaries independently of flattened display buffers. */
internal class ResponsesNativePrefix(body: CharSequence) {
    val textOwner = ResponsesTextOwner(body)
    val ownership = ResponsesNativeOwnership(textOwner)
    val delivered = ResponsesDeliveredHistory(textOwner)
    private val terminalTail = ResponsesNativeTail()
    private val items = mutableListOf<NativePrefixItem>()
    private val completedIds = hashSetOf<String>()
    private val pending = sortedMapOf<Int, ResponsesTextRanges>()
    private var sealed: List<JsonObject>? = null
    private var boundary: Int? = null

    /** Additional native data outside textBuf's proven contents; raw snapshots retain their own bounded owner. */
    val retainedChars: Long get() = ownership.extraChars

    fun delta(index: Int, text: String) {
        bufferedDelta(index, text)
        delivered.text(index, text)
        checkCapacity()
    }

    fun bufferedDelta(index: Int, text: String) {
        pending.getOrPut(index, textOwner::borrow).append(text.length)
        checkCapacity()
    }

    /** Keep complete upstream snapshots separately from the text the client actually received. */
    fun collect(item: JsonObject, index: Int): String? {
        if (!replayable(item)) return null
        val isMessage = JsonScalars.str(item, "type") == NATIVE_MESSAGE_TYPE
        val streamed = pending.remove(index)
        val streamedLength = streamed?.length ?: 0
        val captured = when {
            !isMessage -> {
                // Reusing an index starts another item; the preceding unfinished message remains history.
                if (streamedLength != 0) retain(message(checkNotNull(streamed).materialize()), index, streamed)
                item
            }
            ResponsesMessageText.text(item).isEmpty() && streamedLength != 0 ->
                JsonObject(item + ("content" to checkNotNull(message(checkNotNull(streamed).materialize())["content"])))
            else -> item
        }
        val fresh = retain(captured, index, streamed)
        if (fresh) delivered.item(captured, index)
        checkCapacity()
        return knownSuffix(captured, streamed, fresh)
    }

    private fun retain(item: JsonObject, index: Int, observed: ResponsesTextRanges? = null): Boolean {
        val id = JsonScalars.str(item, "id")
        if (id != null && !completedIds.add(id)) return false
        ownership.retain(item, index, observed)
        checkCapacity()
        items.add(NativePrefixItem(index, item))
        return true
    }

    private fun knownSuffix(item: JsonObject, streamed: ResponsesTextRanges?, fresh: Boolean): String? {
        if (JsonScalars.str(item, "type") != NATIVE_MESSAGE_TYPE) return null
        if (!fresh) return ""
        val completed = ResponsesMessageText.text(item)
        val matches = streamed?.matchesPrefix(completed) ?: true
        return completed.takeIf { matches }?.substring(streamed?.length ?: 0)
    }

    fun seal(index: Int?, call: GatewayCustomCall) {
        if (boundary == null) {
            val lastCompleted = items.maxOfOrNull { it.index }
            val lastPending = pending.keys.lastOrNull()
            val last = listOfNotNull(lastCompleted, lastPending).maxOrNull()
            boundary = index ?: last?.plus(1) ?: 0
            sealed = snapshot()
            terminalTail.seal(call.raw)
        }
        delivered.call(call.raw)
    }

    /** A sparse terminal cannot remove or permute an item already captured before the call sealed. */
    fun finish(output: JsonArray) {
        val complete = output.mapNotNull { it as? JsonObject }
        val callAt = complete.indexOfFirst { JsonScalars.str(it, "type") == "custom_tool_call" }
        if (callAt < 0) return
        val terminal = complete.take(callAt).filter(::replayable)
        val before = sealed ?: snapshot()
        val known = before.associateBy(::identity)
        val anonymous = anonymousMatches(before, terminal)
        val matched = terminal.mapNotNull { known[identity(it)] ?: anonymous[it] }
        if (matched == before) {
            val reconciled = terminal.map { candidate -> known[identity(candidate)] ?: candidate }
            val added = terminal.filter { identity(it) !in known }
            added.forEachIndexed { index, item -> ownership.retain(item, index, null) }
            checkCapacity()
            boundary = boundary ?: callAt
            sealed = reconciled
        }
        terminalTail.reconcile(complete, delivered.terminalTail(), snapshot())?.let { update ->
            update.added.forEach { (index, item) -> retain(item, index) }
            delivered.completeTail(update.items)
        }
    }

    /** Exact text is an anchor only when unique on both sides; known anchors must retain their order. */
    private fun anonymousMatches(before: List<JsonObject>, terminal: List<JsonObject>): Map<JsonObject, JsonObject> {
        val anonymous = before.filter { JsonScalars.str(it, "id") == null }.groupBy(::messageKey)
        val candidates = terminal.groupBy(::messageKey)
        return anonymous.filterKeys { it != null }.filterValues { it.size == 1 }.mapNotNull { (key, entries) ->
            candidates[key]?.singleOrNull()?.let { it to entries.single() }
        }.toMap()
    }

    private fun messageKey(item: JsonObject): String? =
        if (JsonScalars.str(item, "type") == NATIVE_MESSAGE_TYPE && JsonScalars.str(item, "role") == "assistant") {
            ResponsesMessageText.text(item).takeIf { it.isNotEmpty() }
        } else {
            null
        }

    fun snapshot(): List<JsonObject> = sealed ?: buildList {
        val end = boundary ?: Int.MAX_VALUE
        val completed = items.filter { it.index < end }
        val unfinished = pending.filterKeys {
            it < end
        }.map { (index, text) -> NativePrefixItem(index, message(text.materialize())) }
        addAll((completed + unfinished).sortedBy { it.index }.map { it.item })
    }

    private fun identity(item: JsonObject): JsonElement = item["id"] ?: item

    private fun checkCapacity() {
        ownership.checkCapacity()
    }

    private fun replayable(item: JsonObject): Boolean = when (JsonScalars.str(item, "type")) {
        NATIVE_MESSAGE_TYPE -> true
        "reasoning" -> !JsonScalars.str(item, "encrypted_content").isNullOrEmpty()
        else -> false
    }

    private fun message(text: String): JsonObject = JsonObject(
        ResponsesAssistantText.item(text, AssistantPhase.COMMENTARY) + ("type" to JsonPrimitive(NATIVE_MESSAGE_TYPE)),
    )
}

private data class NativePrefixItem(val index: Int, val item: JsonObject)
