// NEW: wire projection preserves the transport's redirected-round capability.
package splice.provider.codex.stream

import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.turn.GatewayCustomCall
import splice.core.turn.TurnOutcome
import splice.provider.codex.CodeModeBody
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRewrite
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeTurn
import splice.provider.codex.CodexCodeModeWire
import splice.upstream.InterceptedRoundPost
import splice.upstream.PostingTurnRow
import splice.upstream.ReanchorRound
import splice.upstream.RedirectableRoundPost
import splice.upstream.RoundBody
import splice.upstream.RoundBodyInterceptor
import splice.upstream.RoundBodyPost
import splice.upstream.RoundInterceptor
import splice.upstream.RoundResult
import splice.upstream.sse.WireSink
import splice.upstream.transport.UpstreamEnding

internal class CodeModeRoundInterceptor(
    private val turn: CodexCodeModeBridge.Turn,
    private val initialOuter: GatewayCustomCall?,
    private val disableParallel: Boolean,
    private val wire: CodexCodeModeWire,
    private val controller: CodexCodeModeTurn,
    private val streams: CodeModeStreams,
) : RoundInterceptor, RoundBodyInterceptor {
    private var recovery: CodeModeRecoveryHistory? = null

    override fun resumesSource(): Boolean = streams.owns(turn)

    override fun reanchor(round: ReanchorRound) {
        val partial = round.failure.partial ?: return
        val history = recovery ?: CodeModeRecoveryHistory(wire.body(RoundBody.Tree(round.requestBody)))
            .also { recovery = it }
        history.extend(partial)
    }

    /** The round as the head holds it: a tree is read as that tree, so nothing renders or reparses it here. */
    override suspend fun intercept(body: RoundBody, sink: WireSink, postRound: InterceptedRoundPost): RoundResult {
        val slot = CodeModeEndingSlot()
        // Detached readers record through this reference; the context element still covers in-context layers.
        val upstream = CodeModeUpstreamPosts.of(postRound, wire, slot)
        val outcome = withContext(slot) {
            controller.run(
                CodeModeRunInput(turn, initialOuter, disableParallel, wire.body(body), sink, upstream, recovery),
            )
        }
        val ending = slot.ending ?: return RoundResult.Outcome(outcome)
        // The turn ends on the ending, but the cut it made is still its own: the step's bill carried it.
        val cuts = (outcome as? TurnOutcome.Failure)?.salvagedUsage?.cutRounds ?: 0L
        if (cuts > 0) postRound.perf?.add(PerfKeys.CUT_SOURCE_ROUNDS, cuts)
        return RoundResult.Ended(ending)
    }

    override suspend fun intercept(bodyJson: String, sink: WireSink, postRound: InterceptedRoundPost): RoundResult =
        intercept(RoundBody.Text(bodyJson), sink, postRound)
}

/** The code-mode post for a round post: redirectable when the transport can redirect it, plain otherwise. */
internal object CodeModeUpstreamPosts {
    fun of(
        post: InterceptedRoundPost,
        wire: CodexCodeModeWire,
        slot: CodeModeEndingSlot? = null,
    ): CodeModeUpstreamPost =
        if (post is RedirectableRoundPost) {
            CodeModeRedirectablePost(post, wire, slot)
        } else {
            CodeModeUpstreamPost(post, wire, slot)
        }
}

/** How a code-mode round goes upstream: what it posts leaves out the dialect's tool_search pairs, and the
 *  turn's perf record rides through, so code mode's local work counts on the turn it serves. A post that
 *  declares RoundBodyPost is handed the body as it is; any other is handed its text.
 *
 *  [endings] is the intercepted turn's ending slot: detached reader coroutines do not carry the
 *  interceptor's withContext element, so whichever layer meets an UpstreamEnding records it here. */
internal open class CodeModeUpstreamPost(
    private val target: InterceptedRoundPost,
    protected val wire: CodexCodeModeWire,
    val endings: CodeModeEndingSlot? = null,
) {
    val perf: TurnPerf? get() = target.perf

    /** The upstream ended this post without an outcome: hand it to the turn that wraps this dispatch. */
    fun record(ending: UpstreamEnding) {
        endings?.record(ending)
    }

    suspend fun canonicalize(
        body: CodeModeBody,
        records: List<CodeModeRecord>,
        media: Map<String, List<JsonElement>>,
        capture: CodeModeRecord? = null,
    ): CodeModeRewrite = perf?.timed(PerfKeys.CODE_MODE_CANONICAL_MS) {
        wire.canonicalize(body, records, media, capture)
    } ?: wire.canonicalize(body, records, media, capture)

    suspend operator fun invoke(body: CodeModeBody): TurnOutcome {
        val posted = wire.upstream(body)
        return when (val result = if (target is RoundBodyPost) target.post(posted) else target(posted.text)) {
            is RoundResult.Outcome -> result.outcome
            is RoundResult.Ended -> {
                record(result.ending)
                CodeModeEndings.placeholder()
            }
        }
    }
}

/** The same for a post the transport can redirect, so a script can park while its round streams on. */
internal class CodeModeRedirectablePost(
    private val target: RedirectableRoundPost,
    wire: CodexCodeModeWire,
    slot: CodeModeEndingSlot? = null,
) : CodeModeUpstreamPost(target, wire, slot) {
    /** The posting turn's row, for a round still streaming when that turn returns. */
    val postingRow: PostingTurnRow? get() = target.postingRow

    suspend fun into(body: CodeModeBody, sink: WireSink): RoundResult {
        val posted = wire.upstream(body)
        return if (target is RoundBodyPost) target.postInto(posted, sink) else target.into(posted.text, sink)
    }
}

/** The complete client step handed to the serial turn coordinator. */
internal data class CodeModeRunInput(
    val turn: CodexCodeModeBridge.Turn,
    val initialOuter: GatewayCustomCall?,
    val disableParallel: Boolean,
    val body: CodeModeBody,
    val sink: WireSink,
    val post: CodeModeUpstreamPost,
    val recovery: CodeModeRecoveryHistory? = null,
)
