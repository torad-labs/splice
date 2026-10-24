package splice.dialect.responses.reasoning

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.upstream.transport.BufferCapacity

/** Append-only textBuf owns prose. These views retain offsets, never a second mutable prose buffer. */
internal class ResponsesTextOwner(private val text: CharSequence) {
    private var cachedStart = 0
    private var cachedEnd = 0
    private var cachedText = ""
    val length: Int get() = text.length

    fun borrow(): ResponsesTextRanges = ResponsesTextRanges(this)
    fun charAt(index: Int): Char = text[index]

    /** A terminal harvest may replace textBuf; such model-only text never rebinds existing delivery ranges. */
    fun snapshot(current: CharSequence): String =
        if (current === text) materialize(0, text.length) else current.toString()

    fun materialize(start: Int, end: Int): String {
        if (start != cachedStart || end != cachedEnd) {
            cachedText = text.subSequence(start, end).toString()
            cachedStart = start
            cachedEnd = end
        }
        return cachedText
    }
}

internal class ResponsesTextRanges(private val owner: ResponsesTextOwner) {
    private val ranges = mutableListOf<TextRange>()
    var length: Int = 0
        private set

    fun append(count: Int) {
        require(count >= 0 && count <= owner.length)
        val end = owner.length
        val start = end - count
        val last = ranges.lastOrNull()
        if (last?.end == start) last.end = end else ranges.add(TextRange(start, end))
        length += count
    }

    fun matchesPrefix(model: String, offset: Int = 0): Boolean {
        if (length > model.length - offset) return false
        var at = offset
        for (range in ranges) {
            for (index in range.start until range.end) {
                if (owner.charAt(index) != model[at++]) return false
            }
        }
        return true
    }

    fun materialize(): String = when (ranges.size) {
        0 -> ""
        1 -> ranges.single().let { owner.materialize(it.start, it.end) }
        else -> buildString(length) {
            ranges.forEach { range ->
                for (index in range.start until range.end) append(owner.charAt(index))
            }
        }
    }
}

private data class TextRange(val start: Int, var end: Int)

/** Raw snapshots have their own bounded owner. Shared accounting adds only data outside textBuf's proven bytes. */
internal class ResponsesNativeOwnership(private val owner: ResponsesTextOwner) {
    private var sourceChars = 0L
    private val completing = mutableMapOf<Int, NativeMessageOwnership>()
    var extraChars: Long = 0
        private set

    fun retain(item: JsonObject, index: Int, observed: ResponsesTextRanges?) {
        val size = JsonWire.string(item).length.toLong()
        sourceChars += size
        var shared = 0
        if (JsonScalars.str(item, "type") == NATIVE_MESSAGE_TYPE) {
            val model = ResponsesMessageText.text(item)
            val proven = observed?.matchesPrefix(model) ?: true
            if (proven) shared = observed?.length ?: 0
            completing[index] = NativeMessageOwnership(item, shared, proven, owner.length)
        }
        extraChars += size - shared
        checkCapacity()
    }

    fun representedSuffix(index: Int, count: Int) {
        val message = completing[index] ?: return
        val ownerAdvanced = message.proven && owner.length == message.ownerEnd + count
        if (!ownerAdvanced) return
        val model = ResponsesMessageText.text(message.item)
        val represented = owner.borrow().also { it.append(count) }
        if (represented.matchesPrefix(model, message.shared)) {
            message.shared += count
            message.ownerEnd = owner.length
            extraChars -= count
        }
    }

    fun checkCapacity() {
        require(maxOf(sourceChars, extraChars) <= BufferCapacity.MAX_BUFFERED_CHARS) {
            "native history exceeds buffer capacity"
        }
    }
}

private data class NativeMessageOwnership(val item: JsonObject, var shared: Int, val proven: Boolean, var ownerEnd: Int)
