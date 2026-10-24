// NEW: LAN share, phase 2, milestone 5 (docs/specs/lan-share.md). The advert (ShareAdvert) everywhere but a Mac:
// multicast DNS through JmDNS, one responder on each private network multicast runs on. Registering probes the network
// for a second or so, so it runs on its own thread, in the order asked, and the listener's opening and closing never wait
// on it. A network it cannot advertise on is logged. Since round 14 of PR #419's review, a network whose advert will not
// say goodbye (JmDNS can throw as it unregisters, on a network the machine left) is logged too, and the other networks
// say theirs and the next advert opens all the same, so this machine is never left unadvertised until a restart.
package splice.share

import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/** One network's advert as it runs: [said] says its goodbye, so a machine still holding the record drops it. */
internal fun interface AdvertGoodbye {
    fun said()
}

/** Opens the advert of this machine as [name] at [port] on each network it runs on, each with its goodbye. */
internal fun interface AdvertOpening {
    fun opened(name: String, port: Int): List<AdvertGoodbye>
}

internal class JmdnsAdvert(
    private val log: LogSink,
    private val opening: AdvertOpening = JmdnsOpening(log),
) : ShareAdvert {
    private val worker: ExecutorService =
        Executors.newSingleThreadExecutor(Thread.ofVirtual().name("splice-share-advert").factory())

    /** The adverts running now; read and written on [worker] only. */
    private var responders: List<AdvertGoodbye> = emptyList()

    override fun on(name: String, port: Int) {
        worker.execute {
            withdrawn()
            responders = opening.opened(name, port)
            if (responders.isNotEmpty()) log("[share] advertised on this network as $name, port $port\n")
        }
    }

    /** The responders say goodbye, so a machine still holding the record drops it. */
    override fun off() {
        worker.execute(::withdrawn)
    }

    private fun withdrawn() {
        responders.forEach { responder ->
            Cancellables.runCatchingBestEffort { responder.said() }
                .onFailure { log("[share] an advert did not say goodbye: ${SafeFailureText.render(it)}\n") }
        }
        responders = emptyList()
    }
}

/** JmDNS's adverts: one responder on each private network multicast runs on. A network it cannot advertise on is
 *  logged, and the others still are. */
private class JmdnsOpening(private val log: LogSink) : AdvertOpening {
    override fun opened(name: String, port: Int): List<AdvertGoodbye> =
        LocalNetworks().multicast().mapNotNull { network ->
            val address = network.address
            Cancellables.runCatchingCancellable {
                JmDNS.create(address, "$name-splice").also {
                    it.registerService(ServiceInfo.create(SHARE_MDNS_TYPE, name, port, SHARE_MDNS_TEXT))
                }
            }.onFailure {
                log("[share] not advertised on ${address.hostAddress}: ${SafeFailureText.render(it)}\n")
            }.getOrNull()
        }.map { responder ->
            AdvertGoodbye {
                responder.unregisterAllServices()
                val closed = Cancellables.runCatchingCleanup { responder.close() }
                Cancellables.discard(closed, "a responder that will not close has stopped answering all the same")
            }
        }
}
