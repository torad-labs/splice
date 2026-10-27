// NEW: failure-handling slice 4. One ledger per turn, carried to every post and every round of it (TurnRetry), so a
// budget spent by the HTTP attempt loop is spent for the head's round controller too, and no layer multiplies another's
// tries (final-design.md section 8). RetryEngine reads it; whoever executes a step records it here.
package splice.upstream.retry

import splice.core.failure.FailureCause

/** How a token exchange ended: renewed, refused for good, or failed in a way a later exchange can heal. */
internal enum class Exchange { RENEWED, REFUSED, UNAVAILABLE }

/** The budgets a turn's steps spend, one count each (RetryBudgets). */
internal enum class Spend {
    BURST, OVERLOAD, LOADING, SERVER_RESEND, STREAM_RECOVERY, FRESH_REISSUE, EMPTY, CONTINUE_REASONING, AMEND,
    UNRECOGNIZED, CODE_MODE, FOLD, UNREACHABLE, WARM, NONE,
}

/** What one turn has spent on its failures. Not thread-safe: a turn's posts and rounds run one at a time. */
public class RetryLedger {
    private val spent = IntArray(Spend.entries.size)
    private val waitedMs = LongArray(Spend.entries.size)
    private val exchanges = mutableMapOf<String, Int>()
    private val lastExchange = mutableMapOf<String, Exchange>()
    private val refreshed = mutableSetOf<String>()
    private val logins = linkedSetOf<String>()

    /** Sends made outside holds, the first send included. */
    internal var attempts: Int = 0
        private set

    /** The login the turn's sends go out on now: the credential key the attempt loop names, empty for one login. */
    internal var login: String = ""
        private set

    /** The logins this turn was sent on, in order. */
    internal val loginsTried: List<String> get() = logins.toList()

    /** One send left for the provider. */
    internal fun sent() {
        attempts += 1
        logins += login
    }

    /** The sends go out on [key] from now on. */
    internal fun onLogin(key: String) {
        login = key
    }

    /** [step] was taken for [cause]: its budget is spent once, and its wait counted. */
    internal fun took(step: RetryStep, cause: FailureCause) {
        val spend = RetryEngine.spendOf(step, cause, this)
        spent[spend.ordinal] += 1
        if (step is RetryStep.Again) waitedMs[spend.ordinal] += step.afterMs
        if (step is RetryStep.Again && step.how == How.REFRESH_LOGIN) refreshed += login
    }

    /** One token exchange for the current login ended as [outcome]. */
    internal fun exchanged(outcome: Exchange) {
        exchanges[login] = (exchanges[login] ?: 0) + 1
        lastExchange[login] = outcome
    }

    internal fun count(spend: Spend): Int = spent[spend.ordinal]

    internal fun waited(spend: Spend): Long = waitedMs[spend.ordinal]

    /** Token exchanges made for the current login this turn. */
    internal val exchangesForLogin: Int get() = exchanges[login] ?: 0

    /** How the current login's last token exchange ended, or null when none was made. */
    internal val lastExchangeForLogin: Exchange? get() = lastExchange[login]

    /** Whether the current login's renewal already began this turn. */
    internal val refreshStarted: Boolean get() = login in refreshed
}
