// PORT-OF: splice/gateway/head/HeadServer.kt (prepareTurn, Preparation/Ready/Rejected) @ 1caedd6 —
// invariants unchanged: parse → validate → classify → build as ONE named phase, with a single scan
// of system + last-user text shared by classification and shadow instrumentation, and the
// forwarded-header merge whose operand order (`prepared.extraHeaders + forwardedClientHeaders`)
// is what makes the CALLER's value replace the provider's configured default. Split out (HD-24);
// Preparation WIDENED private nested -> internal, because HeadAdmission dispatches on it.
package splice.gateway.head

import io.ktor.server.application.ApplicationCall
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.gateway.compact.CompactClassifier
import splice.spi.BuiltTurn
import splice.spi.Provider

internal sealed class Preparation {
    data class Ready(val built: BuiltTurn, val stream: Boolean) : Preparation()
    data class Rejected(val message: String) : Preparation()
}

internal class TurnPreparation(
    private val provider: Provider,
    private val deps: HeadDeps,
    private val bodyReader: RequestBodyReader,
    private val bodyParse: AnthropicBodyParse,
    private val clientAuth: ClientAuth,
) {
    private val compactClassifier = CompactClassifier()

    suspend fun prepareTurn(call: ApplicationCall, perf: TurnPerf): Preparation {
        val body = bodyReader.receiveBodyBounded(call, deps.maxRequestBytes)
        perf.mark(PerfKeys.RECV)
        perf.setCount(PerfKeys.REQ_BYTES, body.bytes.toLong())
        val parsed = bodyParse.parseOrNull(body.text)
            ?: return Preparation.Rejected("invalid request body")
        val unwrappedModel = provider.catalog.unwrap(parsed.typed.model)
        if (!provider.catalog.contains(parsed.typed.model)) {
            return Preparation.Rejected("this head proxies its own models only; got $unwrappedModel")
        }

        // One scan of system + last-user text: classification and shadow instrumentation share it.
        val compactProbe = compactClassifier.classifyCompact(parsed.typed)
        deps.shadow.record(parsed.typed, compactProbe)
        perf.mark(PerfKeys.PARSE)
        val prepared = provider.buildTurn(
            parsed,
            compactProbe.compact,
            call.request.headers["x-claude-code-session-id"],
        )
        // Per-turn headers already outrank the provider's own in TurnDriver's merge, so a forwarded
        // value REPLACES a configured default (e.g. the caller's anthropic-version wins over the
        // provider's), and UpstreamClient folds the casing.
        val built = if (deps.forwardClientAuth) {
            prepared.copy(extraHeaders = prepared.extraHeaders + clientAuth.forwardedClientHeaders(call))
        } else {
            prepared
        }
        perf.mark(PerfKeys.BUILD)
        return Preparation.Ready(built, parsed.typed.stream)
    }
}
