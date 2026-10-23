// NEW: LAN share, phase 2, milestone 5 (docs/specs/lan-share.md). The `_splice._tcp` services on this machine's private
// networks now, for MdnsMachines: one JmDNS browser on each network multicast runs on, all at once, each listing what
// answered within MDNS_BROWSE_MS (sooner when answers came). A browser is closed on its own thread afterwards, so the
// scan never waits on its teardown. A network it cannot browse on finds nothing there.
package splice.share.remote

import splice.core.util.Cancellables
import splice.share.LocalNetworks
import splice.share.SHARE_MDNS_TYPE
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.jmdns.JmDNS

public class JmdnsBrowse : MdnsBrowse {
    private val browser = Executor { Thread.ofVirtual().name("splice-mdns-browse").start(it) }

    override fun services(): List<MdnsService> = LocalNetworks().multicast()
        .map { network -> CompletableFuture.supplyAsync({ browsed(network.address) }, browser) }
        .flatMap { it.handle { services, _ -> services.orEmpty() }.join() }

    private fun browsed(address: InetAddress): List<MdnsService> {
        val name = "splice-scan-${ProcessHandle.current().pid()}"
        val jmdns = Cancellables.runCatchingCancellable { JmDNS.create(address, name) }
            .fold(onSuccess = { it }, onFailure = { null }) ?: return emptyList()
        val listed = jmdns.list(SHARE_MDNS_TYPE, MDNS_BROWSE_MS)
        browser.execute {
            val closed = Cancellables.runCatchingCleanup { jmdns.close() }
            Cancellables.discard(closed, "a browser that will not close is done")
        }
        return listed.map { info ->
            val addresses = info.inet4Addresses.map { it.hostAddress } + info.inet6Addresses.map { it.hostAddress }
            MdnsService(addresses, info.port)
        }
    }
}

// why: how long a browser waits for answers when none has come; a responder on the same network answers in
// milliseconds, and JmDNS returns as soon as one has, so this is the wait on a network where no machine shares.
private const val MDNS_BROWSE_MS = 1_500L
