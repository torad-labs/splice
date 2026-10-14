package splice.core.wire

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.memory.HeapJson
import java.util.Collections

/** Ordered equality proves identical JsonWire output, including numeric lexemes and unknown keys.
 *  Map equality/hash codes alone ignore object emission order and are only candidate selectors.
 */
public object MessageFragmentInputs {
    public fun same(left: JsonElement, right: JsonElement): Boolean = when {
        left is JsonPrimitive && right is JsonPrimitive -> left == right
        left is JsonArray && right is JsonArray ->
            left.size == right.size && left.indices.all { same(left[it], right[it]) }
        left is JsonObject && right is JsonObject -> sameObject(left, right)
        else -> false
    }

    private fun sameObject(left: JsonObject, right: JsonObject): Boolean {
        if (left.size != right.size) return false
        val current = right.entries.iterator()
        return left.entries.all { before ->
            val after = current.next()
            before.key == after.key && same(before.value, after.value)
        }
    }

    public fun same(left: AnthropicMessage, right: AnthropicMessage): Boolean =
        left.role == right.role && sameBlocks(left.content, right.content)

    private fun sameBlocks(left: List<ContentBlock>, right: List<ContentBlock>): Boolean =
        left.size == right.size && left.indices.all { sameBlock(left[it], right[it]) }

    private fun sameBlock(before: ContentBlock, after: ContentBlock): Boolean = when (before) {
        is ToolUseBlock -> after is ToolUseBlock && sameToolUse(before, after)
        is ToolResultBlock -> after is ToolResultBlock && sameToolResult(before, after)
        is UnknownBlock -> after is UnknownBlock && same(before.raw, after.raw)
        is TextBlock, is ThinkingBlock, is RedactedThinkingBlock, is ImageBlock, is DocumentBlock -> before == after
    }

    private fun sameToolUse(before: ToolUseBlock, after: ToolUseBlock): Boolean =
        before.id == after.id && before.name == after.name && same(before.input, after.input)

    private fun sameToolResult(before: ToolResultBlock, after: ToolResultBlock): Boolean =
        before.toolUseId == after.toolUseId && before.isError == after.isError &&
            sameBlocks(before.content, after.content)

    public fun freeze(value: JsonElement): JsonElement = when (value) {
        is JsonPrimitive -> value
        is JsonArray -> JsonArray(Collections.unmodifiableList(value.map(::freeze)))
        is JsonObject -> {
            val owned = value.entries.associateTo(linkedMapOf()) { it.key to freeze(it.value) }
            JsonObject(Collections.unmodifiableMap(owned))
        }
    }

    public fun freeze(value: AnthropicMessage): AnthropicMessage =
        value.copy(content = Collections.unmodifiableList(value.content.map(::freezeBlock)))

    private fun freezeBlock(block: ContentBlock): ContentBlock = when (block) {
        is ToolUseBlock -> block.copy(input = freeze(block.input) as JsonObject)
        is ToolResultBlock -> block.copy(content = Collections.unmodifiableList(block.content.map(::freezeBlock)))
        is UnknownBlock -> block.copy(raw = freeze(block.raw) as JsonObject)
        is TextBlock, is ThinkingBlock, is RedactedThinkingBlock, is ImageBlock, is DocumentBlock -> block
    }

    /** Conservative retained graph weight; never serializes the complete typed message to select a hit. */
    public fun bytes(input: AnthropicMessage): Long = HeapJson.add(HeapJson.text(input.role), blockBytes(input.content))

    private fun blockBytes(blocks: List<ContentBlock>): Long =
        blocks.fold(MESSAGE_FRAGMENT_OWNER_BYTES) { total, block ->
            val bytes = when (block) {
                is ToolUseBlock -> HeapJson.add(
                    HeapJson.add(HeapJson.text(block.id), HeapJson.text(block.name)),
                    HeapJson.bytes(block.input),
                )
                is ToolResultBlock -> HeapJson.add(HeapJson.text(block.toolUseId), blockBytes(block.content))
                is UnknownBlock -> HeapJson.bytes(block.raw)
                is TextBlock -> HeapJson.text(block.text)
                is ThinkingBlock -> HeapJson.text(block.thinking)
                is RedactedThinkingBlock -> HeapJson.text(block.data)
                is ImageBlock -> mediaBytes(block.source)
                is DocumentBlock -> mediaBytes(block.source)
            }
            HeapJson.add(total, HeapJson.add(MESSAGE_FRAGMENT_OWNER_BYTES, bytes))
        }

    private fun mediaBytes(source: MediaSource?): Long {
        val fields = listOf(source?.type, source?.mediaType, source?.data, source?.url)
        return fields.fold(MESSAGE_FRAGMENT_OWNER_BYTES) { total, text ->
            HeapJson.add(total, text?.let(HeapJson::text) ?: 0L)
        }
    }
}

private const val MESSAGE_FRAGMENT_OWNER_BYTES = 256L
