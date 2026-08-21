// NEW: (no Node source) the ONE provider for the anthropic-passthrough dialect. Every vendor on
// this dialect differs only in DECLARED data — quirks (which deformations its wire needs), static
// headers, and whether it has computed per-install identity state — so there is no vendor subclass
// to write. Kimi was the dialect's only consumer and its provider class held the vendor facts as
// code; the claude head needs the same dialect with none of them (campaign claude-head, CH-3).
//
// What stays in code here is what is genuinely invariant for the dialect: the Anthropic Messages
// path, `Accept: text/event-stream`, and OFF reasoning display (passthrough emits REAL thinking
// blocks, so the transcript text-mirror must not double-render them).
//
// Auth is applied by UpstreamClient from the head's AuthProvider — this provider NEVER sets an
// Authorization header itself, which is what lets kimi ride Credentials.ApiKey(x-api-key) and a
// client-auth head forward the caller's own credential untouched.
package splice.dialect.passthrough

import splice.core.auth.Credentials
import splice.core.parse.AnthropicTurnBody
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.spi.BuiltTurn
import splice.spi.Provider
import splice.spi.ProviderIdentity
import splice.spi.ProviderTuning
import splice.spi.StreamTranslator
import splice.spi.TurnSignals

public class PassthroughProvider(
    private val tuning: ProviderTuning,
    private val quirks: PassthroughQuirks,
    /** Operator-declared vendor headers (TOML `extra_headers`) — e.g. `anthropic-version`, a UA a
     *  vendor gates on. Pure data, which is what makes a new anthropic-compatible vendor TOML-only. */
    private val staticHeaders: Map<String, String> = emptyMap(),
    /** COMPUTED per-install identity a vendor requires (Kimi's persisted X-Msh-* device set). A
     *  function, not config, precisely because it cannot be declared; absent for every other head. */
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

    private val builder = PassthroughRequestBuilder(quirks, configEffort)

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

    override fun streamTranslator(meta: TurnMeta, signals: TurnSignals): StreamTranslator =
        PassthroughStreamTranslator(
            PassthroughTurnContext(
                clientGone = signals.clientGone,
                watchdogFired = signals.watchdogFired,
                idleCapMs = watchdog.streamIdle.inWholeMilliseconds,
                totalCapMs = watchdog.totalCap.inWholeMilliseconds,
            ),
            quirks,
        )

    override fun extraHeaders(creds: Credentials): Map<String, String> = buildMap {
        put(ACCEPT, SSE_CONTENT_TYPE) // dialect invariant: this upstream streams SSE
        putAll(staticHeaders)
        putAll(identityHeaders())
    }
}

private const val ACCEPT = "Accept"
private const val SSE_CONTENT_TYPE = "text/event-stream"
