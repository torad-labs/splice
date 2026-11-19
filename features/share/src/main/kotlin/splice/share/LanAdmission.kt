// NEW: LAN share (docs/specs/lan-share.md, "Who is on the LAN"). Who may reach the share listener at all, before any
// route runs. The source must be on this machine's own networks: loopback, RFC 1918, link-local, or an IPv6 address on
// a prefix a house interface of this machine holds, one beside an RFC 1918 address and no public IPv4 one, which is how
// a house whose router hands out a globally routable or unique local /64 reaches itself. Unique local IPv6 as a whole
// (fc00::/7) is not. A tailnet peer is (door-network, Nov 18, 2026, which reverses the Oct 24 "Tailscale is not part of
// this"): a source on Tailscale's 100.64/10 or fd7a:115c:a1e0::/48 is admitted while this machine holds an address
// there itself, so it is a peer on the same tailnet, which Tailscale has already admitted; on a machine with no tailnet
// address those ranges are a carrier's NAT, and turned away. A source on any
// prefix a datacenter interface holds (one with a public IPv4 address) is refused, whatever its range: a provider's
// private network, link and /64 are shared with its other customers. A machine whose interfaces cannot be read admits
// loopback alone, since nothing then tells a house from a datacenter, and says that, not that the source is off the
// LAN, which it may well be on. The Host must be an IP literal or one of this machine's own names: a web page whose DNS
// was rebound onto this address arrives with its own name in Host and is refused, so a browser on the LAN cannot be
// turned into a client of the heads.
package splice.share

import splice.core.listen.IpLiterals
import splice.core.listen.IpPrefix
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

internal class LanAdmission(
    /** This machine's host name as the OS reports it, full or short. */
    localName: String,
    /** `[share] hostnames`: further names a request may give this machine. */
    hostnames: List<String>,
    /** The prefixes this machine's own interfaces hold: what makes a routable address a machine on this LAN. */
    private val attached: AttachedPrefixes,
) {
    // The domains a house router, mDNS or a home DNS puts this machine's short name under.
    private val lanSuffixes = listOf("local", "lan", "home", "internal")

    private val tailnetIpv6 = IpPrefix(InetAddress.getByName(TAILNET_IPV6_PREFIX), TAILNET_IPV6_BITS)

    // This machine's short name: its host name up to the first dot.
    private val short = localName.lowercase().trimEnd('.').substringBefore('.')

    private val names: Set<String> = buildSet {
        val full = localName.lowercase().trimEnd('.')
        addAll(listOf("localhost", full, short))
        lanSuffixes.forEach { add("$short.$it") }
        hostnames.forEach { add(it.lowercase().trimEnd('.')) }
    }

    /** Why [address] may not reach the listener, in words for the machine turned away, or null when it may. */
    fun refusal(address: String): String? {
        val ip = literal(address)
        if (ip?.isLoopbackAddress == true) return null
        // Interfaces that could not be read cannot tell a house from a datacenter: loopback alone is admitted then.
        val links = attached.now()?.groupBy { it.link }?.values ?: return UNREAD_NETWORKS
        if (ip != null && tailnet(ip) && links.flatten().any { tailnet(it.address) }) return null
        val lan = ip != null && !datacenter(ip, links) && (privateRange(ip) || onThisLan(ip, links))
        return if (lan) null else "requests from $address are not from this machine's LAN or its tailnet"
    }

    private fun privateRange(ip: InetAddress): Boolean = ip.isSiteLocalAddress || ip.isLinkLocalAddress

    /** Whether [ip] is on one of the ranges Tailscale hands its peers: 100.64/10 or fd7a:115c:a1e0::/48. */
    private fun tailnet(ip: InetAddress): Boolean = carrierGrade(ip) || tailnetIpv6.holds(ip)

    /** Whether a datacenter interface of this machine, one holding a public IPv4 address, holds [ip]'s prefix. Its
     *  private network is the provider's, and some share it with every customer in the region (Linode's
     *  192.168.128.0/17); its link and its IPv6 /64 are shared the same way. A source there is a stranger whatever
     *  range its address is in. No prefix says which link a link-local source came in on, in either family, so a
     *  machine with a datacenter interface turns every link-local source away. */
    private fun datacenter(ip: InetAddress, links: Collection<List<SharePrefix>>): Boolean =
        links.filter(::publicIpv4).any { link -> ip.isLinkLocalAddress || link.any { it.holds(ip) } }

    /** Whether [ip] is an IPv6 address on a prefix a house interface of this machine holds: a machine reachable with
     *  no router in between. SLAAC makes a house's /64 the link itself, globally routable or unique local alike. IPv4 is left
     *  to the private ranges: a house's IPv4 is RFC 1918. */
    private fun onThisLan(ip: InetAddress, links: Collection<List<SharePrefix>>): Boolean =
        ip is Inet6Address && links.any { link -> house(link) && link.any { it.holds(ip) } }

    /** Whether the interface holding [link] is a house's: its router NATs an RFC 1918 address, and it holds no public
     *  IPv4 address. */
    private fun house(link: List<SharePrefix>): Boolean =
        link.any { it.address is Inet4Address && it.address.isSiteLocalAddress } && !publicIpv4(link)

    /** Whether the interface holding [link] holds a public IPv4 address, as a datacenter server's does and a house's,
     *  behind its router's NAT, never does. Tailscale's 100.64/10 is not public. */
    private fun publicIpv4(link: List<SharePrefix>): Boolean = link.map { it.address }.filterIsInstance<Inet4Address>()
        .any { !it.isSiteLocalAddress && !it.isLinkLocalAddress && !it.isLoopbackAddress && !carrierGrade(it) }

    /** An absent Host is admitted: an HTTP/1.0 client sends none, and no browser request lacks one. */
    fun admitsHost(host: String?): Boolean {
        val name = host?.let(::hostOnly)?.lowercase()?.trimEnd('.') ?: return true
        return literal(name) != null || name in names || magicDns(name)
    }

    /** Whether [name] is this machine's name on a tailnet, as MagicDNS gives it: its short name under
     *  `<tailnet>.ts.net`. */
    private fun magicDns(name: String): Boolean = name.endsWith(MAGIC_DNS_SUFFIX) &&
        name.substringBefore('.') == short && name.count { it == '.' } == MAGIC_DNS_DOTS

    /** `[fe80::1]:3097` -> `fe80::1`, `host:3097` -> `host`; a bare IPv6 literal is kept whole. */
    private fun hostOnly(host: String): String = when {
        host.startsWith("[") -> host.substringAfter('[').substringBefore(']')
        host.count { it == ':' } == 1 -> host.substringBefore(':')
        else -> host
    }

    private fun literal(text: String): InetAddress? = IpLiterals.parse(text)

    private fun carrierGrade(ip: InetAddress): Boolean {
        val bytes = (ip as? Inet4Address)?.address ?: return false
        val first = bytes[0].toUByte().toInt()
        val second = bytes[1].toUByte().toInt()
        return first == CGNAT_FIRST_OCTET && second and CGNAT_SECOND_MASK == CGNAT_SECOND_OCTET
    }
}

