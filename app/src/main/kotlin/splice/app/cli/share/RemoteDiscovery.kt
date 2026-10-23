// NEW: LAN share, phase 1 (docs/specs/lan-share.md). Finding the machines that share heads: every address
// on each private IPv4 network this machine is on is asked for /share/v1/info on the share port, all at
// once, under one deadline. No multicast and no new dependency; a network bigger than a /22 is probed only
// in the /24 around this machine's own address. mDNS advertising replaces this in phase 2.
package splice.app.cli.share

import splice.app.share.ShareInfo
import splice.core.util.Cancellables
import java.net.Inet4Address
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

    /** Every host address of [network], or of the /24 around this machine when the network is larger. */
    private fun hosts(network: InterfaceAddress): List<String> {
        val declared = network.networkPrefixLength.toInt()
        val prefix = if (declared < MIN_PREFIX) AROUND_PREFIX else declared.coerceAtMost(MAX_PREFIX)
        val self = ByteBuffer.wrap(network.address.address).int
        val mask = -1 shl (IPV4_BITS - prefix)
        val base = self and mask
        val size = 1 shl (IPV4_BITS - prefix)
        return (1 until size - 1).map { offset -> dotted(base + offset) }
    }

    private fun dotted(address: Int): String =
        (IPV4_BITS - BYTE_BITS downTo 0 step BYTE_BITS).joinToString(".") { shift ->
            ((address ushr shift) and BYTE_MASK).toString()
        }
}

private const val SCAN_DEADLINE_MS = 2_500L
private const val IPV4_BITS = 32
private const val BYTE_BITS = 8
private const val BYTE_MASK = 0xff

// A /22 is 1022 hosts, the most one scan asks; anything wider is narrowed to the /24 this machine is in.
private const val MIN_PREFIX = 22
private const val AROUND_PREFIX = 24
private const val MAX_PREFIX = 30
