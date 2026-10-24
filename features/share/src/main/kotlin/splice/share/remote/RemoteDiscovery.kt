// NEW: LAN share, phase 1 (docs/specs/lan-share.md). Finding the machines that share heads. Since phase 2, milestone 5,
// from every source at once: the machines that advertise themselves over mDNS (MdnsMachines), the peers on his tailnet
// (TailscalePeers), and every address on this machine's private networks (SubnetSource), which still finds a machine
// where multicast is blocked. Each source looks in parallel, each machine it names is asked for /share/v1/info the
// moment it names it, and every wait takes what is left of one deadline. One machine reached two ways is one entry, at
// the address the first source gave: machines are told apart by the certificate each serves, by name only when one
// states none.
package splice.share.remote

import splice.core.util.Cancellables
import splice.share.ShareInfo
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A machine that answered: the [address] it answered on, and what it offers. */
public data class RemoteFound(public val address: String, public val port: Int, public val info: ShareInfo) {

    /** The name to keep for it: its own, when that resolves here to the address that answered (a DHCP move then
     *  follows the name), else that address. An IPv6 address is written many ways (`fd7a::1` in an advert,
     *  `fd7a:0:0:0:0:0:0:1` from the resolver), so the two are compared as addresses, never as text. */
    public fun reachable(): String {
        if (address.none { it.isLetter() } || ':' in address) {
            val server = info.server
            return listOf("$server.local", "$server.lan", server).firstOrNull { name ->
                Cancellables.runCatchingCancellable {
                    val answered = InetAddress.getByName(address)
                    InetAddress.getAllByName(name).any { it == answered }
                }.fold(onSuccess = { it }, onFailure = { false })
            } ?: address
        }
        return address
    }
}

/** An address and port where a source thinks a sharing machine may answer. */
public data class RemoteCandidate(public val host: String, public val port: Int)

/** One place sharing machines are found: names where to ask, given the default share [port]. A source that throws
 *  finds nothing, and the others still look. */
public fun interface RemoteSource {
    public fun candidates(port: Int): List<RemoteCandidate>
}

/** Asks one address what it offers: null when nothing there answers as splice (RemoteHttp). */
public fun interface RemoteInfoProbe {
    public fun info(host: String, port: Int): CompletableFuture<ShareInfo?>
}

public class RemoteDiscovery(
    private val probe: RemoteInfoProbe,
    /** Where to look, in the order the address kept is chosen: by default everywhere a sharing machine can be, the
     *  network path first (what advertises itself here, every address on these networks), then the peers on his
     *  tailnet, which reach a machine on another network. */
    private val sources: List<RemoteSource> = listOf(MdnsMachines(), SubnetSource(), TailscalePeers()),
) {
    private val looker = Executor { Thread.ofVirtual().name("splice-remote-scan").start(it) }

    public fun scan(port: Int): List<RemoteFound> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SCAN_DEADLINE_MS)
        val asked = ConcurrentHashMap<RemoteCandidate, CompletableFuture<ShareInfo?>>()
        val looking = sources.map { source ->
            CompletableFuture.supplyAsync({ source.candidates(port) }, looker).thenApply { named ->
                named.map { one ->
                    one to asked.computeIfAbsent(one) { candidate -> probe.info(candidate.host, candidate.port) }
                }
            }
        }
        // In source order, so the first source's address is the one kept; whatever has not answered by the deadline is
        // an address where nothing listens.
        return looking.flatMap { waited(it, deadline).orEmpty() }
            .mapNotNull { (candidate, reply) ->
                waited(reply, deadline)?.let { RemoteFound(candidate.host, candidate.port, it) }
            }
            .distinctBy(::machine)
    }

    /** Which machine answered: the certificate it serves, or its name when it states none. */
    private fun machine(found: RemoteFound): String =
        found.info.fingerprint?.let { "certificate $it" } ?: "name ${found.info.server}"

    /** What [future] holds by [deadline]: null when it is still out then (nothing listens there, and the ask is
     *  dropped) or when it failed (a source that could not look, an address that refused). */
    private fun <T> waited(future: CompletableFuture<T>, deadline: Long): T? =
        try {
            future.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            null
        } catch (_: ExecutionException) {
            null
        }
}

// why: one deadline for the whole scan: a machine on the same network answers /share/v1/info in milliseconds, so
// two and a half seconds leaves room for a slow Wi-Fi peer and an mDNS browse while `splice remote` still answers
// within three.
private const val SCAN_DEADLINE_MS = 2_500L
