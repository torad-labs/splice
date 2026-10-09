// NEW: which auth kinds a head's provider and dialect may carry, and the sentence that names a pairing that may not.
package splice.app.provider

import splice.core.topology.AuthKind
import splice.core.topology.AuthKindRegistry
import splice.core.topology.Dialect
import splice.core.topology.DialectWires
import splice.core.topology.ProviderConfig

/** Registered auth kinds are promises with a finite compatibility matrix. Kimi OAuth also binds to the Kimi
 *  provider ID that owns its Moonshot wire identity. Unknown/custom kinds intentionally retain the api-key
 *  fallback in each arm. */
internal object AuthCompatibility {
    /** Why [headKey] cannot carry its provider's auth kind on that provider's dialect, or null when it can. The
     *  sentence names the head, kind, provider and dialect and quotes no file content, so a log may print it whole. */
    fun refusal(headKey: String, provider: String, providerCfg: ProviderConfig): String? {
        val kind = AuthKindRegistry.from(providerCfg.auth.kind) ?: return null
        val dialect = providerCfg.dialect
        if (isCompatible(kind, dialect, provider)) return null
        return "head '$headKey' has incompatible auth kind '${kind.wire}' " +
            "for provider '$provider' and dialect '${DialectWires.name(dialect)}'"
    }

    private fun isCompatible(kind: AuthKind, dialect: Dialect, provider: String): Boolean = when (kind) {
        AuthKind.ChatgptOAuth -> dialect == Dialect.OPENAI_RESPONSES
        AuthKind.GrokOAuth -> dialect == Dialect.OPENAI_RESPONSES || dialect == Dialect.OPENAI_CHAT
        AuthKind.KimiOAuth -> dialect == Dialect.ANTHROPIC_PASSTHROUGH && provider == "kimi"
        AuthKind.MuseOAuth -> museDialectAllowed(dialect, provider)
        AuthKind.Client -> dialect == Dialect.ANTHROPIC_PASSTHROUGH
    }

    private fun museDialectAllowed(dialect: Dialect, provider: String): Boolean =
        provider == "muse" && (dialect == Dialect.OPENAI_RESPONSES || dialect == Dialect.ANTHROPIC_PASSTHROUGH)
}
