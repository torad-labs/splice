// NEW: v0.4.0 FEATURES.md §10 — what makes a provider "local" when the operator did not say —
// the openai-chat dialect on a loopback address, which is how every user-managed runtime (Ollama
// :11434, LM Studio :1234, vLLM :8000) is reached. Kept as a class so the rule has one home and
// one test; the probe that talks to the runtime lives in the chat dialect.
package splice.core.topology

import java.net.URI
import java.net.URISyntaxException

internal class LocalProviderRule {
    private val loopbackHosts = setOf("localhost", "127.0.0.1", "::1", "[::1]", "0.0.0.0")

    fun isLocalByDefault(dialect: Dialect, baseUrl: String): Boolean =
        dialect == Dialect.OPENAI_CHAT && isLoopback(baseUrl)

    fun isLoopback(baseUrl: String): Boolean =
        // A base URL that does not parse is not loopback.
        host(baseUrl)?.lowercase() in loopbackHosts

    private fun host(baseUrl: String): String? = try {
        URI(baseUrl).host
    } catch (_: URISyntaxException) {
        null
    }
}
