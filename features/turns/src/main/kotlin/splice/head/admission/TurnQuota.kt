// NEW: V4-99 (arch-audit 2026-09-17) — the sole resolver for which QuotaTracker a turn reads.
//
// "Which quota tracker does this turn read?" used to be answered with an elvis chain at seven call
// sites, three different left operands, two right operands. The precedence is selected-account
// tracker, else the head's primary — and `?:` types nothing about that, so a site reaching for
// deps.quotaBundle.quota first compiles, runs, and stamps the WRONG account's anthropic-ratelimit-* headers.
// HeadAdmission.kt once carried a hand-written comment recording that exact bug fixed at ONE site
// while six siblings kept the old shape. This file is the single place the decision lives.
package splice.head.admission

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.auth.REFUSAL_FIELD
import splice.core.usage.QuotaFull
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.wire.RateLimitReply
import splice.head.usage.QuotaTracker
import splice.head.usage.TrackedAccountQuota
import splice.upstream.credentials.AccountPool
import splice.upstream.credentials.AccountSelection

/** The one resolver: the SELECTED account's tracker if a selection (or a sticky session) is in
 *  hand, else the head's primary tracker. Owned by [HeadDeps] and read through
 *  [HeadDeps.turnQuota], so no caller re-derives the chain. */
internal class TurnQuota(
    private val accountPool: AccountPool?,
    private val accountQuotas: Map<String, QuotaTracker>,
    private val primary: QuotaTracker?,
) {
    fun forSession(sessionId: String?, account: AccountSelection?): QuotaTracker? {
        (account?.account?.quota as? TrackedAccountQuota)?.let { return it.tracker }
        val label = account?.account?.label ?: accountPool?.view(sessionId)?.selectedLabel
        return label?.let(accountQuotas::get) ?: primary
    }

    /** No native login can take over. Each unavailable place names its own remedy without acquiring credentials. */
    suspend fun standbyRefusal(account: AccountSelection?): String? {
        val pool = accountPool?.takeIf { it.active } ?: return null
        if (pool.view(null).accounts.any { it.available }) return null
        val others = pool.members.filter { it.label.startsWith("native:") && it.label != account?.account?.label }
        val reasons = others.map { other ->
            val command = other.label.removePrefix("native:")
            val description = other.auth.describe()
            val reason = description.fields[REFUSAL_FIELD]
                ?: "its subscription is held; wait for its reported reset"
            "$command cannot take over: $reason."
        }
        return reasons.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    /** Preserve the provider status, error type and headers. Only its JSON message gains the takeover explanation. */
    fun withStandby(reply: RateLimitReply?, sentence: String?): RateLimitReply? {
        if (reply == null || sentence == null) return reply
        return Cancellables.runCatchingCancellable {
            val body = Json.parseToJsonElement(reply.body) as? JsonObject ?: return@runCatchingCancellable reply
            val error = body["error"] as? JsonObject ?: return@runCatchingCancellable reply
            val message = JsonScalars.str(error, "message") ?: return@runCatchingCancellable reply
            val changed = JsonObject(error + ("message" to JsonPrimitive("$message $sentence")))
            reply.copy(body = JsonWire.string(JsonObject(body + ("error" to changed))))
        }.fold(onSuccess = { it }, onFailure = { reply })
    }

    /** V4-452: the head's full reading by the providers' own CURRENT readings, null when there is none. A head
     *  reads full when every account it holds does, so a pool needs all of its trackers full and answers with the
     *  earliest reset; a head with no tracker never reads full. Head-wide, so no session or selection is asked. */
    fun full(): QuotaFull? {
        val current = accountPool?.takeIf { it.active }?.members
            ?.mapNotNull { accountQuotas[it.label] } ?: listOfNotNull(primary)
        val readings = current.map(QuotaTracker::full)
        if (readings.any { it == null }) return null
        return readings.filterNotNull().minByOrNull { it.resetsAtEpochSeconds }
    }
}
