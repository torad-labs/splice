// NEW: wire projection preserves the transport's redirected-round capability.
package splice.provider.codex.stream

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
            CodeModeUpstreamPost(postRound, wire)
        } else {
            InterceptedRoundPost { body -> postRound(wire.upstream(body)) }
        }
        return controller.run(CodeModeRunInput(turn, initialOuter, disableParallel, bodyJson, sink, upstream))
    }
}

internal class CodeModeUpstreamPost(
    private val post: RedirectableRoundPost,
    private val wire: CodexCodeModeWire,
) : RedirectableRoundPost {
    override suspend fun invoke(bodyJson: String): TurnOutcome = post(wire.upstream(bodyJson))
    override suspend fun into(bodyJson: String, sink: WireSink): TurnOutcome = post.into(wire.upstream(bodyJson), sink)
}
