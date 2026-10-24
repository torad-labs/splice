// NEW: LAN share, phase 2, milestone 5 (docs/specs/lan-share.md). This machine's own addresses on its private IPv4
// networks: where the subnet probe looks (SubnetSource), and where multicast DNS advertises (ShareAdvert) and browses
// (JmdnsBrowse). Only interfaces that are up count, never loopback or a sub-interface; a machine whose interfaces cannot
// be listed has none.
package splice.share

import splice.core.util.Cancellables
import java.net.Inet4Address
import java.net.InterfaceAddress
import java.net.NetworkInterface

internal class LocalNetworks {

    /** Each private IPv4 network this machine is on, as its own address there with the network's prefix. */
    fun siteLocal(): List<InterfaceAddress> = interfaces(multicastOnly = false)

    /** The private IPv4 networks multicast DNS can run on: [siteLocal], on an interface that carries multicast. */
    fun multicast(): List<InterfaceAddress> = interfaces(multicastOnly = true)

    private fun interfaces(multicastOnly: Boolean): List<InterfaceAddress> = Cancellables.runCatchingCancellable {
        NetworkInterface.networkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual && (!multicastOnly || it.supportsMulticast()) }
            .flatMap { it.interfaceAddresses }
            .filter { it.address is Inet4Address && it.address.isSiteLocalAddress }
    }.fold(onSuccess = { it }, onFailure = { emptyList() })
}
