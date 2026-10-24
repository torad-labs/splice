// NEW: LAN share, round 14 of PR #419's review. The goal: one network whose advert will not say goodbye never keeps this
// machine from being advertised again. A responder can throw when it unregisters (JmDNS schedules the goodbye on a timer
// it may have cancelled), and sharing moves or stops all the same: the other networks say theirs, the failure is said in
// the log, and the next advert opens on every network. JmdnsAdvert over adverts of this test's own, one of which throws.
package splice.share

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// why: two networks, so one network's failed goodbye is seen to leave the other's alone.
private const val NETWORKS = 2

// why: how long the advert's own thread is given for what it was asked: it runs in milliseconds.
private const val ADVERT_WAIT_S = 10L

class JmdnsAdvertTest {

    @Test
    fun `a network whose advert will not say goodbye leaves the others to theirs, and sharing is advertised again`() {
        val opened = AtomicInteger()
        val goodbyes = ConcurrentLinkedQueue<Int>()
        val said = ConcurrentLinkedQueue<String>()
        // Two adverts open, one on each network; two more once sharing moves to another port; all four say goodbye.
        val done = CountDownLatch(NETWORKS * 2)
        val advert = JmdnsAdvert({ said += it }) { _, _ ->
            (1..NETWORKS).map {
                val advertised = opened.incrementAndGet()
                AdvertGoodbye {
                    goodbyes += advertised
                    done.countDown()
                    check(advertised != 1) { "Timer already cancelled." }
                }
            }
        }

        advert.on("torad", SHARE_PORT)
        advert.on("torad", SHARE_PORT + 1)
        advert.off()

        assertTrue(done.await(ADVERT_WAIT_S, TimeUnit.SECONDS), "every advert said goodbye: $goodbyes, $said")
        assertEquals(NETWORKS * 2, opened.get(), "sharing was advertised again on every network")
        assertEquals((1..NETWORKS * 2).toSet(), goodbyes.toSet(), "each advert said its goodbye")
        assertTrue(said.any { "goodbye" in it }, "the one that would not is said in the log: $said")
    }
}

// why: the port the advert names; nothing listens on it, as an advert only names it.
private const val SHARE_PORT = 3097
