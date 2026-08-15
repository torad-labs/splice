// NEW: (no Node source) the auth provider for a head that holds NO credential — the caller brings
// its own (campaign claude-head, CH-5). A splice head normally OWNS the upstream credential: it
// reads a vendor's auth file, refreshes it, and writes the header. For a claude head that would
// mean re-implementing a lifecycle the client already runs correctly (credentials, keychain,
// single-flight refresh, /login), against an undocumented token endpoint, and taking custody of a
// secret splice has no reason to hold. So this provider holds nothing and says so.
//
// It is still a RefreshableAuthProvider because ProviderTuning requires one and the transport's
// 401 path calls refresh(); both are INERT here. A 401 on this head means the CALLER's credential
// was rejected, and the caller — which kept its native /login — is the only party that can fix it.
package splice.core.auth

public class ClientAuthProvider(private val headKey: String) : RefreshableAuthProvider {

    /** Never null: a null would make the transport raise auth-missing before the request, and
     *  there IS no missing credential here — the one that matters rides on the inbound call. */
    override suspend fun credentials(): Credentials = Credentials.ClientForwarded

    override suspend fun describe(): AuthDescription = AuthDescription(
        present = true,
        kind = KIND,
        fields = mapOf("head" to headKey, "source" to "inbound request"),
    )

    /** Nothing to refresh. Returning the same forward-mode marker keeps the transport's retry path
     *  intact (it re-reads credentials and tries once more) without pretending a rotation happened. */
    override suspend fun refresh(): Credentials = Credentials.ClientForwarded

    /** A 401 here is the CALLER's credential being rejected upstream, never a stale splice token,
     *  so the refresh-and-retry dance can only burn a second upstream call. Let it surface. */
    override fun allowRefreshAfterFailure(status: Int, body: String): Boolean = false

    public companion object {
        public const val KIND: String = "client"
    }
}
