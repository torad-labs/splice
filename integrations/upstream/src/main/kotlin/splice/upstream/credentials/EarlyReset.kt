// NEW: a plan hold ends when the provider's own usage reading shows its window was reset before its time.
//
// A hold lasts until the reset its refusal named, and until Nov 12, 2026 only an answered turn ended one sooner
// (RateLimitCooldown.acceptance). No turn is sent to a held login while another is open, so a login a person reset at
// the provider stayed out of the pool until the reset the refusal had named. That morning one login read 0% of its
// Fable week after such a reset, and the pool would have passed it over until the next Wednesday while the last open
// login ran out (Marcos: "I have 12 resets ... let me know when we're on the last account close to hit limit and I'll
// call a reset").
//
// What ends a hold is a figure that FELL, never a figure below full. A reading taken off an answer's headers carries
// the model weeks of an older one (QuotaSnapshot.keepingModelsOf), so a week read at 99% five minutes before its
// refusal reads 99% after it too, and says nothing. Usage inside a window only falls when the window was reset. A
// window that rolls over at its own time falls too, and by then the hold has run out by itself.
//
// Each window's last figure is kept for as long as the login is, held or not: the figure a reset falls from is most
// often read before the refusal that holds the login. A restart forgets them, and the reading saved before it is the
// first one weighed after it, so a reset that happens across a restart is still seen to fall.
package splice.upstream.credentials

import splice.core.usage.FIVE_HOUR_CLAIM
import splice.core.usage.QuotaSnapshot
import splice.core.usage.SEVEN_DAY_CLAIM
import splice.upstream.retry.RateLimitCooldown
import java.util.concurrent.ConcurrentHashMap

/** Hears [label]'s holds end on a reading: [ended] names each window that reads reset, a model's id or the login's
 *  own window in words (`7-day`). */
public fun interface EarlyResetHeard {
    public fun heard(label: String, ended: List<String>)
}

internal class EarlyReset(private val cooldown: RateLimitCooldown) {
    /** Each window's figure as last read: a model's week under the provider's name for it, the login's own five hours
     *  and week under their claims. */
    private val read = ConcurrentHashMap<String, Double>()

    /** Weighs [reading] and ends each hold whose window reads lower than it last did and below full. Names the
     *  windows that ended, none when no hold did. */
    fun weigh(reading: QuotaSnapshot?): List<String> {
        if (reading == null) return emptyList()
        val weeks = reading.models.filter { fell(it.model, it.usedPercent) && !it.usedUp }
        val own = listOf(FIVE_HOUR_CLAIM to reading.fiveHour, SEVEN_DAY_CLAIM to reading.sevenDay)
            .filter { (claim, window) -> window != null && fell(claim, window.usedPercent) && !window.usedUp }
            .map { it.first }
        val hold = cooldown.planHold
        val models = hold.models().mapNotNull { it.model }.filter { model -> weeks.any { it.weekOf(model) } }
        models.forEach(hold::release)
        val login = hold.live()?.takeIf { it.claim in own }
        // What an answer on this login would do: the window is open, so nothing of its refusal stands.
        if (login != null) cooldown.acceptance().invoke()
        return models + listOfNotNull(login?.windowWords)
    }

    /** Whether [window] reads lower than it last did. Its figure is kept either way. */
    private fun fell(window: String, percent: Double): Boolean = read.put(window, percent)?.let { percent < it } == true
}