/** One prefix an interface of this machine holds: [address], with its first [bits] bits fixed, on the interface
 *  named [link]. */
internal data class SharePrefix(val address: InetAddress, val bits: Int, val link: String) {

    /** Whether [other] sits on this prefix: the same address family, with those leading bits equal. */
    fun holds(other: InetAddress): Boolean = IpPrefix(address, bits).holds(other)
}

/** The prefixes this machine's interfaces hold right now. Read at each request rather than kept: a Wi-Fi hop, a
 *  cable moved or a VPN coming up changes them, and the listener follows without a restart. */
internal fun interface AttachedPrefixes {
    /** Null when they could not be read: nothing then tells a house from a datacenter, so loopback alone is
     *  admitted. */
    fun now(): List<SharePrefix>?
}

/** What the OS reports for this machine's own interfaces. A list splice cannot read is null, which admits loopback
 *  alone for that request, and [log] says why, since a machine on the LAN being turned away otherwise looks like a
 *  wrong address. */
internal class HostPrefixes(private val log: LogSink) : AttachedPrefixes {
    override fun now(): List<SharePrefix>? = Cancellables.runCatchingCancellable {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp }
            .flatMap { link ->
                link.interfaceAddresses.asSequence()
                    .map { SharePrefix(it.address, it.networkPrefixLength.toInt(), link.name) }
            }
            .toList()
    }.onFailure { failure ->
        val why = SafeFailureText.render(failure)
        log("[share] this machine's own networks could not be read ($why); only loopback is answered for now\n")
    }.getOrNull()
}

// why: a machine on the LAN refused while this one cannot read its own networks is told the real cause, which a retry
// may outlast; "not from this machine's LAN" would be false for it.
private const val UNREAD_NETWORKS =
    "this machine could not read its own networks; only loopback is answered until it can"

// why: Tailscale hands every peer an address from fd7a:115c:a1e0::/48, inside unique local IPv6 (fc00::/7), so a
// tailnet peer is told from another site's unique local source by this prefix alone.
private const val TAILNET_IPV6_PREFIX = "fd7a:115c:a1e0::"

// why: the 48 leading bits of that prefix are the tailnet's; the rest is the peer.
private const val TAILNET_IPV6_BITS = 48

// The domain MagicDNS names a tailnet's machines under: `<machine>.<tailnet>.ts.net`.
private const val MAGIC_DNS_SUFFIX = ".ts.net"

// why: `machine.tailnet.ts.net` has three dots; a name with more is some other machine's under it.
private const val MAGIC_DNS_DOTS = 3

// why: 100.64.0.0/10, carrier-grade NAT and the range Tailscale hands out, starts with the octet 100.
private const val CGNAT_FIRST_OCTET = 100

// why: a /10 fixes only the top two bits of the second octet, so those two are all that is compared.
private const val CGNAT_SECOND_MASK = 0xc0

// why: in 100.64/10 those two bits read 01, the second octets 64 through 127.
private const val CGNAT_SECOND_OCTET = 0x40
