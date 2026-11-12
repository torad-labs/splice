package splice.dialect.responses.request

import splice.core.failure.Decline
import splice.core.failure.FailureCause
import splice.core.failure.RequestFeature
import splice.core.wire.AnthropicRequest
import splice.core.wire.ToolDefinition
import splice.upstream.RequestRefusal

/** Refuses only callable source semantics that the ordinary public Responses function path cannot carry. */
internal class ResponsesToolDeclarationAdmission {
    fun refusal(body: AnthropicRequest): RequestRefusal? =
        ResponsesSourceToolDefinitions.offered(body)
            .mapNotNull { unsupported(it, body) }
            .firstOrNull()?.let { feature ->
                RequestRefusal(FailureCause.Splice.Declined(Decline.Untranslatable(feature)))
            }

    private fun unsupported(tool: ToolDefinition, request: AnthropicRequest): RequestFeature? = when {
        tool.type != null && tool.type != SOURCE_CUSTOM_TOOL -> RequestFeature.TOOL_TYPE
        tool.allowedCallers?.contains(DIRECT_CALLER) == false -> RequestFeature.TOOL_ALLOWED_CALLERS
        // Streaming Responses argument deltas are incremental. An explicit request to buffer this
        // tool's arguments requires a per-tool buffering path only when the caller requests a stream.
        request.stream && tool.eagerInputStreaming == false -> RequestFeature.TOOL_EAGER_INPUT_STREAMING
        !ResponsesCacheHints.accepts(tool.cacheControl) -> RequestFeature.TOOL_CACHE_CONTROL
        // Input examples are preserved in the public function description, not silently dropped.
        else -> null
    }
}

private const val DIRECT_CALLER = "direct"
private const val SOURCE_CUSTOM_TOOL = "custom"
