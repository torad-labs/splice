// Upstream transport tests have no concrete dialect. Their explicit reader uses the rows shared by Chat and
// Responses; production ReplyReader implementations live only in those dialect modules.
package splice.upstream.testing

import splice.core.failure.FailureCause
import splice.upstream.failure.FailedEvent
import splice.upstream.failure.FailedReply
import splice.upstream.failure.OpenAiReplies
import splice.upstream.failure.ReplyReader

public object OpenAiTestReplies : ReplyReader {
    override fun http(reply: FailedReply): FailureCause.Provider = OpenAiReplies.http(reply)

    override fun event(event: FailedEvent): FailureCause.Provider = OpenAiReplies.event(event)
}
