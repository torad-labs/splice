package splice.dialect.responses.reasoning

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText

private const val ASSISTANT_PHASE_KEY = "phase"

/** Confirmed delivery order is independent of native output indexes and full backend snapshots. */
internal class ResponsesDeliveredHistory(private val owner: ResponsesTextOwner) {
    private val entries = mutableListOf<DeliveredNativeItem>()
    private val open = mutableMapOf<Int, DeliveredMessage>()
    private val announced = mutableMapOf<Int, MessagePhaseSource>()
    private val fragmented = hashSetOf<Int>()
    private var prefixEnd: Int? = null
    private var verifiedEnd: Int? = null
    private var proven = true

    /** The calls [call] already placed. The fold reports each finished call item to [item] as well, after [call]. */
    private val called = hashSetOf<String>()
    var callId: String? = null
        private set

    fun added(index: Int, item: JsonObject? = null) {
        open.remove(index)
        fragmented.remove(index)
        announced.remove(index)
        if (JsonScalars.str(item, "type") == NATIVE_MESSAGE_TYPE) announced[index] = source(checkNotNull(item))
    }

    fun text(index: Int, text: String) {
        var entry = open[index]
        if (entry == null || entries.lastOrNull() !== entry) {
            if (entry != null) fragmented.add(index)
            entry = DeliveredMessage(owner.borrow(), announced[index]).also { entries.add(it); open[index] = it }
        }
        val released = if (index in fragmented) null else entry.completeSuffix(text)
        if (released != null) {
            open.remove(index)
            announced.remove(index)
            return
        }
        entry.append(text)
        if (index !in fragmented) promote(index, entry)
    }

    fun item(item: JsonObject, index: Int) {
        if (JsonScalars.str(item, "type") == NATIVE_MESSAGE_TYPE) {
            val entry = open.getOrPut(index) {
                DeliveredMessage(owner.borrow(), announced[index]).also { entries.add(it) }
            }
            entry.identify(item)
            entry.completed = item
            if (index !in fragmented) promote(index, entry)
        } else {
            open.remove(index)
            announced.remove(index)
            // A call that sealed the prefix, or that [call] already added after it, was delivered once.
            if (JsonScalars.str(item, "call_id")?.let(called::contains) == true) return
            entries.add(DeliveredItem(item))
        }
    }

    fun call(item: JsonObject) {
        JsonScalars.str(item, "call_id")?.let(called::add)
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

    /** Only upstream phases qualify as provenance. Unknown unfinished text keeps no invented phase here. */
    fun phaseHistory(sources: List<JsonObject>): List<JsonObject>? {
        if (!proven) return null
        val identified = sources.mapNotNull { item -> JsonScalars.str(item, "id")?.let { it to item } }.toMap()
        return entries.mapNotNull { entry ->
            if (entry is DeliveredMessage) entry.id?.let(identified::get)?.let(entry::identify)
            entry.snapshot(provenanceOnly = true)
        }
    }

    fun afterExec(): List<JsonObject>? = prefixEnd?.let { start ->
        snapshot(start, verifiedEnd ?: entries.size)
    }

    /** A truncated or conflicting message cannot establish positions for newly harvested reasoning. */
    fun terminalTail(): List<JsonObject>? = if (proven) afterExec() else null

    fun completeTail(items: List<JsonObject>) {
        val start = prefixEnd ?: return
        if (!proven) return
        entries.subList(start, entries.size).clear()
        entries.addAll(items.map(::DeliveredItem))
        open.clear()
        announced.clear()
        fragmented.clear()
    }

    private fun snapshot(start: Int, end: Int): List<JsonObject> = entries.subList(
        start,
        maxOf(start, end),
    ).mapNotNull {
        it.snapshot()
    }

    private fun source(item: JsonObject): MessagePhaseSource = MessagePhaseSource(
        JsonScalars.str(item, "id"),
        AssistantPhase.entries.firstOrNull { it.wire == JsonScalars.str(item, ASSISTANT_PHASE_KEY) },
    )

    private fun promote(index: Int, entry: DeliveredMessage) {
        val completed = entry.completed ?: return
        val text = ResponsesMessageText.text(completed)
        if (!entry.matchesPrefix(text)) {
            invalidate()
        } else if (entry.length == text.length) {
            entry.release(completed)
            open.remove(index)
            announced.remove(index)
        }
    }
}

private data class MessagePhaseSource(val id: String?, val phase: AssistantPhase?)

private sealed interface DeliveredNativeItem {
    fun snapshot(provenanceOnly: Boolean = false): JsonObject?
}

private class DeliveredItem(private val item: JsonObject) : DeliveredNativeItem {
    override fun snapshot(provenanceOnly: Boolean): JsonObject = item
}

private class DeliveredMessage(
    private val body: ResponsesTextRanges,
    source: MessagePhaseSource?,
) : DeliveredNativeItem {
    private var raw: JsonObject? = null
    private var actualLength = 0
    private var phase = source?.phase
    var id: String? = source?.id
        private set
    var completed: JsonObject? = null
    val length: Int get() = actualLength

    fun identify(item: JsonObject) {
        id = JsonScalars.str(item, "id") ?: id
        phase = AssistantPhase.entries.firstOrNull { it.wire == JsonScalars.str(item, ASSISTANT_PHASE_KEY) } ?: phase
    }

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

    override fun snapshot(provenanceOnly: Boolean): JsonObject? {
        if (actualLength == 0) return null
        val original = raw
        if (original != null) {
            val known = phase
            return if (known != null && JsonScalars.str(original, ASSISTANT_PHASE_KEY) == null) {
                JsonObject(original + (ASSISTANT_PHASE_KEY to JsonPrimitive(known.wire)))
            } else {
                original
            }
        }
        return JsonObject(
            ResponsesAssistantText.item(
                body.materialize(),
                phase ?: AssistantPhase.COMMENTARY.takeUnless { provenanceOnly },
            ) + ("type" to JsonPrimitive(NATIVE_MESSAGE_TYPE)),
        )
    }
}
