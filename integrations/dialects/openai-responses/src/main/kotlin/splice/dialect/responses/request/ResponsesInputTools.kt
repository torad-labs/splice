// NEW: the tool round-trip family — appendToolUse, appendToolDeclaration, appendToolResult — split
// out of ResponsesRequestBuilder.kt (2026-08-17, concentration campaign). This is the family that
// reaches outward (ResponsesStableIds, ToolSearchOutput, the reasoning cache lookup); isolating it
// is what makes ResponsesInputBuilder.kt's walk itself trivial. Every relocated member kept its identical
// name and argument list.
package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.wire.ToolDefinition
import splice.core.wire.ToolResultBlock
import splice.core.wire.ToolUseBlock
import splice.core.wire.UnknownBlock
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.ResponsesReasoningInject
import splice.dialect.responses.tools.ToolSearchOutput
import splice.dialect.responses.tools.ToolWireObjects
import splice.upstream.ToolNameShortener

internal class ResponsesInputTools(
    private val quirks: ResponsesQuirks,
    private val loopGuardDirectives: Map<String, String> = emptyMap(),
    private val names: ToolNameShortener = ToolNameShortener(),
) {

    private val ids = ResponsesStableIds()
    private val toolSearchOutput = ToolSearchOutput(names)
    private val parts = ResponsesInputParts(quirks.request.minImageEdgePx)
    private val resultRenderer =
        ResponsesToolResultRenderer(quirks, loopGuardDirectives, ToolResultOmission(::omission))
    private val toolWire = ToolWireObjects(names)
    private val inject = ResponsesReasoningInject()

    internal fun appendToolUse(
        sink: JsonArrayBuilder,
        block: ToolUseBlock,
        opts: BuildOptions,
        declareByName: Map<String, ToolDefinition>,
    ) {
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
        opts.handoff.lookup(block.id)?.forEach { envelope ->
            opts.handoff.decode(envelope)?.let { inject.addReasoningOnce(sink, it, opts) }
        }
        sink.add(
            buildJsonObject {
                put("type", "function_call")
                put("call_id", block.id)
                put("name", names.shorten(block.name))
                put("arguments", JsonWire.string(block.input))
            },
        )
    }

    /** The synthetic pair CHANGE 2 injects for a deferred tool a ToolUseBlock already named: a
     *  tool_search_call, then the tool_search_output carrying its FULL schema — the exact shape
     *  [ResponsesToolSearchPolicy] emits for a REAL within-turn search, reused via
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
                quirks.tools.emitStrict,
                quirks.tools.forceStrictFalse,
                quirks.tools.normalizeToolSchemas,
            ),
        )
    }

    /** What an unknown block says in its place. A tool_reference says its tool is loaded, which is true only where
     *  the client's tool list is the model's tool list. A head with a tool surface (codex, meta) holds some tools out
     *  of the request and hands them over through its own tool_search, so a loaded tool is not always one the model
     *  can call directly: that head's bytes stay as they were. */
    internal fun omission(block: UnknownBlock): String =
        block.omissionMarker(quirks.providerTag, toolsCallable = quirks.tools.toolSurface == null)

    internal fun appendToolAddition(
        sink: JsonArrayBuilder,
        block: UnknownBlock,
        knownTools: MutableMap<String, ToolDefinition>,
    ) {
        val tool = ResponsesSourceToolDefinitions.fromAddition(block, knownTools)
        if (tool == null) {
            val name = changedToolName(block) ?: "unknown"
            sink.add(
                parts.roleText(
                    ResponsesContextMessage.CLIENT_ROLE,
                    "[tool addition $name omitted by ${quirks.providerTag} proxy: schema unavailable]",
                ),
            )
            return
        }
        // A later reference must carry the schema that this exact addition made current, not the
        // request's original catalog entry. This is source provenance, not a Responses-side rewrite.
        appendToolDefinition(sink, tool, knownTools)
    }

    /** A tool search result's validated bare reference makes a known source-deferred schema callable. */
    internal fun appendLoadedTool(
        sink: JsonArrayBuilder,
        block: UnknownBlock,
        knownTools: MutableMap<String, ToolDefinition>,
    ) {
        val tool = block.loadedTool?.let(knownTools::get) ?: return
        appendToolDefinition(sink, tool, knownTools)
    }

    private fun appendToolDefinition(
        sink: JsonArrayBuilder,
        tool: ToolDefinition,
        knownTools: MutableMap<String, ToolDefinition>,
    ) {
        knownTools[tool.name] = tool
        // A backend that does not read the item refuses the whole request; its tool list carries the tool instead
        // (ResponsesRequestBuilder).
        if (!quirks.tools.acceptsInlineToolChanges) return
        sink.add(
            buildJsonObject {
                put("type", ADDITIONAL_TOOLS)
                put("role", "developer")
                put(
                    "tools",
                    buildJsonArray {
                        add(
                            toolWire.functionToolObject(
                                tool,
                                quirks.tools.emitStrict,
                                quirks.tools.forceStrictFalse,
                                quirks.tools.normalizeToolSchemas,
                            ),
                        )
                    },
                )
            },
        )
    }

    private fun changedToolName(block: UnknownBlock): String? {
        val tool = block.raw[SOURCE_TOOL_FIELD_TOOL] as? JsonObject ?: return null
        return when (JsonScalars.str(tool, SOURCE_TOOL_FIELD_TYPE)) {
            SOURCE_TOOL_REFERENCE -> JsonScalars.str(tool, SOURCE_TOOL_FIELD_NAME)
            SOURCE_TOOL_DEFINITION ->
                JsonScalars.str(tool[SOURCE_TOOL_FIELD_DEFINITION] as? JsonObject, SOURCE_TOOL_FIELD_NAME)
            else -> null
        }
    }

    internal fun appendToolResult(sink: JsonArrayBuilder, block: ToolResultBlock) {
        resultRenderer.append(sink, block)
    }
}

private const val ADDITIONAL_TOOLS = "additional_tools"
