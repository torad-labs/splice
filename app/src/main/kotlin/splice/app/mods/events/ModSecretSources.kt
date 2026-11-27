package splice.app.mods.events

import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader
import splice.oauth.StoredCredential
import splice.upstream.transport.HeaderRedaction

/** Read through the same credential sources as the provider, including keys added after daemon start. */
internal class ModSecretSources(
    private val env: EnvReader,
    names: Set<String> = ProcessBuilder().environment().keys,
) {
    @Volatile var providers: Map<String, ProviderConfig> = emptyMap()

    private val environmentNames = (names + setOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN"))
        .filter { HeaderRedaction.isSecret(it) || "LOGIN" in it }
    private val stored = StoredCredential()

    fun read(): Set<String> = buildSet {
        environmentNames.mapNotNullTo(this, env::invoke)
        for ((key, provider) in providers) {
            val name = provider.auth.effectiveApiKeyEnv(key)
            env(name)?.let(::add)
            provider.staticHeaders.filterKeys(HeaderRedaction::isSecret).values.forEach(::add)
            stored.bearer(provider, key, env)?.let(::add)
        }
        removeIf(String::isBlank)
    }
}
