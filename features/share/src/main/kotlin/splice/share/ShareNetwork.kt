// NEW: LAN share, phase 1 (docs/specs/lan-share.md). Who may reach the share listener at all, before any
// route runs. The source must be on this LAN: a private address — loopback, RFC 1918, link-local, 100.64/10 (the
// range Tailscale hands out) or fc00::/7 — or on an IPv6 prefix a house interface of this machine holds, one beside
// an RFC 1918 address and no public IPv4 one, which is how a house whose router hands out a globally routable /64
// reaches itself. A source on any prefix a datacenter interface holds (one with a public IPv4 address) is refused,
// whatever its range: a provider's private network, link and /64 are shared with its other customers. The Host must
// be an IP literal or one of this machine's own names, the share listener's counterpart of LoopbackHost: a page whose
// DNS was rebound onto this address arrives with its own name in Host and is refused, so a browser on the LAN cannot
// be turned into a client.
package splice.share

import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface

internal class ShareNetwork(
    /** This machine's host name as the OS reports it, full or short. */
    localName: String,
    /** `[share] hostnames`: further names a client may use, a Tailscale name for one. */
    hostnames: List<String>,
    /** The prefixes this machine's own interfaces hold: what makes a routable address a machine on this LAN. */
    private val attached: AttachedPrefixes,
) {
    private val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    private val names: Set<String> = buildSet {
        val full = localName.lowercase().trimEnd('.')
        val short = full.substringBefore('.')
        addAll(listOf("localhost", full, short, "$short.local", "$short.lan"))
        hostnames.forEach { add(it.lowercase().trimEnd('.')) }
    }

    fun admitsSource(address: String): Boolean {
        val ip = literal(address) ?: return false
        val links = attached.now().groupBy { it.link }.values
        return ip.isLoopbackAddress || !datacenter(ip, links) && (privateRange(ip) || onThisLan(ip, links))
    }

    private fun privateRange(ip: InetAddress): Boolean =
        ip.isSiteLocalAddress || ip.isLinkLocalAddress || carrierGrade(ip) || uniqueLocal(ip)

    /** Whether a datacenter interface of this machine, one holding a public IPv4 address, holds [ip]'s prefix. Its
     *  private network is the provider's, and some share it with every customer in the region (Linode's
     *  192.168.128.0/17); its link and its IPv6 /64 are shared the same way. A source there is a stranger whatever
     *  range its address is in. Link-local prefixes are the same fe80::/64 on every interface, so a machine with a
     *  datacenter interface turns every link-local source away: a house machine still reaches it by its other
     *  addresses. */
    private fun datacenter(ip: InetAddress, links: Collection<List<SharePrefix>>): Boolean =
        links.any { link -> publicIpv4(link) && link.any { it.holds(ip) } }

    /** Whether [ip] is an IPv6 address on a prefix a house interface of this machine holds: a machine reachable with
     *  no router in between. SLAAC makes a house's /64 the link itself, globally routable though it is, so the named
     *  private ranges alone would turn the house away. IPv4 is left to them: a house's IPv4 is RFC 1918. */
    private fun onThisLan(ip: InetAddress, links: Collection<List<SharePrefix>>): Boolean =
        ip is Inet6Address && links.any { link -> house(link) && link.any { it.holds(ip) } }

    /** Whether the interface holding [link] is a house's: its router NATs an RFC 1918 address, and it holds no public
     *  IPv4 address. An interface with no IPv4 address says nothing either way. */
    private fun house(link: List<SharePrefix>): Boolean =
        link.any { it.address is Inet4Address && it.address.isSiteLocalAddress } && !publicIpv4(link)

    /** Whether the interface holding [link] holds a public IPv4 address, as a datacenter server's does and a house's,
     *  behind its router's NAT, never does. */
    private fun publicIpv4(link: List<SharePrefix>): Boolean = link.map { it.address }.filterIsInstance<Inet4Address>()
        .any { !it.isSiteLocalAddress && !it.isLinkLocalAddress && !it.isLoopbackAddress && !carrierGrade(it) }

    /** An absent Host is admitted, as LoopbackHost admits it: an HTTP/1.0 client sends none, and no browser
     *  request lacks one. */
    fun admitsHost(host: String?): Boolean {
        val name = host?.let(::hostOnly)?.lowercase()?.trimEnd('.') ?: return true
        return literal(name) != null || name in names
    }

    /** `[fe80::1]:3097` -> `fe80::1`, `host:3097` -> `host`; a bare IPv6 literal is kept whole. */
    private fun hostOnly(host: String): String = when {
        host.startsWith("[") -> host.substringAfter('[').substringBefore(']')
        host.count { it == ':' } == 1 -> host.substringBefore(':')
        else -> host
    }

    /** An IP literal parsed with no DNS lookup, or null for a name. A zone (`%en0`) is dropped first. */
    private fun literal(text: String): InetAddress? {
        val bare = text.substringBefore('%')
        val ipv6 = ':' in bare && bare.all { it.isLetterOrDigit() || it == ':' || it == '.' }
        val looksLiteral = ipv4.matches(bare) || ipv6
        if (!looksLiteral) return null
        return Cancellables.runCatchingCancellable { InetAddress.getByName(bare) }
            .fold(onSuccess = { it }, onFailure = { null })
    }

    private fun carrierGrade(ip: InetAddress): Boolean {
        val bytes = (ip as? Inet4Address)?.address ?: return false
        val first = bytes[0].toUByte().toInt()
        val second = bytes[1].toUByte().toInt()
        return first == CGNAT_FIRST_OCTET && second and CGNAT_SECOND_MASK == CGNAT_SECOND_OCTET
    }

    private fun uniqueLocal(ip: InetAddress): Boolean {
        val bytes = (ip as? Inet6Address)?.address ?: return false
        return bytes[0].toInt() and ULA_MASK == ULA_PREFIX
    }
}

