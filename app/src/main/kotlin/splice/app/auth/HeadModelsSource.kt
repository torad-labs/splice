// MOVED: 2026-10-27 from ModelRosters.kt, unchanged — where a head's model list is asked, and the credential the ask
// presents. Beside StoredModelCredentials, which it asks through; ModelRosters (app.provider) keeps each list.
package splice.app.auth

import splice.core.auth.CredentialKey
import splice.core.auth.Credentials
import splice.core.topology.ProviderConfig
import splice.core.util.DaemonLog
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.models.discovery.Discovery
import splice.models.discovery.ModelDiscovery
import splice.oauth.StoredCredential

/** One head's provider asked what it serves, and the credential the ask presents. [EndpointModels] in production. */
internal fun interface HeadModelsSource {
    fun discover(key: String, provider: ProviderConfig): Discovery

    /** The credential [discover] presents for [key], as a one-way key that is never logged; null when it presents
     *  none. A source that presents nothing (a test's fake) is never asked again for a changed credential. */
    fun presenting(key: String, provider: ProviderConfig): String? = null
}

/** Production [HeadModelsSource]: the models feature's discovery, presenting the credential a head's turns present
 *  (StoredCredential.bearer, the same one `splice models` uses): an api-key head's key from the environment, its key
 *  file or the key store, in the head's own order, else the token a sign-in left in the head's credential file. Both
 *  read through one [StoredCredential], so a key store that cannot be read is said once per version however often it
 *  is reread. */
internal class EndpointModels(
    private val env: EnvReader = EnvReader(System::getenv),
    log: LogSink = LogSink(DaemonLog::write),
) : HeadModelsSource {
    private val stored = StoredCredential(log = log)
    private val discovery = ModelDiscovery(StoredModelCredentials(stored))

    override fun discover(key: String, provider: ProviderConfig): Discovery = discovery.discover(key, provider, env)

    override fun presenting(key: String, provider: ProviderConfig): String? =
        stored.bearer(provider, key, env)?.let { CredentialKey.fromCredentials(Credentials.Bearer(it)) }
}
