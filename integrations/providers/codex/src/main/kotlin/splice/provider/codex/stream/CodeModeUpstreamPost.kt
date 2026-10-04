// NEW: wire projection preserves the transport's redirected-round capability.
package splice.provider.codex.stream

import splice.core.perf.TurnPerf
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.provider.codex.CodeModeRunInput
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeTurn
import splice.provider.codex.CodexCodeModeWire
import splice.upstream.InterceptedRoundPost
import splice.upstream.RedirectableRoundPost
import splice.upstream.RoundInterceptor
import splice.upstream.sse.WireSink

internal class CodeModeRoundInterceptor(
    private val turn: CodexCodeModeBridge.Turn,
    private val initialOuter: GatewayCustomCall?,
    private val disableParallel: Boolean,
    private val wire: CodexCodeModeWire,
    private val controller: CodexCodeModeTurn,
    private val streams: CodeModeStreams,
) : RoundInterceptor {
    override fun resumesSource(): Boolean = streams.owns(turn)

    override suspend fun intercept(bodyJson: String, sink: WireSink, postRound: InterceptedRoundPost): TurnOutcome {
        val upstream = if (postRound is RedirectableRoundPost) {
            CodeModeRedirectablePost(postRound, wire)
        } else {
            CodeModeUpstreamPost(postRound, wire)
        }
        return controller.run(CodeModeRunInput(turn, initialOuter, disableParallel, bodyJson, sink, upstream))
    }
}

/** The round post code mode is handed: what it posts leaves out the dialect's tool_search pairs, and
 *  the turn's perf record rides through, so code mode's local work counts on the turn it serves. */
internal open class CodeModeUpstreamPost(
    private val post: InterceptedRoundPost,
    private val wire: CodexCodeModeWire,
) : InterceptedRoundPost {
    override val perf: TurnPerf? get() = post.perf

    override suspend fun invoke(bodyJson: String): TurnOutcome = post(wire.upstream(bodyJson))
}

/** The same for a post the transport can redirect, so a script can park while its round streams on. */
internal class CodeModeRedirectablePost(
    private val post: RedirectableRoundPost,
    private val wire: CodexCodeModeWire,
) : CodeModeUpstreamPost(post, wire), RedirectableRoundPost {
    override suspend fun into(bodyJson: String, sink: WireSink): TurnOutcome = post.into(wire.upstream(bodyJson), sink)
}