/** One prefix an interface of this machine holds: [address], with its first [bits] bits fixed, on the interface
 *  named [link]. */
internal data class SharePrefix(val address: InetAddress, val bits: Int, val link: String) {

    /** Whether [other] sits on this prefix: the same address family, with those leading bits equal. */
    fun holds(other: InetAddress): Boolean {
        val ours = address.address
        val theirs = other.address
        if (ours.size != theirs.size || bits !in 0..ours.size * Byte.SIZE_BITS) return false
        return (0 until bits).all { bit(ours, it) == bit(theirs, it) }
    }

    /** The bit at [index] of [bytes], counted from the most significant bit of the first byte, as a prefix counts. */
    private fun bit(bytes: ByteArray, index: Int): Int =
        (bytes[index / Byte.SIZE_BITS].toInt() shr (Byte.SIZE_BITS - 1 - index % Byte.SIZE_BITS)) and 1
}

/** The prefixes this machine's interfaces hold right now. Read at each request rather than kept: a Wi-Fi hop, a
 *  cable moved or a VPN coming up changes them, and the listener follows without a restart. */
internal fun interface AttachedPrefixes {
    fun now(): List<SharePrefix>
}

/** What the OS reports for this machine's own interfaces. An interface list splice cannot read leaves no prefix at
 *  all, so the named private ranges stay the only way in: the refusal is the safe side, and [log] says why, since a
 *  machine on the LAN being turned away otherwise looks like a wrong address. */
internal class HostPrefixes(private val log: LogSink) : AttachedPrefixes {
    override fun now(): List<SharePrefix> =
        Cancellables.runCatchingCancellable {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp }
                .flatMap { link ->
                    link.interfaceAddresses.asSequence()
                        .map { SharePrefix(it.address, it.networkPrefixLength.toInt(), link.name) }
                }
                .toList()
        }.onFailure { failure ->
            val why = SafeFailureText.render(failure)
            log("[share] this machine's own networks could not be read ($why); only the private ranges are admitted\n")
        }.getOrDefault(emptyList())
}

// why: 100.64.0.0/10, carrier-grade NAT and the range Tailscale hands out, starts with the octet 100.
private const val CGNAT_FIRST_OCTET = 100

// why: a /10 fixes only the top two bits of the second octet, so those two are all that is compared.
private const val CGNAT_SECOND_MASK = 0xc0

// why: in 100.64/10 those two bits read 01, the second octets 64 through 127.
private const val CGNAT_SECOND_OCTET = 0x40

// why: fc00::/7, IPv6's unique local range, fixes the top seven bits of the first byte.
private const val ULA_MASK = 0xfe

// why: those seven bits read 1111110, the first bytes 0xfc and 0xfd.
private const val ULA_PREFIX = 0xfc
