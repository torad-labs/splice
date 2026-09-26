// NEW: (no Node source) the ONE provider for the anthropic-passthrough dialect. Every vendor on
// this dialect differs only in assembly-provided data — quirks, static headers, and optional
// runtime host/OS identity — so there is no vendor subclass. Generic vendors declare their facts in
// TOML; Kimi OAuth/API-key arms receive compatibility defaults before TOML overrides. CLIENT stays
// neutral regardless of provider ID (campaign claude-head, CH-3).
//
// What stays in code here is what is genuinely invariant for the dialect: the Anthropic Messages
// path, `Accept: text/event-stream`, and OFF reasoning display (passthrough emits REAL thinking
// blocks, so the transcript text-mirror must not double-render them).
//
// Auth is applied by UpstreamClient from the head's AuthProvider — this provider NEVER sets an
// Authorization header itself: Kimi OAuth rides Credentials.ApiKey(x-api-key), Kimi API-key auth
// uses the configured Bearer path, and a client-auth head forwards the caller credential untouched.
package splice.dialect.anthropic

import splice.core.auth.Credentials
import splice.core.parse.AnthropicTurnBody
import splice.core.prompt.SystemPromptMode
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.upstream.BuiltTurn
import splice.upstream.Provider
import splice.upstream.ProviderIdentity
import splice.upstream.ProviderTuning
import splice.upstream.ReanchorController
import splice.upstream.StreamTranslator
import splice.upstream.TurnSignals

public class PassthroughProvider(
    private val tuning: ProviderTuning,
    private val quirks: PassthroughQuirks,
    /** Effective vendor headers selected by assembly: generic and CLIENT arms contribute TOML
     *  `extra_headers`; Kimi OAuth/API-key adds compatibility defaults that TOML can override. */
    private val staticHeaders: Map<String, String> = emptyMap(),
    /** Computed runtime identity headers a vendor requires (Kimi OAuth/API-key's X-Msh host and OS
     *  set). A function, not config, because these values are not operator declarations. */
    private val identityHeaders: IdentityHeaders = IdentityHeaders { emptyMap() },
    /** PT-002/v27: the daemon's configured default effort ([daemon] effort / Knob.EFFORT) — the
     *  session-stable proxy the request builder falls back to on a turn with no `thinking` config
     *  at all. See PassthroughThinking.effortLadder: this can only ever inform TurnMeta.effort
     *  — a turn that sends `thinking` without a budget keeps the wire-frozen EFFORT_MAX regardless
     *  (KIMI BYTE-IDENTITY). */
    private val configEffort: String? = null,
) : Provider, ProviderIdentity by tuning {

    // baseUrl carries no /v1 (topology supplies the host root); the Messages path is /v1/messages.
    override val upstreamUrl: String = "${tuning.baseUrl}/v1/messages"

    override val showReasoning: ReasoningDisplay = ReasoningDisplay.OFF
    override val replayReasoning: Boolean = false

    // V4-32: one instance per head. The builder shortens into it, every stream translator this
    // provider makes restores out of it, so a name rewritten on the way out is recoverable on
    // the way back for the life of the head. Off (cap 0) for every head but Muse.
    private val toolNames = ToolNameShortener(quirks.toolNameCap)
    private val builder = PassthroughRequestBuilder(quirks, configEffort, names = toolNames)
    private val compactionTail = PassthroughCompactionTail()
    private val systemPrompt = PassthroughSystemPrompt()

    override fun buildTurn(body: AnthropicTurnBody, compact: Boolean, sessionId: String?): BuiltTurn {
        val upstreamModel = catalog.stripSuffixes(body.typed.model)
        val built = builder.build(
            body = body,
            upstreamModel = upstreamModel,
            originalModel = body.typed.model,
            compact = compact,
        )
        return BuiltTurn(built.req, built.meta)
    }

    override fun withCompactionTail(turn: BuiltTurn, instructions: String): BuiltTurn =
        turn.copy(requestBody = compactionTail.append(turn.requestBody, instructions))

    override fun withSystemPrompt(turn: BuiltTurn, prompt: String, mode: SystemPromptMode): BuiltTurn =
        turn.copy(requestBody = systemPrompt.apply(turn.requestBody, prompt, mode))

    override fun streamTranslator(meta: TurnMeta, signals: TurnSignals): StreamTranslator =
        PassthroughStreamTranslator(
            PassthroughTurnContext(
                clientGone = signals.clientGone,
                watchdogFired = signals.watchdogFired,
                idleCapMs = watchdog.streamIdle.inWholeMilliseconds,
                totalCapMs = watchdog.totalCap.inWholeMilliseconds,
            ),
            quirks,
            names = toolNames,
        )

    /** The third retry layer, finally wired for this dialect. Until 2026-09-16 this returned the
     *  SPI default (null = surface the failure), so a stream that truncated mid-answer ended the
     *  turn at attempts=1 — the connect-phase and G5 budgets cannot see a 2xx that EOFs early, and
     *  this was the only layer that could. Stateless and cheap, so it is constructed per call
     *  rather than held. */
    override fun reanchorController(meta: TurnMeta): ReanchorController =
        PassthroughReanchorController(prefill = quirks.reanchorPrefill)

    override fun extraHeaders(creds: Credentials): Map<String, String> = buildMap {
        put(ACCEPT, SSE_CONTENT_TYPE) // dialect invariant: this upstream streams SSE
        putAll(staticHeaders)
        putAll(identityHeaders())
    }
}

private const val ACCEPT = "Accept"
private const val SSE_CONTENT_TYPE = "text/event-stream"
