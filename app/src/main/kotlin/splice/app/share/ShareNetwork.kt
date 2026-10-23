// NEW: LAN share, phase 1 (docs/specs/lan-share.md). Who may reach the share listener at all, before any
// route runs. The source must be a private address: loopback, RFC 1918, link-local, 100.64/10 (the range
// Tailscale hands out) or fc00::/7. The Host must be an IP literal or one of this machine's own names, the
// share listener's counterpart of LoopbackHost: a page whose DNS was rebound onto this address arrives with
// its own name in Host and is refused, so a browser on the LAN cannot be turned into a client.
package splice.app.share

import splice.core.util.Cancellables
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal class ShareNetwork(
    /** This machine's host name as the OS reports it, full or short. */
    localName: String,
    /** `[share] hostnames`: further names a client may use, a Tailscale name for one. */
    hostnames: List<String>,
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
        return ip.isLoopbackAddress || ip.isSiteLocalAddress || ip.isLinkLocalAddress || carrierGrade(ip) ||
            uniqueLocal(ip)
    }

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
        val first = bytes[0].toInt() and BYTE_MASK
        val second = bytes[1].toInt() and BYTE_MASK
        return first == CGNAT_FIRST_OCTET && second and CGNAT_SECOND_MASK == CGNAT_SECOND_OCTET
    }

    private fun uniqueLocal(ip: InetAddress): Boolean {
        val bytes = (ip as? Inet6Address)?.address ?: return false
        return bytes[0].toInt() and ULA_MASK == ULA_PREFIX
    }

}

private const val BYTE_MASK = 0xff

// 100.64.0.0/10: first octet 100, top two bits of the second octet 01.
private const val CGNAT_FIRST_OCTET = 100
private const val CGNAT_SECOND_MASK = 0xc0
private const val CGNAT_SECOND_OCTET = 0x40

// fc00::/7
private const val ULA_MASK = 0xfe
private const val ULA_PREFIX = 0xfc
