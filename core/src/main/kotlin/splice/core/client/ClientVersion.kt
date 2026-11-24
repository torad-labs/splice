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
    // why: a version is read from what any key holder sends (a header, a check-in); its bounded shape keeps one
    // request from handing the comparison a number of any size or a list of any length.
    private val numeric = Regex("[0-9]{1,$MAX_COMPONENT_DIGITS}(?:\\.[0-9]{1,$MAX_COMPONENT_DIGITS}){1,$MAX_DOTS}")
    private val claudeCli = Regex("(?:^|\\s)claude-cli/([0-9]+(?:\\.[0-9]+)+)(?=\\s|$)")

    /** [value] as a version, or null when it is none: not dotted numbers, longer than [MAX_VERSION_CHARS], or with a
     *  number past [MAX_COMPONENT_DIGITS] digits or more than [MAX_DOTS] dots. */
    public fun parse(value: String?): ClientVersion? {
        val wire = value?.trim()?.takeIf { it.length <= MAX_VERSION_CHARS && numeric.matches(it) } ?: return null
        return ClientVersion(wire, wire.split('.').map(::BigInteger))
    }

    public fun fromUserAgent(userAgent: String?): ClientVersion? =
        claudeCli.find(userAgent.orEmpty())?.groupValues?.get(1)?.let(::parse)
}

/** The longest text read as a version. */
private const val MAX_VERSION_CHARS: Int = 128

/** The most digits one number of a version may have. */
private const val MAX_COMPONENT_DIGITS: Int = 18

/** The most dots a version may have, so the most numbers it may name is one more. */
private const val MAX_DOTS: Int = 15
