// NEW: the shared base for every openai-responses Provider (codex / grok / openai-platform),
// extracted 2026-07-18 (craft review). buildTurn + streamTranslator were byte-identical across the
// three providers — the exact "port the neighbor, copies drift (v29 lesson)" failure the codebase
// legislates against, moved one layer up from the dialect. Subclasses now supply ONLY what genuinely
// differs: their quirk profile, extraHeaders, and (grok) a per-turn header hook. The reasoning-policy
// wiring — include-encrypted when shown, input-replay only on operator opt-in, emit redacted_thinking
// on the stream — lives here ONCE.
package splice.dialect.responses

import splice.core.auth.Credentials
import splice.core.parse.AnthropicTurnBody
import splice.core.reasoning.decodeReasoningEnvelope
import splice.core.reasoning.encodeReasoningEnvelope
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.util.DaemonLog
import splice.spi.BuiltTurn
import splice.spi.FoldController
import splice.spi.Provider
import splice.spi.ProviderIdentity
import splice.spi.ProviderTuning
import splice.spi.ReanchorController
import splice.spi.StreamTranslator
import splice.spi.TurnSignals
import splice.spi.UpstreamClient
import splice.spi.WsRoundRunner
import splice.spi.WsUpstream

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
    private val log: (String) -> Unit = DaemonLog::write,
) : Provider, ProviderIdentity by tuning {

    final override val upstreamUrl: String = "${tuning.baseUrl}/responses"
    private val builder = ResponsesRequestBuilder(quirks)

    /** Per-turn upstream headers beyond the shared Accept set (grok's x-grok-conv-id). Empty by
     *  default — a header that depends on the turn/session rides HERE, never on shared state. */
    protected open fun perTurnHeaders(sessionId: String?): Map<String, String> = emptyMap()

    final override fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn {
        val upstreamModel = catalog.stripSuffixes(body.typed.model)
        val showOn = !showReasoning.isOff
        val built = builder.build(
            body.typed,
            body.raw,
            BuildOptions(
                compact = compact,
                originalModel = body.typed.model,
                upstreamModel = upstreamModel,
                // Config-driven (TOML [daemon] / env / state); "none" suppresses when display is off.
                configEffort = configEffort,
                configSummary = if (showOn) configSummary else "none",
                showReasoning = showReasoning,
                // LEGACY client-round-trip replay (redacted_thinking through Claude Code) —
                // operator opt-in only; superseded by the gateway-held reasoning cache below.
                replayReasoning = InjectPriorReasoning(replayReasoning),
                // Ask for the opaque encrypted handle whenever reasoning is visible OR the
                // reasoning cache needs it (RC-5: the cache can only hold what the server returns).
                includeEncryptedReasoning = RequestEncryptedReasoning(
                    (showOn && !compact) || reasoningCacheActive(quirks, compact),
                ),
                sessionId = sessionId,
                decodeReasoningEnvelope = { decodeReasoningEnvelope(it) },
                // RC-5: gateway-held reasoning continuity — the turn that emitted these tool ids
                // left its plan in the cache; reinject it so the model resumes instead of
                // re-deriving (codex parity; repeated-tool-call amnesia otherwise). Scoped to
                // THIS conversation (same first-message hash the builder stamps on TurnMeta).
                // ONE atomic snapshot per build (review of #71 round 2): per-block lookups could
                // tear across a concurrent eviction (rounds 1..k injected, k+1.. missing), re-ran
                // the first-message SHA-256 per block, and re-touched the conversation per block.
                // Lazy so a build with no tool_use blocks never touches the cache at all.
                reasoningLookup = if (!reasoningCacheActive(quirks, compact)) {
                    { null }
                } else {
                    val snapshot = lazy { reasoningCache.snapshot(stablePromptCacheKey(body.typed)) }
                    ({ id -> snapshot.value[id] })
                },
                // The provider's capability latch, read at build time: false = a shape-400 already
                // closed it this daemon lifetime; build the full status-quo request instead.
                toolSurfaceOpen = toolSurfaceLatch.open,
            ),
        )
        return BuiltTurn(
            built.req,
            built.meta,
            perTurnHeaders(sessionId) + liteHeaders(built.meta),
            toolSearch = built.toolSearch,
        )
    }

    /** codex-rs sends this marker header for responses-lite (5.6-family) turns; compact turns keep
     *  the normal shape so the header stays off there too (mirrors the builder's lite gate). */
    private fun liteHeaders(meta: TurnMeta): Map<String, String> =
        if (!meta.compact && quirks.responsesLiteModelRegex?.containsMatchIn(meta.upstreamModel) == true) {
            mapOf("x-openai-internal-codex-responses-lite" to "true")
        } else {
            emptyMap()
        }

    final override fun streamTranslator(meta: TurnMeta, signals: TurnSignals): StreamTranslator =
        ResponsesStreamTranslator(
            StreamTurnContext(
                compact = meta.compact,
                // STREAM-side emission of redacted_thinking wire blocks (so Claude Code stores the
                // handle for the NEXT turn's replay). COUPLED to replayReasoning (2026-07-20): a
                // handle the gateway will never inject back is pure cost — each redacted_thinking
                // block is a content_block_start with NO thinking_delta, which Claude Code renders as
                // a permanent empty "✳ Thinking…" spinner; a deep turn emits dozens (the "walls of
                // Thinking" report). With replay OFF (default) the whole transcript-replay loop is off
                // end-to-end: no empty spinners, and reasoning is re-derived fresh (deeper) each turn.
                // The live summary thinking blocks (reasoning_summary_text deltas) are a SEPARATE path
                // and still display. Fold's own intra-turn reasoning replay is independent of this.
                emitEncryptedReasoning = EmitEncryptedReasoning(showOn() && replayReasoning),
                encodeReasoningEnvelope = { encodeReasoningEnvelope(it) },
                clientGone = signals.clientGone,
                watchdogFired = signals.watchdogFired,
                streamIdleMsForMessage = watchdog.streamIdle.inWholeMilliseconds,
                upstreamTimeoutMsForMessage = watchdog.totalCap.inWholeMilliseconds,
                dedupeRepeatedSummaryParts = quirks.summaryDelivery != null,
                summaryPartsShared = meta.summaryParts,
                // Collect this round's encrypted reasoning envelopes whenever a continuation
                // could consume them: fold replay (Success side) OR mid-stream re-anchor salvage
                // (Failure side) — i.e. every non-compact responses turn since re-anchor landed
                // (2026-07-24). Compact turns keep the collection off.
                collectReasoningEnvelopes = foldController(meta) != null || reanchorController(meta) != null ||
                    reasoningCacheActive(quirks, meta.compact),
                onTurnReasoning = { ids, envs ->
                    if (reasoningCacheActive(quirks, meta.compact)) {
                        reasoningCache.put(meta.conversationKey, ids, envs)
                    }
                },
            ),
        )

    // Non-null ONLY when folding is configured AND the turn's model is fold-eligible AND it is not a
    // compaction (a text summarizer requests no encrypted_content). Sol and every non-codex head get
    // null here → the gateway never buffers or loops → pure passthrough.
    final override fun foldController(meta: TurnMeta): FoldController? {
        val cfg = foldConfig ?: return null
        if (meta.compact || meta.upstreamModel !in cfg.models) return null
        return ResponsesFoldController(cfg, decodeReasoningEnvelope = { decodeReasoningEnvelope(it) })
    }

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
        if (!quirks.webSocket || !supportsWebSocket) {
            null
        } else {
            ResponsesWsRunner(
                transport = WsUpstream(log = log),
                session = ResponsesWsSession(),
                // Same path as upstreamUrl, on the WebSocket scheme (live spike receipt).
                wssUrl = upstreamUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://"),
                // Authorization is added HERE because the SSE path gets it from
                // UpstreamClient.applyAuth, which the WS path never goes through — without it every
                // handshake 401s and the overlay falls back to SSE forever, i.e. the feature simply
                // cannot work (found while adjudicating the review of #72).
                handshakeHeaders = { creds ->
                    val auth = when (creds) {
                        is Credentials.Bearer -> mapOf("Authorization" to "Bearer ${creds.token}")
                        is Credentials.ApiKey -> mapOf(creds.header to "${creds.prefix}${creds.key}")
                    }
                    auth + extraHeaders(creds) + mapOf("OpenAI-Beta" to WS_BETA_HEADER)
                },
                log = log,
            )
        }
    }

    // RC-2/RC-4: gateway-held reasoning continuity for tool round-trips (codex parity). One
    // cache per provider instance; capture and lookup wire in via buildTurn/streamTranslator.
    // The log sink surfaces the cache's two one-way transitions (freeze, bound eviction) in
    // /mgmt/logs — silent state loss here cost a two-day cache-drain investigation.
    private val reasoningCache: ReasoningCache = ReasoningCache(log = log)

    // The tool-surface capability latch (§1.3/§5.3): one AtomicBoolean per provider instance,
    // moving in exactly one direction — toward status quo. Read at build time (buildTurn), closed
    // by amendBodyOnFailure on a shape-400.
    private val toolSurfaceLatch = ToolSurfaceLatch()

    /** RC-4: a 400 rejecting stale encrypted reasoning strips the injected items and retries
     *  once (NEVER-BELOW-STATUS-QUO law); every other failure keeps the plain retry plan. A 400
     *  rejecting the tool-surface shape strips the tool_search entry, retries once, and closes the
     *  latch so every LATER turn on this provider instance builds the full status-quo request.
     *  Keyed off the SAME classifier as the retry plan's GIVE_UP (review 2026-07-24: a narrower
     *  literal match here let any upstream wording drift skip the recovery entirely).
     *  Honesty gap (review 2026-07-25, [ToolSurfaceLatch]'s KDoc has the full account): the amend
     *  return value here is eager-only for THIS turn — this function only ever sees
     *  (status, responseText, bodyJson), never the [ToolPartition] that would let it re-attach the
     *  deferred tools' schemas, so the recovery turn runs one turn below full status quo before
     *  the latch restores every later turn. [logToolSurfaceLatchClosed] makes that one-time degrade
     *  observable instead of silent. */
    final override fun amendBodyOnFailure(status: Int, responseText: String, bodyJson: String): String? = when {
        UpstreamClient.isEncryptedContentError(status, responseText) -> stripStaleReasoning(bodyJson, reasoningCache)
        isToolSurfaceRejection(status, responseText) -> dropToolSearchTool(bodyJson)?.also {
            if (toolSurfaceLatch.close()) logToolSurfaceLatchClosed()
        }
        else -> null
    }

    /** The latch's one observable signal, through the injected daemon sink so it reaches
     *  /mgmt/logs and not stderr alone (wall kt-no-println, 2026-07-27; it used to be a bare
     *  System.err.println on the premise that no logger reaches this module — one now does). Guarded by [ToolSurfaceLatch.close]'s CAS return, so this
     *  fires EXACTLY ONCE per provider instance — never once per turn, since every turn after the
     *  close reads the latch already-closed and never re-enters this branch. */
    private fun logToolSurfaceLatchClosed() {
        log(
            "[${quirks.providerTag}] tool-surface latch closed: backend rejected the tool_search " +
                "shape; this turn recovered eager-only (one turn below status quo), every later turn " +
                "on this provider instance builds the full eager surface.",
        )
    }

    // The controller is stateless — one cached instance serves every turn (a per-call
    // allocation here also ran per ROUND via the collectReasoningEnvelopes null-check).
    private val reanchorPolicy: ReanchorController by lazy {
        ResponsesReanchorController(decodeReasoningEnvelope = { decodeReasoningEnvelope(it) })
    }

    // Every non-compact responses turn is re-anchor eligible (compaction is unary/buffered — the
    // pre-handoff retry covers it). NB: fold-eligible turns get re-anchor via FoldRunner's
    // trigger-B, not ReanchorRunner (driveOneTurn routes fold first).
    final override fun reanchorController(meta: TurnMeta): ReanchorController? =
        if (meta.compact) null else reanchorPolicy

    private fun showOn(): Boolean = !showReasoning.isOff

    private companion object {
        /** The v2 Responses-WebSocket beta value codex-rs sends (codex-rs/core/src/client.rs:155),
         *  confirmed accepted by the live backend in the WS-0 spike. */
        const val WS_BETA_HEADER = "responses_websockets=2026-02-06"
    }
}
