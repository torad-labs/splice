// NEW: LAN share, phase 1 (docs/specs/lan-share.md). Finding the machines that share heads: every address
// on each private IPv4 network this machine is on is asked for /share/v1/info on the share port, all at
// once, under one deadline. No multicast and no new dependency; a network bigger than a /22 is probed only
// in the /24 around this machine's own address. mDNS advertising replaces this in phase 2.
package splice.app.cli.share

import splice.app.share.ShareInfo
import splice.core.util.Cancellables
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** A machine that answered: the [address] it answered on, and what it offers. */
internal data class RemoteFound(val address: String, val port: Int, val info: ShareInfo)

internal class RemoteDiscovery(private val http: RemoteHttp) {

    fun scan(port: Int): List<RemoteFound> {
        val networks = NetworkInterface.networkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.interfaceAddresses }
            .filter { it.address is Inet4Address && it.address.isSiteLocalAddress }
        val own = networks.map { it.address.hostAddress }.toSet()
        val asked = networks.flatMap(::hosts).distinct().filterNot { it in own }
            .map { host -> host to http.info(host, port) }
        // Every probe is already in flight; each wait takes what is left of one shared deadline, and whatever has not
        // answered by then is an address where nothing listens.
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SCAN_DEADLINE_MS)
        return asked.mapNotNull { (host, reply) ->
            answered(reply, (deadline - System.nanoTime()).coerceAtLeast(0))?.let { RemoteFound(host, port, it) }
        }.distinctBy { it.info.server }
    }

    private fun answered(reply: CompletableFuture<ShareInfo?>, leftNanos: Long): ShareInfo? =
        Cancellables.runCatchingCancellable { reply.get(leftNanos, TimeUnit.NANOSECONDS) }
            .fold(onSuccess = { it }, onFailure = { null })

    /** Every host address of [network], or of the /24 around this machine when the network is larger. The
     *  address is read as one Int, so its 32 bits are [Int.SIZE_BITS]. */
    private fun hosts(network: InterfaceAddress): List<String> {
        val declared = network.networkPrefixLength.toInt()
        val prefix = if (declared < MIN_PREFIX) AROUND_PREFIX else declared.coerceAtMost(MAX_PREFIX)
        val self = ByteBuffer.wrap(network.address.address).int
        val mask = -1 shl (Int.SIZE_BITS - prefix)
        val base = self and mask
        val size = 1 shl (Int.SIZE_BITS - prefix)
        return (1 until size - 1).map { offset -> dotted(base + offset) }
    }

    /** An address as `a.b.c.d`; four raw bytes, so no name is looked up. */
    private fun dotted(address: Int): String =
        InetAddress.getByAddress(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(address).array()).hostAddress
}

// why: one deadline for the whole scan: a machine on the same network answers /share/v1/info in milliseconds, so
// two and a half seconds leaves room for a slow Wi-Fi peer while `splice remote` still answers within three.
private const val SCAN_DEADLINE_MS = 2_500L

// why: a /22 is 1022 hosts, the most one scan asks; anything wider is narrowed to the /24 this machine is in.
private const val MIN_PREFIX = 22

// why: the network a wider one is narrowed to: the /24 around this machine, 254 hosts, a home network's usual size.
private const val AROUND_PREFIX = 24

// why: a /31 or /32 (a point-to-point link, a VPN's own address) has no host range of its own, so it is probed as
// the /30 around it, the two hosts beside this machine.
private const val MAX_PREFIX = 30
