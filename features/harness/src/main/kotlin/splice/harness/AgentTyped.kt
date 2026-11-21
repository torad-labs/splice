// NEW: a door mode on every dialect (docs/specs/harness.md, decision 12, Modes). One round's stream as a dialect that
// does not stream Anthropic's frames writes it: each typed write is put into the model's message as the block and the
// delta Anthropic's stream would carry, then goes on to the round's sink, which hides the calls splice answers.
package splice.harness

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.index.WireBlockIndex
import splice.upstream.sse.WireSink

/** [round] with every typed write also put into [message]. */
internal class AgentTyped(private val round: WireSink, private val message: MessageAssembly) : WireSink by round {
    override suspend fun openText(): WireBlockIndex = round.openText().also {
        message.opened(it.value, block(TYPED_TEXT, mapOf(TYPED_TEXT to text(""))))
    }

    override suspend fun openThinking(): WireBlockIndex = round.openThinking().also {
        message.opened(it.value, block(TYPED_THINKING, mapOf(TYPED_THINKING to text(""), TYPED_SIGNATURE to text(""))))
    }

    override suspend fun openTool(id: String, name: String): WireBlockIndex = round.openTool(id, name).also {
        val fields = mapOf("id" to text(id), "name" to text(name), "input" to JsonObject(emptyMap()))
        message.opened(it.value, block("tool_use", fields))
    }

    override suspend fun openRawBlock(contentBlock: JsonObject): WireBlockIndex? =
        round.openRawBlock(contentBlock)?.also { message.opened(it.value, contentBlock) }

    override suspend fun textDelta(index: WireBlockIndex, text: String) {
        message.added(index.value, block("text_delta", mapOf(TYPED_TEXT to text(text))))
        round.textDelta(index, text)
    }

    override suspend fun thinkingDelta(index: WireBlockIndex, thinking: String) {
        message.added(index.value, block("thinking_delta", mapOf(TYPED_THINKING to text(thinking))))
        round.thinkingDelta(index, thinking)
    }

    override suspend fun signatureDelta(index: WireBlockIndex, signature: String) {
        message.added(index.value, block("signature_delta", mapOf(TYPED_SIGNATURE to text(signature))))
        round.signatureDelta(index, signature)
    }

    override suspend fun inputJsonDelta(index: WireBlockIndex, partialJson: String) {
        message.added(index.value, block("input_json_delta", mapOf("partial_json" to text(partialJson))))
        round.inputJsonDelta(index, partialJson)
    }

    override suspend fun rawDelta(index: WireBlockIndex, delta: JsonObject) {
        message.added(index.value, delta)
        round.rawDelta(index, delta)
    }

    override suspend fun addTextBlock(text: String) {
        if (text.isNotEmpty()) message.whole(block(TYPED_TEXT, mapOf(TYPED_TEXT to text(text))))
        round.addTextBlock(text)
    }

    override suspend fun addRedactedThinking(data: String) {
        message.whole(block("redacted_thinking", mapOf("data" to text(data))))
        round.addRedactedThinking(data)
    }

    private fun text(value: String): JsonPrimitive = JsonPrimitive(value)

    private fun block(type: String, fields: Map<String, JsonElement>): JsonObject =
        JsonObject(mapOf(TYPED_TYPE to text(type)) + fields)
}

private const val TYPED_TYPE = "type"
private const val TYPED_TEXT = "text"
private const val TYPED_THINKING = "thinking"
private const val TYPED_SIGNATURE = "signature"
