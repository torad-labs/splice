// NEW: LAN share, phase 2, milestone 5 (docs/specs/lan-share.md). The advert (ShareAdvert) everywhere but a Mac:
// multicast DNS through JmDNS, one responder on each private network multicast runs on. Registering probes the network
// for a second or so, so it runs on its own thread, in the order asked, and the listener's opening and closing never wait
// on it. A network it cannot advertise on is logged.
package splice.share

import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

internal class JmdnsAdvert(private val log: LogSink) : ShareAdvert {
    private val worker: ExecutorService =
        Executors.newSingleThreadExecutor(Thread.ofVirtual().name("splice-share-advert").factory())

    /** The responders advertising now; read and written on [worker] only. */
    private var responders: List<JmDNS> = emptyList()

    override fun on(name: String, port: Int) {
        worker.execute {
            withdrawn()
            responders = LocalNetworks().multicast().mapNotNull { network ->
                val address = network.address
                Cancellables.runCatchingCancellable {
                    JmDNS.create(address, "$name-splice").also {
                        it.registerService(ServiceInfo.create(SHARE_MDNS_TYPE, name, port, SHARE_MDNS_TEXT))
                    }
                }.onFailure {
                    log("[share] not advertised on ${address.hostAddress}: ${SafeFailureText.render(it)}\n")
                }.getOrNull()
            }
            if (responders.isNotEmpty()) log("[share] advertised on this network as $name, port $port\n")
        }
    }

    /** The responders say goodbye, so a machine still holding the record drops it. */
    override fun off() {
        worker.execute(::withdrawn)
    }

    private fun withdrawn() {
        responders.forEach { responder ->
            responder.unregisterAllServices()
            val closed = Cancellables.runCatchingCleanup { responder.close() }
            Cancellables.discard(closed, "a responder that will not close has stopped answering all the same")
        }
        responders = emptyList()
    }
}
