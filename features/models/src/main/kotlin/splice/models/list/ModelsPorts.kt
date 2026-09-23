// NEW: model-listing inputs and output belong to the feature; app supplies disk, credentials, and stdout.
package splice.models.list

import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader

/** The configured providers and the path named in command diagnostics. */
public data class ModelConfiguration(
    public val path: String,
    public val providers: Map<String, ProviderConfig>,
)

/** Loads the same provider configuration the daemon uses. */
public fun interface ModelConfigurationSource {
    public fun load(): ModelConfiguration
}

/** Reads a provider's credential without exposing its storage format to model discovery. */
public fun interface ModelCredentialSource {
    public fun bearer(provider: ProviderConfig, key: String, env: EnvReader): String?

    /** When the stored OAuth token this provider presents expires, in epoch milliseconds; null when
     *  there is none or it carries no expiry (an api-key). A refusal of an expired token is the head's
     *  refresh not yet run, never a login to redo, and the probe's sentence has to say which. */
    public fun expiresAtMs(provider: ProviderConfig): Long? = null
}

/** Writes an operator-facing model-report line, not a daemon log or a client-wire frame. */
public fun interface ModelReportOutput {
    public fun line(text: String): Unit
}
