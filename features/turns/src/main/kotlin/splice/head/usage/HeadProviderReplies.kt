// NEW: every product send observes the same head answer and the actual credential's refusal hold.
package splice.head.usage

import splice.core.auth.AuthProvider
import splice.core.auth.ClientAuthProvider
import splice.core.auth.RefreshableAuthProvider
import splice.core.usage.PlanLimit
import splice.core.usage.QuotaHeaderRead
import splice.core.wire.HttpStatus
import splice.head.HeadDeps
import splice.head.transport.StreamAnswerObserver
import splice.upstream.RetryNotice
import splice.upstream.StreamStart
import splice.upstream.credentials.PoolAccount
import splice.upstream.retry.RateLimitCooldown
import splice.upstream.transport.ProviderAnswerObserver

// why: response observation is milliseconds while a declared plan reset uses epoch seconds.
private const val REPLY_SECOND_MS = 1_000L

/** Borrows one assembled head's existing lifetime and stores; no second tracker or cooldown exists. */
internal class HeadProviderReplies(
    private val deps: HeadDeps,
    private val head: RefreshableAuthProvider,
) : ProviderReplyObserver {
    override fun posted(sender: ProviderReplySender) {
        sender.acceptance = cooldown(sender)?.acceptance()
    }

    override fun observed(reply: ProviderReply, sender: ProviderReplySender) {
        val auth = sender.auth as? RefreshableAuthProvider
        val plan = plan(reply, auth)
        deps.stores.usageStore.observeProviderAnswer(
            sender.auth,
            reply.status,
            reply.observedAtEpochMs,
            quotaRefused = reply.status == HttpStatus.TOO_MANY_REQUESTS && plan != null,
            credentialKey = sender.credentialKey,
        )
        if (reply.status in HttpStatus.OK..HttpStatus.MAX_SUCCESS && sender.auth !== head) {
            (head as? ClientAuthProvider)?.upstreamAnswered(reply.status, true)
        }
        val cooldown = cooldown(sender) ?: return
        when {
            reply.status in HttpStatus.OK..HttpStatus.MAX_SUCCESS -> sender.acceptance?.invoke()
            reply.status == HttpStatus.TOO_MANY_REQUESTS ||
                auth?.isQuotaExhausted(reply.status, reply.body.orEmpty()) == true -> {
                refused(reply, sender, cooldown, plan)
            }
        }
    }

    private fun cooldown(sender: ProviderReplySender): RateLimitCooldown? {
        val selected = sender.selectedAccount
        if (selected != null) {
            return selected.takeIf { owned ->
                deps.quotaBundle.activePool?.members?.any { it === owned } == true
            }?.cooldown
        }
        return sender.account?.let { deps.quotaBundle.activePool?.responseCooldowns?.get(it) }
            ?: deps.traffic.upstream.credentialCooldown(sender.requestHeaders, sender.declaredCarrier)
    }

    private fun plan(reply: ProviderReply, auth: RefreshableAuthProvider?): PlanLimit? {
        val nowSeconds = reply.observedAtEpochMs / REPLY_SECOND_MS
        return auth?.planLimit(reply.headers, nowSeconds)
            ?: reply.body?.let { auth?.planLimitFromBody(it, nowSeconds) }
    }

    private fun refused(
        reply: ProviderReply,
        sender: ProviderReplySender,
        cooldown: RateLimitCooldown,
        plan: PlanLimit?,
    ) {
        cooldown.captureProviderReset(reply.body)
        val planWait = plan?.let { cooldown.planHold.hold(it, RetryNotice { }) } ?: 0L
        val namedWait = maxOf(planWait, cooldown.providerUnavailableForMs())
        if (namedWait > 0L) {
            cooldown.arm(namedWait)
            if (sender.account != null) cooldown.markUnavailable(namedWait)
        }
    }
}

/** Normal turns and independent sends retain the same credential-owner-scoped answer. */
internal class TurnProviderAnswers(
    private val store: UsageStore,
    private val sender: RefreshableAuthProvider,
    private val head: RefreshableAuthProvider,
) : ProviderAnswerObserver {
    private var credentialKey: String? = null

    fun sent(key: String?) {
        credentialKey = key
    }

    override fun observed(status: Int, observedAtEpochMs: Long) {
        store.observeProviderAnswer(sender, status, observedAtEpochMs, credentialKey = credentialKey)
        if (status in HttpStatus.OK..HttpStatus.MAX_SUCCESS && sender !== head) {
            (head as? ClientAuthProvider)?.upstreamAnswered(status, true)
        }
    }

    override fun quotaRefused(status: Int, observedAtEpochMs: Long) {
        store.observeProviderAnswer(
            sender,
            status,
            observedAtEpochMs,
            quotaRefused = true,
            credentialKey = credentialKey,
        )
    }
}

/** WebSocket answers carry acceptance, never invented HTTP statuses. */
internal class TurnStreamAnswers(private val store: UsageStore) : StreamAnswerObserver {
    override fun observed(accepted: Boolean, at: Long, sender: AuthProvider, credentialKey: String?) {
        store.observeProviderStreamAnswer(sender, accepted, at, credentialKey)
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
    /** The captured owner, so a removed label cannot redirect an old reply to its replacement. */
    public val selectedAccount: PoolAccount? = null,
    /** Same private join key as credential quota readings, captured from this send's effective headers. */
    public val credentialKey: String? = null,
) {
    /** Local posting receipt; absent observation evidence never clears a hold. */
    internal var acceptance: StreamStart? = null

    override fun toString(): String = "ProviderReplySender(account=$account, requestHeaders=<redacted>)"
}

/** Borrows the head's existing stores and holds; it never sends, retries, or alters the request. */
public fun interface ProviderReplyObserver {
    /** Captures the hold state immediately before this sender posts, without altering its wire. */
    public fun posted(sender: ProviderReplySender) { }

    public fun observed(reply: ProviderReply, sender: ProviderReplySender)
}
