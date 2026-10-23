// NEW: LAN share, phase 1 (docs/specs/lan-share.md); one source of several since phase 2, milestone 5. Every address on
// each private IPv4 network this machine is on, at the share port: what finds a sharing machine when multicast is
// blocked and it is not on the tailnet. A network bigger than a /22 is probed only in the /24 around this machine's own
// address, and this machine's own addresses are left out.
package splice.share.remote

import splice.share.LocalNetworks
import java.net.InetAddress
import java.net.InterfaceAddress
import java.nio.ByteBuffer

public class SubnetSource : RemoteSource {

    override fun candidates(port: Int): List<RemoteCandidate> {
        val networks = LocalNetworks().siteLocal()
        val own = networks.map { it.address.hostAddress }.toSet()
        return networks.flatMap(::hosts).distinct().filterNot { it in own }.map { RemoteCandidate(it, port) }
    }

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

// why: a /22 is 1022 hosts, the most one scan asks; anything wider is narrowed to the /24 this machine is in.
private const val MIN_PREFIX = 22

// why: the network a wider one is narrowed to: the /24 around this machine, 254 hosts, a home network's usual size.
private const val AROUND_PREFIX = 24

// why: a /31 or /32 (a point-to-point link, a VPN's own address) has no host range of its own, so it is probed as
// the /30 around it, the two hosts beside this machine.
private const val MAX_PREFIX = 30
