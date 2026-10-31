package splice.upstream.codemode

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

// Codex's codex-utils-string approximation: one token for every four UTF-8 bytes, rounded up.
// why: codex-utils-string approximates one token per four UTF-8 bytes, rounding token counts upward.
private const val NATIVE_BYTES_PER_TOKEN: Long = 4

// why: UTF-8 continuation bytes have the two most significant bits 10; isolate those bits.
private const val UTF8_CONTINUATION_MASK: Int = 0xc0

// why: The UTF-8 continuation-byte prefix is binary 10, so middle truncation never cuts a code point.
private const val UTF8_CONTINUATION_TAG: Int = 0x80

/** Ports Codex output-truncation and UTF-8 middle truncation, retaining media order and omission markers. */
internal object NativeCodeModeTruncation {
    public fun tokens(text: String): Long =
        (text.encodeToByteArray().size.toLong() + NATIVE_BYTES_PER_TOKEN - 1) / NATIVE_BYTES_PER_TOKEN

    public fun content(items: List<JsonObject>, maxTokens: Int): List<JsonObject> {
        require(maxTokens >= 0) { "Native output token budget must be nonnegative" }
        return if (items.all { JsonScalars.str(it, "type") == "input_text" }) {
            textItems(items, maxTokens)
        } else {
            mixedItems(items, maxTokens)
        }
    }

    public fun text(text: String, maxTokens: Int): String {
        require(maxTokens >= 0) { "Native output token budget must be nonnegative" }
        val bytes = text.encodeToByteArray()
        val budget = maxTokens.toLong() * NATIVE_BYTES_PER_TOKEN
        if (bytes.size <= budget || bytes.isEmpty()) return text
        val leftBudget = (budget / 2).toInt()
        val rightBudget = (budget - leftBudget).toInt()
        var prefixEnd = leftBudget
        while (prefixEnd > 0 && continuation(bytes[prefixEnd])) prefixEnd--
        var suffixStart = bytes.size - rightBudget
        while (suffixStart < bytes.size && continuation(bytes[suffixStart])) suffixStart++
        val removed = (bytes.size - budget + NATIVE_BYTES_PER_TOKEN - 1) / NATIVE_BYTES_PER_TOKEN
        val marker = "…$removed tokens truncated…"
        return bytes.decodeToString(0, prefixEnd) + marker + bytes.decodeToString(suffixStart, bytes.size)
    }

    private fun continuation(byte: Byte): Boolean = byte.toInt() and UTF8_CONTINUATION_MASK == UTF8_CONTINUATION_TAG

    private fun textItems(items: List<JsonObject>, maxTokens: Int): List<JsonObject> {
        val combined = StringBuilder()
        items.forEach { item ->
            if (combined.isNotEmpty()) combined.append('\n')
            combined.append(JsonScalars.str(item, "text").orEmpty())
        }
        val value = combined.toString()
        if (value.encodeToByteArray().size.toLong() <= maxTokens.toLong() * NATIVE_BYTES_PER_TOKEN) return items
        val lines = if (value.isEmpty()) 0 else value.count { it == '\n' } + if (value.endsWith('\n')) 0 else 1
        val warning = "Warning: truncated output (original token count: ${tokens(value)})\n" +
            "Total output lines: $lines\n\n"
        return listOf(NativeCodeModeOutput.textItem(warning + text(value, maxTokens)))
    }

    private fun mixedItems(items: List<JsonObject>, maxTokens: Int): List<JsonObject> {
        val budget = MixedBudget(maxTokens.toLong())
        items.forEach { item -> budget.append(item) }
        return budget.finish()
    }

    private class MixedBudget(private var remaining: Long) {
        private val output = mutableListOf<JsonObject>()
        private var omittedText = 0
        private var omittedAudio = 0

        fun append(item: JsonObject) {
            when (JsonScalars.str(item, "type")) {
                "input_text" -> appendText(JsonScalars.str(item, "text").orEmpty())
                "input_audio" -> {
                    val cost = NativeCodeModeAudio.tokens(JsonScalars.str(item, "audio_url").orEmpty())
                    if (cost <= remaining) {
                        output.add(item)
                        remaining -= cost
                    } else {
                        omittedAudio++
                    }
                }
                else -> output.add(item)
            }
        }

        private fun appendText(value: String) {
            if (value.isEmpty()) return
            if (remaining == 0L) {
                omittedText++
            } else {
                val cost = tokens(value)
                if (cost <= remaining) {
                    output.add(NativeCodeModeOutput.textItem(value))
                    remaining -= cost
                } else {
                    output.add(NativeCodeModeOutput.textItem(text(value, remaining.toInt())))
                    remaining = 0
                }
            }
        }

        fun finish(): List<JsonObject> {
            if (omittedText > 0) output.add(NativeCodeModeOutput.textItem("[omitted $omittedText text items ...]"))
            if (omittedAudio > 0) output.add(NativeCodeModeOutput.textItem("[omitted $omittedAudio audio items ...]"))
            return output
        }
    }
}
