// NEW: emits a `response` hook's rewritten JSON body into a WireSink and extracts plain text from assembled blocks.
package splice.harness.hook

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import splice.upstream.sse.WireSink

internal class HookRewriteEmit {
    suspend fun emit(target: WireSink, body: JsonObject) {
        val blocks = (body["content"] as? JsonArray)?.mapNotNull { it as? JsonObject }
        if (blocks != null) {
            blocks.forEach { emitBlock(target, it) }
        } else {
            JsonScalars.str(body, "text")?.takeIf { it.isNotEmpty() }?.let { target.addTextBlock(it) }
        }
    }

    fun textOf(blocks: List<JsonObject>): String = blocks.mapNotNull { block ->
        if (JsonScalars.str(block, "type") == "text") (block["text"] as? JsonPrimitive)?.content else null
    }.joinToString("")

    private suspend fun emitBlock(target: WireSink, block: JsonObject) {
        when (JsonScalars.str(block, "type")) {
            "text" -> emitText(target, block)
            "redacted_thinking" -> JsonScalars.str(block, "data")?.let { target.addRedactedThinking(it) }
            "thinking" -> emitThinking(target, block)
            "tool_use" -> emitToolUse(target, block)
            else -> target.openRawBlock(block)?.let { target.closeBlock(it) }
        }
    }

    private suspend fun emitText(target: WireSink, block: JsonObject) {
        JsonScalars.str(block, "text")?.takeIf { it.isNotEmpty() }?.let { target.addTextBlock(it) }
    }

    private suspend fun emitThinking(target: WireSink, block: JsonObject) {
        val idx = target.openThinking()
        JsonScalars.str(block, "thinking")?.takeIf { it.isNotEmpty() }?.let { target.thinkingDelta(idx, it) }
        JsonScalars.str(block, "signature")?.takeIf { it.isNotEmpty() }?.let { target.signatureDelta(idx, it) }
        target.closeBlock(idx)
    }

    private suspend fun emitToolUse(target: WireSink, block: JsonObject) {
        val id = JsonScalars.str(block, "id") ?: return
        val name = JsonScalars.str(block, "name") ?: return
        val idx = target.openTool(id, name)
        val input = block["input"]?.toString().orEmpty()
        if (input.isNotEmpty()) target.inputJsonDelta(idx, input)
        target.closeBlock(idx)
    }
}
