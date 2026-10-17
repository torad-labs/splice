// NEW: LAYOUT-01 — the model probe's credential port over oauth's StoredCredential. It lived beside
// StoredCredential in app/auth until the reader moved to integrations/oauth; the adapter stays in app,
// the one module that may see both the models feature's port and the integration it adapts.
package splice.app.auth

import splice.core.topology.ProviderConfig
import splice.core.util.EnvReader
import splice.models.list.ModelCredentialSource
import splice.oauth.StoredCredential

/** The model probe's view of [StoredCredential]: the bearer a request presents and when it expires. */
internal class StoredModelCredentials(private val stored: StoredCredential = StoredCredential()) :
    ModelCredentialSource {
    override fun bearer(provider: ProviderConfig, key: String, env: EnvReader): String? =
        stored.bearer(provider, key, env)

    override fun expiresAtMs(provider: ProviderConfig): Long? = stored.expiresAtMs(provider)
}
