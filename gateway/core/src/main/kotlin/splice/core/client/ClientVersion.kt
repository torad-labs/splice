// NEW: v0.4.0 FEATURES.md §2 — a dotted numeric Claude Code version, compared by numeric component, never as text.
package splice.core.client

import java.math.BigInteger

/** A dotted numeric Claude Code version, compared by numeric component rather than as text. */
public class ClientVersion internal constructor(
    public val wire: String,
    private val components: List<BigInteger>,
) : Comparable<ClientVersion> {

    override fun compareTo(other: ClientVersion): Int {
        repeat(maxOf(components.size, other.components.size)) { index ->
            val compared = component(index).compareTo(other.component(index))
            if (compared != 0) return compared
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is ClientVersion && compareTo(other) == 0

    override fun hashCode(): Int = components.dropLastWhile { it == BigInteger.ZERO }.hashCode()

    override fun toString(): String = wire

    private fun component(index: Int): BigInteger = components.getOrElse(index) { BigInteger.ZERO }
}

/** Parses the Claude Code version token without accepting an arbitrary product's version. */
public class ClientVersionParser {
    private val numeric = Regex("[0-9]+(?:\\.[0-9]+)+")
    private val claudeCli = Regex("(?:^|\\s)claude-cli/([0-9]+(?:\\.[0-9]+)+)(?=\\s|$)")

    public fun parse(value: String?): ClientVersion? {
        val wire = value?.trim()?.takeIf(numeric::matches) ?: return null
        return ClientVersion(wire, wire.split('.').map(::BigInteger))
    }

    public fun fromUserAgent(userAgent: String?): ClientVersion? =
        claudeCli.find(userAgent.orEmpty())?.groupValues?.get(1)?.let(::parse)
}
