package splice.dialect.responses.reasoning

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText

/** Confirmed delivery order is independent of native output indexes and full backend snapshots. */
internal class ResponsesDeliveredHistory(private val owner: ResponsesTextOwner) {
    private val entries = mutableListOf<DeliveredNativeItem>()
    private val open = mutableMapOf<Int, DeliveredMessage>()
    private val fragmented = hashSetOf<Int>()
    private var prefixEnd: Int? = null
    private var verifiedEnd: Int? = null
    private var proven = true
    var callId: String? = null
        private set

    fun added(index: Int) {
        open.remove(index)
        fragmented.remove(index)
    }

    fun text(index: Int, text: String) {
        var entry = open[index]
        if (entry == null || entries.lastOrNull() !== entry) {
            if (entry != null) fragmented.add(index)
            entry = DeliveredMessage(owner.borrow()).also { entries.add(it); open[index] = it }
        }
        val released = if (index in fragmented) null else entry.completeSuffix(text)
        if (released != null) {
            open.remove(index)
            return
        }
        entry.append(text)
        if (index !in fragmented) promote(index, entry)
    }

    fun item(item: JsonObject, index: Int) {
        if (JsonScalars.str(item, "type") == NATIVE_MESSAGE_TYPE) {
            val entry = open.getOrPut(index) { DeliveredMessage(owner.borrow()).also { entries.add(it) } }
            entry.completed = item
            if (index !in fragmented) promote(index, entry)
        } else {
            open.remove(index)
            entries.add(DeliveredItem(item))
        }
    }

    fun call(item: JsonObject) {
        if (prefixEnd == null) {
            callId = JsonScalars.str(item, "call_id")
            prefixEnd = entries.size
        } else {
            entries.add(DeliveredItem(item))
        }
    }

    fun invalidate() {
        proven = false
        if (verifiedEnd == null) verifiedEnd = entries.size
    }

    fun recovery(): List<JsonObject>? = if (proven) snapshot(0, prefixEnd ?: entries.size) else null

    fun afterExec(): List<JsonObject>? = prefixEnd?.let { start ->
        snapshot(start, verifiedEnd ?: entries.size)
    }

    private fun snapshot(start: Int, end: Int): List<JsonObject> = entries.subList(
        start,
        maxOf(start, end),
    ).mapNotNull {
        it.snapshot()
    }

    private fun promote(index: Int, entry: DeliveredMessage) {
        val completed = entry.completed ?: return
        val text = ResponsesMessageText.text(completed)
        if (!entry.matchesPrefix(text)) {
            invalidate()
        } else if (entry.length == text.length) {
            entry.release(completed)
            open.remove(index)
        }
    }
}

private sealed interface DeliveredNativeItem {
    fun snapshot(): JsonObject?
}

private class DeliveredItem(private val item: JsonObject) : DeliveredNativeItem {
    override fun snapshot(): JsonObject = item
}

private class DeliveredMessage(private val body: ResponsesTextRanges) : DeliveredNativeItem {
    private var raw: JsonObject? = null
    private var actualLength = 0
    var completed: JsonObject? = null
    val length: Int get() = actualLength

    fun append(text: String) {
        body.append(text.length)
        actualLength += text.length
    }

    fun matchesPrefix(text: String): Boolean = body.matchesPrefix(text)

    fun release(item: JsonObject): Int {
        raw = item
        return body.length
    }

    fun completeSuffix(text: String): Int? {
        val item = completed ?: return null
        val model = ResponsesMessageText.text(item)
        val matches = actualLength + text.length == model.length && model.regionMatches(
            actualLength,
            text,
            0,
            text.length,
        )
        if (!matches) return null
        actualLength += text.length
        body.append(text.length)
        return release(item)
    }

    override fun snapshot(): JsonObject? = when {
        actualLength == 0 -> null
        raw != null -> raw
        else -> JsonObject(
            ResponsesAssistantText.item(body.materialize(), AssistantPhase.COMMENTARY) +
                ("type" to JsonPrimitive(NATIVE_MESSAGE_TYPE)),
        )
    }
}
