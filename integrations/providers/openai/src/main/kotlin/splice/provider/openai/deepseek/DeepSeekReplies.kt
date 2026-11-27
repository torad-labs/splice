package splice.provider.openai.deepseek

import splice.core.failure.FailureCause
import splice.core.wire.HttpStatus
import splice.upstream.failure.ERROR_EVENT
import splice.upstream.failure.FailedEvent
import splice.upstream.failure.FailedReply
import splice.upstream.failure.OpenAiReplies
import splice.upstream.failure.ProviderDeviation

/** DeepSeek's Messages endpoint names its context limit in OpenAI's words (DeepSeek-V3 issue #1102). */
public object DeepSeekReplies : ProviderDeviation {
    override fun http(reply: FailedReply): FailureCause.Provider.TooLong? =
        if (reply.status == HttpStatus.BAD_REQUEST) statedOverflow(OpenAiReplies.http(reply)) else null

    override fun event(event: FailedEvent): FailureCause.Provider.TooLong? =
        if (event.type == ERROR_EVENT) statedOverflow(OpenAiReplies.event(event)) else null

    private fun statedOverflow(cause: FailureCause.Provider): FailureCause.Provider.TooLong? =
        (cause as? FailureCause.Provider.TooLong)?.takeIf { it.limitTokens != null }
}
