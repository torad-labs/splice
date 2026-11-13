// NEW: Nov 12, 2026: while a command holds a turn, its logins' usage is read again and again, not every five minutes.
//
// A turn held on a plan limit goes on when a login can serve it: at the reset the provider named, or sooner, when the
// person resets a login at the provider. splice hears of that reset from the login's usage reading (PlanHold.weigh),
// and a reading was asked for every five minutes (QuotaPoller) or when someone opened a console page. That evening
// every Claude login on one machine was out of Fable from 8:48 PM CT. Marcos reset one, and the sessions went on
// waiting until the next five-minute reading, at 8:57:44 PM; by then he had logged in again by hand ("my limit just
// freed up, and the sessions were all still loading"). While a head holds any turn, this asks every login's poller
// for a reading each time the poller's own floor allows one, so a reset is heard within about a minute and no one has
// to open a page for it.
package splice.app.probe

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splice.core.head.Head
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessTicker
import splice.usage.quota.HeadUsageSource

// why: a login's poller allows one reading a minute; a ten-second tick asks soon after that minute is up.
private const val HELD_READ_TICK_MS = 10_000L

/** Keeps the usage readings of [head]'s logins fresh for as long as it holds a turn. */
internal class HeldLoginReads(
    private val head: Head,
    private val usage: HeadUsageSource,
    private val ticker: Ticker = ProcessTicker(),
) {
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive && ticker.awaitTick(HELD_READ_TICK_MS)) {
            // A reading that shows a held window reset ends that login's hold, and the waiting turns move to it.
            if (head.heldTurns().isNotEmpty()) usage.probeNow()
        }
    }
}
