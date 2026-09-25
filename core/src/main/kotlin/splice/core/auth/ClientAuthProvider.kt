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
//
// V4-220 item 6b: what it DOES see is upstream's answer to every forwarded turn (upstreamAnswered),
// so describe() reports that verdict instead of a presence it cannot know: unverified until a
// forwarded turn is answered, then accepted or rejected with the time of the last answer.
package splice.core.auth

import splice.core.topology.AuthKind
import splice.core.usage.PlanLimit
import splice.core.usage.QuotaHeaderRead
import splice.core.usage.planLimitOf
import splice.core.util.WallClock
import splice.core.wire.HttpStatus
import java.util.concurrent.atomic.AtomicReference

/** The `auth.kind` wire word for this provider. Was `ClientAuthProvider.KIND`; the companion it
 *  lived in is illegal since the 2026-08-16 style migration (HD-M8) and the law's sanctioned home
 *  for a constant is file scope. RENAMED on the way out — at package scope a bare `KIND` says
 *  nothing, and `import splice.core.auth.KIND` would be unreadable at the one call site.
 *  The literal MUST stay equal to [AuthKind.Client.wire]; LaunchSpecClientAuthTest pins that.
 *  `const` so AuthKinds.CLIENT can be `const` too (detekt TopLevelPropertyNaming). */
public const val CLIENT_AUTH_KIND: String = "client"

public class ClientAuthProvider(
    private val headKey: String,
    private val now: WallClock = WallClock(System::currentTimeMillis),
) : RefreshableAuthProvider {

    private val verdict = AtomicReference<CredentialVerdict>(CredentialVerdict.Unverified)

    /** Never null: a null would make the transport raise auth-missing before the request, and
     *  there IS no missing credential here — the one that matters rides on the inbound call. */
    override suspend fun credentials(): Credentials = Credentials.ClientForwarded

    /** [AuthDescription.present] is false only once upstream REJECTED the forwarded login: an
     *  unverified one is not known to be missing, and the verdict field says which it is. */
    override suspend fun describe(): AuthDescription {
        val last = verdict.get()
        return AuthDescription(
            present = last !is CredentialVerdict.Rejected,
            kind = CLIENT_AUTH_KIND,
            fields = mapOf("head" to headKey, "source" to "inbound request"),
            verdict = last,
        )
    }

    /** A 401 is the caller's login rejected; a success is it accepted. Any other answer (a 429, a
     *  5xx, a 400) says nothing about the credential and leaves the last verdict standing. */
    override fun upstreamAnswered(status: Int, success: Boolean) {
        when {
            status == HttpStatus.UNAUTHORIZED -> verdict.set(CredentialVerdict.Rejected(now()))
            success -> verdict.set(CredentialVerdict.Accepted(now()))
        }
    }

    /** V4-233: the forwarded login is a Claude subscription, answered in Anthropic's unified plan
     *  family, so its 429 can name a spent plan window and the reset it comes back at. */
    override fun planLimit(header: QuotaHeaderRead, nowEpochSeconds: Long): PlanLimit? =
        planLimitOf(header, nowEpochSeconds)

    /** Nothing to refresh. Returning the same forward-mode marker keeps the transport's retry path
     *  intact (it re-reads credentials and tries once more) without pretending a rotation happened. */
    override suspend fun refresh(): Credentials = Credentials.ClientForwarded

    /** A 401 here is the CALLER's credential being rejected upstream, never a stale splice token,
     *  so the refresh-and-retry dance can only burn a second upstream call. Let it surface. */
    override fun allowRefreshAfterFailure(status: Int, body: String): Boolean = false
}
