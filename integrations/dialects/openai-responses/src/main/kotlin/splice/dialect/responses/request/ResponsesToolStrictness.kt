package splice.dialect.responses.request

import splice.core.failure.Decline
import splice.core.failure.FailureCause
import splice.core.failure.RequestFeature
import splice.core.wire.AnthropicRequest
import splice.dialect.responses.ResponsesQuirks
import splice.upstream.RequestRefusal

/** Refuses source strictness only for offered definitions that this provider cannot express. */
internal class ResponsesToolStrictness(private val quirks: ResponsesQuirks) {
    fun refusal(body: AnthropicRequest): RequestRefusal? {
        if (quirks.tools.forceStrictFalse || quirks.tools.emitStrict) return null
        if (ResponsesSourceToolDefinitions.offered(body).none { it.strict == true }) return null
        return RequestRefusal(FailureCause.Splice.Declined(Decline.Untranslatable(RequestFeature.TOOL_STRICTNESS)))
    }
}
