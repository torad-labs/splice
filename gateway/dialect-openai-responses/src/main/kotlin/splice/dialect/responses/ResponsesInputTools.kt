// NEW: the tool round-trip family — appendToolUse, appendToolDeclaration, appendToolResult — split
// out of ResponsesRequestBuilder.kt (2026-08-17, concentration campaign). This is the family that
// reaches outward (ResponsesStableIds, ToolSearchOutput, the reasoning cache lookup); isolating it
// is what makes ResponsesInputBuilder.kt's walk itself trivial. Every relocated member kept its identical
// name and argument list.
package splice.dialect.responses

import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.wire.ImageBlock
import splice.core.wire.TextBlock
import splice.core.wire.ToolDefinition
import splice.core.wire.ToolResultBlock
import splice.core.wire.ToolUseBlock

internal class ResponsesInputTools(
    private val quirks: ResponsesQuirks,
    private val loopGuardDirectives: Map<String, String> = emptyMap(),
) {

    private val ids = ResponsesStableIds()
    private val toolSearchOutput = ToolSearchOutput()
    private val parts = ResponsesInputParts(quirks.minImageEdgePx)
    private val inject = ResponsesReasoningInject()

    internal fun appendToolUse(
        sink: JsonArrayBuilder,
        block: ToolUseBlock,
        opts: BuildOptions,
        declareByName: Map<String, ToolDefinition>,
    ) {
        if (opts.compact) return
        // CHANGE 2 (cache-prefix stability, 2026-07-25): a DEFERRED tool this block names gets its
        // full schema declared IN HISTORY, ONCE per request, immediately before this function_call
        // — the model then knows it from tool_search_output, never from a moving tools[] entry
        // (ToolSurface.kt header). Absent from declareByName means the tool is either already eager
        // (declared normally, nothing to add) or was dropped from body.tools entirely since it was
        // last used (no schema exists to declare) — either way this is a no-op and the function_call
        // rides exactly like an eager tool's: degrade to status quo, never crash, never an
        // undeclared tool_search reference.
        declareByName[block.name]?.let { tool ->
            if (opts.injectedToolDeclarationNames.add(tool.name)) appendToolDeclaration(sink, tool)
        }
        // RC-3: reinject the turn's cached reasoning ONCE, immediately before its FIRST
        // function_call — the API rejects both an orphaned reasoning item and a function_call
        // whose reasoning was dropped (the replay_reasoning=false amnesia class this fixes).
        // Driven by the assistant's tool_use blocks, never by which tool_results arrived.
        opts.reasoningLookup(block.id)?.forEach { envelope ->
            opts.decodeReasoningEnvelope(envelope)?.let { inject.addReasoningOnce(sink, it, opts) }
        }
        sink.add(
            buildJsonObject {
                put("type", "function_call")
                put("call_id", block.id)
                put("name", block.name)
                put("arguments", block.input.toString())
            },
        )
    }

    /** The synthetic pair CHANGE 2 injects for a deferred tool a ToolUseBlock already named: a
     *  tool_search_call, then the tool_search_output carrying its FULL schema — the exact shape
     *  [ResponsesToolSearchController] emits for a REAL within-turn search, reused via
     *  [ToolSearchOutput.toolSearchOutputItem] (ToolSearchOutput.kt) and never re-authored as a
     *  second shape. The call_id is a pure function of the tool NAME alone
     *  ([ResponsesStableIds.stableToolSearchCallId]) — no transcript position, no counters, no
     *  randomness — so the whole pair is byte-identical on every turn that replays this tool's
     *  declaration, which is the property this feature exists to buy. */
    private fun appendToolDeclaration(sink: JsonArrayBuilder, tool: ToolDefinition) {
        val callId = ids.stableToolSearchCallId(tool.name)
        sink.add(
            buildJsonObject {
                put("type", "tool_search_call")
                put("call_id", callId)
                put("execution", "client")
                put("arguments", buildJsonObject { put("query", tool.name) })
            },
        )
        sink.add(
            toolSearchOutput.toolSearchOutputItem(
                callId,
                listOf(tool),
                quirks.emitStrict,
                quirks.forceStrictFalse,
                quirks.normalizeToolSchemas,
            ),
        )
    }

    internal fun appendToolResult(
        sink: JsonArrayBuilder,
        block: ToolResultBlock,
        opts: BuildOptions,
    ) {
        val text = block.content.filterIsInstance<TextBlock>().joinToString("") { it.text }
        if (opts.compact) {
            // fold tool results into plain user text so the summarizer still sees them
            if (text.isNotEmpty()) sink.add(parts.roleText("user", "[tool_result ${block.toolUseId}] $text"))
            return
        }
        sink.add(
            buildJsonObject {
                put("type", "function_call_output")
                put("call_id", block.toolUseId)
                put("output", loopGuardDirectives[block.toolUseId]?.let { "$it\n\n$text" } ?: text)
            },
        )
        // v25: images inside tool_result (Read on a PNG, screenshots) used to vanish —
        // function_call_output.output is string-only, so ride them in a follow-up user message.
        val (undersized, mappable) = block.content.filterIsInstance<ImageBlock>()
            .partition { parts.belowFloor(it.source) != null }
        val imageParts = mappable.mapNotNull { parts.imagePart(it.source) }
        if (imageParts.isNotEmpty()) {
            sink.add(parts.toolResultImageMessage(block.toolUseId, imageParts))
        }
        appendFloorDrops(sink, block.toolUseId, undersized)
    }

    /**
     * DR-155: say so when a tool_result image was dropped for being under the backend's minimum.
     *
     * This path drops an UNMAPPABLE image silently today — unlike the message walk, which emits an
     * "unsupported source" marker — and that silence is a real gap but not this row's, so it is
     * deliberately left exactly as it is. The floor drop does not join it: an undersized image is
     * the one case measured to cost a whole turn, and a screenshot tool whose output quietly lost
     * its image is precisely the regression the v25 marker doctrine exists to prevent.
     */
    private fun appendFloorDrops(sink: JsonArrayBuilder, toolUseId: String, undersized: List<ImageBlock>) {
        val min = undersized.firstNotNullOfOrNull { parts.belowFloor(it.source) } ?: return
        val why = parts.floorReason(min)
        sink.add(
            parts.roleText(
                "user",
                "[${undersized.size} image(s) from tool_result $toolUseId omitted by " +
                    "${quirks.providerTag} proxy: $why]",
            ),
        )
    }
}
