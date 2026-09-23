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
}
