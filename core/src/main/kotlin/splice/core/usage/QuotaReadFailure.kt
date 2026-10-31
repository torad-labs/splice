// NEW: why a login's limits could not be read (Oct 12, 2026: Accounts' "Not read" names its reason). Each provider's
// quota probe ends in an answer or a failure; the failure is kept per head and login as a reason key and a time, so
// /api/accounts can say it. The key is the contract: the console words it, and no secret or body ever rides along.
package splice.core.usage

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/** Why a limits read failed, by the key /api/accounts sends. */
public enum class QuotaReadReason(public val wire: String) {
    /** The provider could not be reached or did not answer in time. */
    NOT_ANSWERING("not_answering"),

    /** The provider answered, but with a refusal or an error instead of usage. */
    REFUSED("refused"),

    /** The provider refused the login's credential (401 or 403), or splice holds none for it. */
    SIGNED_OUT("signed_out"),

    /** splice has nothing to read limits with for this login: a forwarded sign-in carries no token of splice's. */
    UNSUPPORTED("unsupported"),
}

/** The last limits read of one login failed, for [reason], at [atEpochSeconds]. */
public data class QuotaReadFailure(val reason: QuotaReadReason, val atEpochSeconds: Long) {
    /** `{reason, at_epoch_seconds}`, the shape of /api/accounts' `last_read_failure`. */
    public fun json(): JsonObject = buildJsonObject {
        put("reason", reason.wire)
        put("at_epoch_seconds", atEpochSeconds)
    }
}

/** Where one login's read ends: a failure, or null once a read answers. */
public fun interface QuotaReadOutcome {
    public fun read(failure: QuotaReadFailure?)
}

/** The last failed read of one login of one head, or null when its last read answered or none has failed. A
 *  single-login head's login is named by a null label. */
public fun interface QuotaReadFailureSource {
    public fun failure(head: String, label: String?): QuotaReadFailure?
}

/** The daemon's one record of failed limits reads, written by each login's poller and read by /api/accounts. Held in
 *  memory: the next read after a restart says it again within one poll. */
public class QuotaReadFailures : QuotaReadFailureSource {
    private val byLogin = ConcurrentHashMap<List<String>, QuotaReadFailure>()

    /** Where [head]'s login [label] reports each read; a null label is a single-login head's own. */
    public fun outcome(head: String, label: String?): QuotaReadOutcome = QuotaReadOutcome { failure ->
        val key = listOf(head, label ?: OWN_LOGIN)
        if (failure == null) byLogin.remove(key) else byLogin.put(key, failure)
    }

    override fun failure(head: String, label: String?): QuotaReadFailure? = byLogin[listOf(head, label ?: OWN_LOGIN)]
}

// why: the key a single-login head's own login is filed under; no pool label can be empty.
private const val OWN_LOGIN = ""
