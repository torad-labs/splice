// NEW: server mode (docs/specs/server-mode.md, F12 and decision 6). The tokens of each person's turns that ended in the
// last minute, which `[server.limits] tpm_per_person` and a person's own `server person set --tpm` cap. They are
// counted from the ledger's rows as each is written and, at boot, from the rows the ledger already holds, so a restart
// forgets none of the minute. A turn counts every prompt token it was billed (those read from the prompt cache among
// them) and its answer's, from the moment it ended. Once a person's minute reaches their cap, their next turn is
// refused with how long until enough of it has left the minute; everyone else is counted alone, and served.
package splice.server

import splice.server.ledger.LedgerRow
import splice.server.ledger.RowWatch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

internal class MinuteTokens(private val clock: Clock) : RowWatch {
    private val minute = Duration.ofMinutes(1).toMillis()

    // Each person's turns that ended in the last minute, by when each ended and its tokens.
    private val ended = ConcurrentHashMap<String, MinuteStarts>()

    override fun counted(row: LedgerRow) {
        val tokens = row.promptTokens + row.completionTokens
        val at = Instant.parse(row.at).toEpochMilli() + row.durationMs
        // A row read at boot from long ago, or a turn that billed nothing, counts toward no minute.
        if (tokens <= 0 || clock.millis() - at >= minute) return
        val theirs = ended.computeIfAbsent(row.person) { MinuteStarts() }
        synchronized(theirs) { theirs.add(at, tokens) }
    }

    /** Why [person]'s next turn is refused under [cap], and when it may be retried; null while the
     *  tokens of their turns that ended in the last minute are under it. */
    fun refusal(person: String, cap: TokenCap): LimitVerdict.Refused? {
        val theirs = ended[person] ?: return null
        val now = clock.millis()
        val (used, wait) = synchronized(theirs) { theirs.count(now) to theirs.waitFor(cap.tokens, now) }
        wait ?: return null
        val why = "$person's turns that ended in the last minute used $used tokens, the most one person may use in a " +
            "minute here (${cap.tokens}, ${cap.named}); the next turn can start in $wait s"
        return LimitVerdict.Refused(why, wait)
    }
}
