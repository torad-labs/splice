// NEW: Muse keeps its per-head Responses alias map and static headers apart from OAuth account wiring.
package splice.app.provider

import splice.core.auth.Credentials
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.dialect.responses.CacheKeyStrategy
import splice.dialect.responses.ResponsesProvider
import splice.dialect.responses.ResponsesQuirks
import splice.upstream.ProviderTuning
import splice.upstream.ToolNameShortener
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

// why: clear the UUID version nibble before setting version four on the digest-derived ID.
private const val UUID_VERSION_MASK = -0xF001L

// why: version four marks the stable gateway-routing UUID with the canonical version nibble.
private const val UUID_VERSION_FOUR = 0x4000L

// why: clear the upper UUID variant bits before setting the RFC variant on the second half.
private const val UUID_VARIANT_MASK = 0x3FFF_FFFF_FFFF_FFFFL

internal data class MuseResponsesOptions(
    val showReasoning: ReasoningDisplay,
    val replayReasoning: Boolean,
    val configEffort: String?,
    val configSummary: String?,
    val quirks: ResponsesQuirks,
    val headers: Map<String, String>,
    val toolNames: ToolNameShortener,
)

internal class MuseResponsesProvider(
    tuning: ProviderTuning,
    private val options: MuseResponsesOptions,
) : ResponsesProvider(
    tuning,
    options.showReasoning,
    options.replayReasoning,
    options.configEffort,
    options.configSummary,
    options.quirks,
    toolNames = options.toolNames,
) {
    override fun extraHeaders(creds: Credentials): Map<String, String> = options.headers

    override fun perTurnHeaders(meta: TurnMeta): Map<String, String> {
        if (options.quirks.promptCache.key == CacheKeyStrategy.OFF) return emptyMap()
        // The same conversation key as prompt_cache_key; no client identity or raw session ID rides.
        val key = meta.sessionId?.let { "${options.quirks.providerTag}:$it" }
            ?: meta.conversationKey ?: return emptyMap()
        return mapOf("x-meta-ai-gateway-session-id" to gatewaySessionId(key))
    }

    private fun gatewaySessionId(key: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("splice-muse:$key".toByteArray(Charsets.UTF_8))
        val buffer = ByteBuffer.wrap(bytes)
        val most = (buffer.long and UUID_VERSION_MASK) or UUID_VERSION_FOUR
        val least = (buffer.long and UUID_VARIANT_MASK) or Long.MIN_VALUE
        return UUID(most, least).toString()
    }
}
