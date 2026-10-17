package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonWire
import splice.core.wire.AnthropicRequest
import splice.core.wire.RedactedThinkingBlock
import splice.core.wire.ToolDefinition
import splice.core.wire.ToolUseBlock
import splice.dialect.responses.ResponsesQuirks
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
        val inputBuilder = ResponsesInputBuilder(quirks, directives, toolNames)
        val context = context(body, raw, opts, directives, effort)
        return buildJsonArray {
            for (msg in body.messages) {
                // Tool calls/decrypted reasoning have live lookups and per-request inject-once state.
                if (msg.content.any { it is ToolUseBlock || it is RedactedThinkingBlock }) {
                    inputBuilder.appendMessage(this, msg, opts, declareByName)
                } else {
                    fragments.message(context, msg) {
                        buildJsonArray { inputBuilder.appendMessage(this, msg, opts, declareByName) }
                    }.forEach { add(it) }
                }
            }
        }
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
