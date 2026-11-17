// NEW (Nov 17, 2026): Claude Code's side requests, recognized so a head's own instructions stay out of them.
//
// Beside a session's turns Claude Code asks a model small questions of its own: a label for a run of tool calls, a
// title for the conversation, a probe with no system text at all. Such a request carries no tools, and its system text
// is the question's own instruction. A head's standing prompt layers and a team slot's text were written for the coding
// agent's turns. A head in replace mode put its prompt where the instruction was, so the model was never asked the
// question: on one day 135 of 213 tool-use summaries read "I don't see a request in your message", and a probe with no
// system text gained the head's whole prompt. The auto-mode review was the first of these found (AutoModeReview,
// Nov 14, 2026); this is the rest of the class.
//
// The match is two facts splice already reads from a request: the client names itself Claude Code in its user agent,
// and the request carries no tools. A session's own turn, main thread or subagent, carries tools. Another client's
// request is never matched: a prompt an operator set for a team's API callers rides on it, tools or not.
package splice.head.turn.review

import splice.core.client.ClientVersionParser
import splice.core.perf.TurnPerf
import splice.core.wire.AnthropicRequest

/** Whether a request is one Claude Code sends beside a session's turns, said on the turn's perf row when it is. */
internal class SideRequest {
    private val versions = ClientVersionParser()

    /** True for a request from Claude Code that carries no tools. */
    fun recognized(request: AnthropicRequest, userAgent: String?, perf: TurnPerf): Boolean {
        if (request.tools.isNotEmpty() || versions.fromUserAgent(userAgent) == null) return false
        perf.setCount(SIDE_REQUEST, 1L)
        return true
    }
}

/** Set to 1 on a turn that was a Claude Code side request, which went upstream without the head's prompt layers or a
 *  slot's text. */
internal const val SIDE_REQUEST: String = "side_request"
