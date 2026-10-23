// NEW: LAN share, phase 2, milestone 5 (docs/specs/lan-share.md). The sharing machines that advertise `_splice._tcp` on
// this network (ShareAdvert): each is asked at its IPv4 address and at the port it advertised, which need not be the
// default. A service that resolved no IPv4 address is left out.
package splice.share.remote

import splice.share.SystemMdns

/** One `_splice._tcp` service this machine saw: the [addresses] it resolved to and the [port] it advertised. */
public data class MdnsService(public val addresses: List<String>, public val port: Int)

/** The `_splice._tcp` services seen on this network now (JmdnsBrowse, DnsSdBrowse). */
public fun interface MdnsBrowse {
    public fun services(): List<MdnsService>
}

public class MdnsMachines(
    /** By default through the system's mDNSResponder on a Mac, which gives a process of ours no multicast, and through
     *  JmDNS everywhere else. */
    private val browse: MdnsBrowse = if (SystemMdns().mac) DnsSdBrowse() else JmdnsBrowse(),
) : RemoteSource {

    override fun candidates(port: Int): List<RemoteCandidate> = browse.services().mapNotNull { service ->
        service.addresses.firstOrNull { '.' in it && ':' !in it }?.let { RemoteCandidate(it, service.port) }
    }
}
