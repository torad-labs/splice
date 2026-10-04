// NEW: every product send observes the same head answer and the actual credential's refusal hold.
package splice.head.usage

import splice.core.auth.AuthProvider
import splice.core.auth.RefreshableAuthProvider
import splice.core.usage.QuotaHeaderRead
import splice.core.wire.HttpStatus
import splice.head.HeadDeps
import splice.upstream.RetryNotice
import splice.upstream.retry.RateLimitCooldown

// why: response observation is milliseconds while a declared plan reset uses epoch seconds.
private const val REPLY_SECOND_MS = 1_000L

/** Borrows one assembled head's existing lifetime and stores; no second tracker or cooldown exists. */
internal class HeadProviderReplies(private val deps: HeadDeps) : ProviderReplyObserver {
    override fun observed(reply: ProviderReply, sender: ProviderReplySender) {
        if (reply.body == null) deps.stores.usageStore.observeProviderAnswer(reply.status, reply.observedAtEpochMs)
        val cooldown = cooldown(sender) ?: return
        val auth = sender.auth as? RefreshableAuthProvider
        when {
            reply.status in HttpStatus.OK..HttpStatus.MAX_SUCCESS -> cooldown.answered()
            reply.status == HttpStatus.TOO_MANY_REQUESTS ||
                auth?.isQuotaExhausted(reply.status, reply.body.orEmpty()) == true -> {
                refused(reply, sender, cooldown)
            }
        }
    }

    private fun cooldown(sender: ProviderReplySender): RateLimitCooldown? =
        sender.account?.let { deps.quotaBundle.accountPool?.responseCooldowns?.get(it) }
            ?: deps.upstream.credentialCooldown(sender.requestHeaders, sender.declaredCarrier)

    private fun refused(reply: ProviderReply, sender: ProviderReplySender, cooldown: RateLimitCooldown) {
        val auth = sender.auth as? RefreshableAuthProvider
        cooldown.captureProviderReset(reply.body)
        val nowSeconds = reply.observedAtEpochMs / REPLY_SECOND_MS
        val plan = auth?.planLimit(reply.headers, nowSeconds)
            ?: reply.body?.let { auth?.planLimitFromBody(it, nowSeconds) }
        val planWait = plan?.let { cooldown.planHold.hold(it, RetryNotice { }) } ?: 0L
        val namedWait = maxOf(planWait, cooldown.providerUnavailableForMs())
        if (namedWait > 0L) {
            cooldown.arm(namedWait)
            if (sender.account != null) cooldown.markUnavailable(namedWait)
        }
    }
}

/** A real provider answer. Body is absent at headers and supplied only for a refusal's reset facts. */
public data class ProviderReply(
    public val status: Int,
    public val observedAtEpochMs: Long,
    public val headers: QuotaHeaderRead,
    public val body: String? = null,
) {
    override fun toString(): String = "ProviderReply(status=$status, observedAtEpochMs=$observedAtEpochMs)"
}

/** The sender that produced this answer, not a later pool choice. Never rendered or persisted. */
public data class ProviderReplySender(
    public val auth: AuthProvider,
    public val requestHeaders: Map<String, String>,
    public val account: String?,
    public val declaredCarrier: String?,
) {
    override fun toString(): String = "ProviderReplySender(account=$account, requestHeaders=<redacted>)"
}

/** Borrows the head's existing stores and holds; it never sends, retries, or alters the request. */
public fun interface ProviderReplyObserver {
    public fun observed(reply: ProviderReply, sender: ProviderReplySender)
}
