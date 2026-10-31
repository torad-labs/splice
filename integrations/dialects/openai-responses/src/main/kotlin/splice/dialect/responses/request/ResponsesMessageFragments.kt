package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonWire
import splice.core.wire.AnthropicMessage
import splice.core.wire.AnthropicRequest
import splice.core.wire.DocumentBlock
import splice.core.wire.ImageBlock
import splice.core.wire.RedactedThinkingBlock
import splice.core.wire.TextBlock
import splice.core.wire.ThinkingBlock
import splice.core.wire.ToolDefinition
import splice.core.wire.ToolResultBlock
import splice.core.wire.ToolUseBlock
import splice.core.wire.UnknownBlock
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.ResponsesAssistantPhaseRecords
import splice.dialect.responses.reasoning.ResponsesHistoryInput
import splice.dialect.responses.stream.LoopGuard
import splice.upstream.ToolNameShortener
import splice.upstream.memory.MessageFragmentContext
import splice.upstream.memory.MessageFragments

/** Cache only the independent part of the walk; injection, declaration and prior-turn state stay live. */
internal class ResponsesMessageFragments(
    private val quirks: ResponsesQuirks,
    private val toolNames: ToolNameShortener,
) {
    private val fragments = MessageFragments()

    fun walk(
        body: AnthropicRequest,
        raw: JsonObject,
        opts: BuildOptions,
        declareByName: Map<String, ToolDefinition>,
        effort: String?,
    ): JsonArray {
        val directives = if (quirks.roundTrip.loopGuard) LoopGuard.analyze(body.messages) else emptyMap()
        val inputBuilder = ResponsesInputBuilder(
            quirks,
            directives,
            toolNames,
            declareByName,
            body.tools.associateByTo(mutableMapOf()) { it.name },
        )
        val context = context(body, raw, opts, directives, effort)
        val history = history(body, opts)
        val assistantToolUseFollows = followingAssistantToolUse(body.messages)
        return buildJsonArray {
            for ((index, msg) in body.messages.withIndex()) {
                history?.inject(this)
                val futureAssistantToolUse = assistantToolUseFollows[index]
                if (!requiresLiveState(msg) && history?.advanceUnchanged(msg) != false) {
                    fragments.message(phaseContext(context, msg, futureAssistantToolUse), msg) {
                        buildJsonArray {
                            inputBuilder.appendMessage(
                                this,
                                msg,
                                opts,
                                futureAssistantToolUse = futureAssistantToolUse,
                            )
                        }
                    }.forEach { add(it) }
                } else {
                    inputBuilder.appendMessage(
                        this,
                        msg,
                        opts,
                        history,
                        futureAssistantToolUse,
                    )
                }
                history?.inject(this)
            }
            history?.inject(this)
            history?.promote()
        }
    }

    private fun history(body: AnthropicRequest, opts: BuildOptions): ResponsesHistoryInput? {
        val retainReasoning = opts.handoff.replay.v || quirks.roundTrip.reasoningCache
        val retainHistory = retainReasoning || hasPhaseHistory(opts)
        return if (retainHistory) ResponsesHistoryInput(opts, body.messages) else null
    }

    private fun hasPhaseHistory(opts: BuildOptions): Boolean {
        if (opts.sessionId.isNullOrEmpty()) return false
        return ResponsesAssistantPhaseRecords().read(opts.handoff.history()).isNotEmpty()
    }

    /** Tool calls, decrypted reasoning and source tool references depend on this request's live state. */
    private fun requiresLiveState(message: AnthropicMessage): Boolean = message.content.any { block ->
        when (block) {
            is ToolUseBlock, is RedactedThinkingBlock -> true
            is ToolResultBlock -> block.content.filterIsInstance<UnknownBlock>().any { it.loadedTool != null }
            is UnknownBlock -> block.type == SOURCE_TOOL_ADDITION || block.type == SOURCE_TOOL_REMOVAL
            is TextBlock, is ImageBlock, is DocumentBlock, is ThinkingBlock -> false
        }
    }

    /** Phase inference reads the adjacent assistant messages, so its cache identity must as well. */
    private fun phaseContext(
        context: String,
        message: AnthropicMessage,
        futureAssistantToolUse: Boolean,
    ): String {
        if (message.role != ROLE_ASSISTANT) return context
        return JsonWire.string(
            buildJsonObject {
                put("request_context", context)
                put("future_assistant_tool_use", futureAssistantToolUse)
            },
        )
    }

    private fun followingAssistantToolUse(messages: List<AnthropicMessage>): BooleanArray {
        val follows = BooleanArray(messages.size)
        var toolUseFollows = false
        for (index in messages.indices.reversed()) {
            val message = messages[index]
            if (message.role != ROLE_ASSISTANT) toolUseFollows = false
            follows[index] = toolUseFollows
            if (message.role == ROLE_ASSISTANT && message.content.any { it is ToolUseBlock }) {
                toolUseFollows = true
            }
        }
        return follows
    }

    private fun context(
        body: AnthropicRequest,
        raw: JsonObject,
        opts: BuildOptions,
        directives: Map<String, String>,
        effort: String?,
    ): String = JsonWire.string(
        buildJsonObject {
            put("request", MessageFragmentContext.of(body, raw))
            put("original_model", opts.models.original)
            put("upstream_model", opts.models.upstream)
            effort?.let { put("effort", it) }
            opts.reasoning.summary?.let { put("summary", it) }
            put("display", opts.reasoning.display.toString())
            put("replay_reasoning", opts.handoff.replay.v)
            put("include_encrypted", opts.handoff.includeEncrypted.v)
            put("tool_surface_open", opts.toolSurfaceOpen)
            opts.sessionId?.let { put("session_id", it) }
            put("loop_guard", JsonObject(directives.mapValues { JsonPrimitive(it.value) }))
        },
    )
}
