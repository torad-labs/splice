// NEW: an address prefix (`10.0.0.0/8`) and the reading of an IP literal with no DNS lookup, for the two listeners
// that decide by a caller's address: LAN share asks whether a source is on this machine's own networks, and server
// mode whether a connection comes from a proxy it trusts.
package splice.core.listen

import java.net.InetAddress
import java.net.UnknownHostException

/** [address] with its first [bits] bits fixed. */
public data class IpPrefix(val address: InetAddress, val bits: Int) {

    /** Whether [other] sits on this prefix: the same address family, with those leading bits equal. */
    public fun holds(other: InetAddress): Boolean {
        val ours = address.address
        val theirs = other.address
        if (ours.size != theirs.size || bits !in 0..ours.size * Byte.SIZE_BITS) return false
        return (0 until bits).all { bit(ours, it) == bit(theirs, it) }
    }

    /** The bit at [index] of [bytes], counted from the most significant bit of the first byte, as a prefix counts. */
    private fun bit(bytes: ByteArray, index: Int): Int =
        (bytes[index / Byte.SIZE_BITS].toInt() shr (Byte.SIZE_BITS - 1 - index % Byte.SIZE_BITS)) and 1
}

public object IpLiterals {
    private val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val ipv6 = Regex("""^[0-9A-Fa-f:.]+$""")

    /** An IP literal parsed with no DNS lookup, or null for a name. Only digits, hex letters, `:` and `.` are handed
     *  to the parser, which looks a name up; a zone (`%en0`) is dropped first. */
    public fun parse(text: String): InetAddress? {
        val bare = text.substringBefore('%')
        val looksLiteral = ipv4.matches(bare) || (':' in bare && ipv6.matches(bare))
        if (!looksLiteral) return null
        // A literal of the right letters that still does not parse (an octet past 255) is no address at all.
        return try {
            InetAddress.getByName(bare)
        } catch (_: UnknownHostException) {
            null
        }
    }

    /** `10.0.0.0/8` as a prefix, and a bare address as the prefix that holds it alone; null for anything else. */
    public fun prefix(text: String): IpPrefix? {
        val address = parse(text.substringBefore('/').trim()) ?: return null
        val whole = address.address.size * Byte.SIZE_BITS
        val bits = if ('/' in text) text.substringAfter('/').trim().toIntOrNull() else whole
        return bits?.takeIf { it in 0..whole }?.let { IpPrefix(address, it) }
    }
}
