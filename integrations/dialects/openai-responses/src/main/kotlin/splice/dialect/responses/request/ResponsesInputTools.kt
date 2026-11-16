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
import splice.dialect.responses.ResponsesToolChanges
import splice.dialect.responses.reasoning.ResponsesReasoningInject
import splice.dialect.responses.tools.ResponsesArrivedTools
import splice.dialect.responses.tools.TOOL_CALLER_NAME
import splice.dialect.responses.tools.ToolCaller
import splice.dialect.responses.tools.ToolSearchOutput
import splice.dialect.responses.tools.ToolWireObjects
import splice.upstream.ToolNameShortener

internal class ResponsesInputTools(
    private val quirks: ResponsesQuirks,
    private val loopGuardDirectives: Map<String, String> = emptyMap(),
    private val names: ToolNameShortener = ToolNameShortener(),
    /** Every definition the conversation has given so far, by name: the request's own, then each addition's. */
    private val knownTools: MutableMap<String, ToolDefinition> = mutableMapOf(),
    /** The conversation's tool list, on a head that writes a later tool into the history ([HistoryTools.listed]). */
    listed: List<ToolDefinition>? = null,
) {

    private val ids = ResponsesStableIds()

    /** Which tools the model calls through the caller, up to this point of the walk. Null on a head with no caller. */
    private val arrived = listed?.let(::ResponsesArrivedTools)
    private val caller = ToolCaller()
    private val toolSearchOutput = ToolSearchOutput(names)
    private val parts = ResponsesInputParts(quirks.request.minImageEdgePx)
    private val resultRenderer =
        ResponsesToolResultRenderer(quirks, loopGuardDirectives, ToolResultOmission(::resultWords))
    private val toolWire = ToolWireObjects(names)
    private val inject = ResponsesReasoningInject()

    internal fun appendToolUse(
        sink: JsonArrayBuilder,
        block: ToolUseBlock,
        opts: BuildOptions,
        declareByName: Map<String, ToolDefinition>,
    ) {
        // A tool the conversation wrote into the history is one the model called through the caller, and its call
        // goes back the way the model made it. This follows from the conversation up to this call alone, so the call
        // is the same bytes on every later request.
        val throughCaller = arrived?.listsCurrent(block.name) == false
        // CHANGE 2 (cache-prefix stability, 2026-07-25): a DEFERRED tool this block names gets its
        // full schema declared IN HISTORY, ONCE per request, immediately before this function_call
        // — the model then knows it from tool_search_output, never from a moving tools[] entry
        // (ToolSurface.kt header). Absent from declareByName means the tool is either already eager
        // (declared normally, nothing to add) or was dropped from body.tools entirely since it was
        // last used (no schema exists to declare) — either way this is a no-op and the function_call
        // rides exactly like an eager tool's: degrade to status quo, never crash, never an
        // undeclared tool_search reference.
        declareByName[block.name]?.takeUnless { throughCaller }?.let { tool ->
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
                put("name", if (throughCaller) TOOL_CALLER_NAME else names.shorten(block.name))
                put(
                    "arguments",
                    if (throughCaller) caller.arguments(block.name, block.input) else JsonWire.string(block.input),
                )
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

    /** What an unknown block says inside a tool's result. On a head with a caller, a reference to a tool that head
     *  writes into the history says the whole tool, right where ToolSearch loaded it: the result is the place the
     *  model looks for what its search found. Any other block says what [omission] says. */
    private fun resultWords(block: UnknownBlock): String = arriving(block)?.let(caller::written) ?: omission(block)

    /** The tool the reference [block] loads, when this head writes it into the history: one the conversation holds a
     *  definition of and the model does not call under its own name. Null on a head with no caller. */
    private fun arriving(block: UnknownBlock): ToolDefinition? = arrived?.let { held ->
        block.loadedTool?.let(knownTools::get)?.takeUnless { held.listsCurrent(it.name) }
    }

    internal fun appendToolAddition(sink: JsonArrayBuilder, block: UnknownBlock) {
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
        knownTools[tool.name] = tool
        if (arrived == null) {
            appendToolDefinition(sink, tool)
        } else {
            // The tool list stays the conversation's first, so the tool is written here, where the conversation added
            // it, and called through the caller from here on. One the list holds that comes back is written again:
            // the list still carries the schema it left with.
            val listedAs = if (arrived.lists(tool.name)) names.shorten(tool.name) else null
            sink.add(parts.roleText(ResponsesContextMessage.CLIENT_ROLE, caller.written(tool, listedAs)))
            arrived.wrote(tool.name)
        }
    }

    /** A tool search result's validated bare reference makes a known source-deferred schema callable. A head with a
     *  caller wrote the tool inside the result ([resultWords]) and adds no item here. */
    internal fun appendLoadedTool(sink: JsonArrayBuilder, block: UnknownBlock) {
        if (arrived != null) return
        block.loadedTool?.let(knownTools::get)?.let { tool -> appendToolDefinition(sink, tool) }
    }

    private fun appendToolDefinition(sink: JsonArrayBuilder, tool: ToolDefinition) {
        // A backend that does not read the item refuses the whole request; its tool list carries the tool instead
        // (ResponsesRequestBuilder).
        if (quirks.tools.toolChanges != ResponsesToolChanges.INLINE) return
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

    /** The tool [block] takes away is told in words where it left, on a head with a caller: the tool list and
     *  everything written before stay as they were, so the conversation is still read from the backend's cache, and a
     *  call the model still makes reaches Claude Code, which refuses a tool it no longer has. The tool is named as the
     *  model knows it: by its name in the tool list, or whole where the conversation wrote it. Any other head writes
     *  nothing here, since its tool list or its allowed_tools drops the tool. */
    internal fun appendToolRemoval(sink: JsonArrayBuilder, block: UnknownBlock) {
        val held = arrived ?: return
        val name = changedToolName(block) ?: return
        val known = if (held.listsCurrent(name)) names.shorten(name) else name
        sink.add(
            parts.roleText(ResponsesContextMessage.CLIENT_ROLE, "[tool removed: $known. It can no longer be called.]"),
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
        // Read before the result is rendered and marked after it, so two references to one tool in one result read
        // alike.
        val wrote = block.content.filterIsInstance<UnknownBlock>().mapNotNull(::arriving)
        resultRenderer.append(sink, block)
        wrote.forEach { arrived?.wrote(it.name) }
    }
}

private const val ADDITIONAL_TOOLS = "additional_tools"
