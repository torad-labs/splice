// NEW: the shared base for every openai-responses Provider (codex / grok / openai-platform),
// extracted 2026-07-18 (craft review). buildTurn + streamTranslator were byte-identical across the
// three providers — the exact "port the neighbor, copies drift (v29 lesson)" failure the codebase
// legislates against, moved one layer up from the dialect. Subclasses now supply ONLY what genuinely
// differs: their quirk profile, extraHeaders, and (grok) a per-turn header hook. The reasoning-policy
// wiring — include-encrypted when shown, input-replay only on operator opt-in, emit redacted_thinking
// on the stream — lives here ONCE.
package splice.dialect.responses

import splice.core.parse.AnthropicTurnBody
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.util.DaemonLog
import splice.core.util.LogSink
import splice.spi.BuiltTurn
import splice.spi.FoldController
import splice.spi.Provider
import splice.spi.ProviderIdentity
import splice.spi.ProviderTuning
import splice.spi.ReanchorController
import splice.spi.StreamTranslator
import splice.spi.TurnSignals
import splice.spi.WsRoundRunner

public abstract class ResponsesProvider(
    tuning: ProviderTuning,
    final override val showReasoning: ReasoningDisplay,
    final override val replayReasoning: Boolean,
    private val configEffort: String?,
    private val configSummary: String?,
    protected val quirks: ResponsesQuirks,
    // Reasoning-continuation folding (codex 518n-2). null = the feature is off for this provider —
    // grok/openai-platform pass nothing → pure passthrough. Only CodexProvider wires a real config.
    private val foldConfig: FoldConfig? = null,
    /** Daemon log sink (Main.persistentLogger): writes BOTH stderr and daemon.log, which is what
     *  /mgmt/logs tails. A bare System.err.println reaches stderr ONLY, so its line never appears in
     *  the log endpoint — the failure you most want to read is the one you cannot (wall
     *  kt-no-println, 2026-07-27). Defaults to a no-op so tests need not thread it; the daemon
     *  always injects the real sink. */
    private val log: LogSink = LogSink(DaemonLog::write),
) : Provider, ProviderIdentity by tuning {

    final override val upstreamUrl: String = "${tuning.baseUrl}/responses"

    // Collaborator wiring lives in ResponsesParts.kt (concentration, 2026-08-19).
    private val parts = ResponsesParts(
        ResponsesPartsInput(
            tuning = tuning,
            showReasoning = showReasoning,
            replayReasoning = replayReasoning,
            configEffort = configEffort,
            configSummary = configSummary,
            quirks = quirks,
            foldConfig = foldConfig,
            log = log,
            streamIdleMs = watchdog.streamIdle.inWholeMilliseconds,
            upstreamTimeoutMs = watchdog.totalCap.inWholeMilliseconds,
        ),
    )

    /** Per-turn upstream headers beyond the shared Accept set (grok's x-grok-conv-id). Empty by
     *  default — a header that depends on the turn/session rides HERE, never on shared state. */
    protected open fun perTurnHeaders(sessionId: String?): Map<String, String> = emptyMap()

    final override fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn {
        val built = parts.builder.build(body.typed, body.raw, parts.turnOptions.build(body, compact, sessionId))
        return BuiltTurn(
            built.req,
            built.meta,
            perTurnHeaders(sessionId) + parts.turnOptions.liteHeaders(built.meta),
            toolSearch = built.toolSearch,
        )
    }

    final override fun streamTranslator(meta: TurnMeta, signals: TurnSignals): StreamTranslator =
        parts.turnSeams.streamTranslator(meta, signals)

    final override fun foldController(meta: TurnMeta): FoldController? =
        parts.turnSeams.foldController(meta)

    /** Whether THIS provider's upstream actually speaks the Responses WebSocket. False by default:
     *  the quirk table is shared by every openai-responses provider (codex, grok, openai-platform),
     *  so an operator setting websocket = true under [providers.xai.quirks] would otherwise make
     *  grok open a WebSocket to api.x.ai and fail every round into SSE (review of #72). Only a
     *  provider that has PROVEN the protocol against its own upstream overrides this. */
    protected open val supportsWebSocket: Boolean = false

    /** ws-transport WS-3: non-null ONLY when the operator opted in AND this provider's upstream
     *  was actually probed. With the quirk off no WsUpstream is constructed and the request path is
     *  byte-identical to before the overlay landed — the property that makes it safe to ship.
     *
     *  LAZY, not an eager val: [supportsWebSocket] is overridden by subclasses, whose own
     *  properties are assigned AFTER this base constructor runs. Computing it eagerly read the
     *  override before it existed, so EVERY provider got null and the overlay could never arm —
     *  caught by WsQuirkWiringTest, and it would have silently disabled the feature in production. */
    final override val wsRunner: WsRoundRunner? by lazy {
        ResponsesWsSupport(log, extraHeaders = WsExtraHeaders { extraHeaders(it) })
            .runner(quirks.webSocket, supportsWebSocket, upstreamUrl)
    }

    final override fun amendBodyOnFailure(status: Int, responseText: String, bodyJson: String): String? =
        parts.failureAmend.amendBodyOnFailure(status, responseText, bodyJson)

    // Every turn, compaction included (2026-09-02, see ResponsesTurnSeams.reanchorController).
    final override fun reanchorController(meta: TurnMeta): ReanchorController? = parts.turnSeams.reanchorController()
}
