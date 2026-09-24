// NEW: v0.4.0 FEATURES.md §10 — what makes a provider "local" when the operator did not say —
// the openai-chat dialect on a loopback address, which is how every user-managed runtime (Ollama
// :11434, LM Studio :1234, vLLM :8000) is reached. Kept as a class so the rule has one home and
// one test; the probe that talks to the runtime lives in the chat dialect.
package splice.core.topology

import java.net.URI

public class LocalProviderRule {
    private val loopbackHosts = setOf("localhost", "127.0.0.1", "::1", "[::1]", "0.0.0.0")

    public fun isLocalByDefault(dialect: Dialect, baseUrl: String): Boolean =
        dialect == Dialect.OPENAI_CHAT && isLoopback(baseUrl)

    public fun isLoopback(baseUrl: String): Boolean =
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-24: a base URL that does not parse is not loopback
        runCatching { URI(baseUrl).host }.getOrNull()?.lowercase() in loopbackHosts
}
