package splice.hub.holder

/** Negotiated only on the private control socket. No v2 data frame is sent before an offer is received. */
object HolderPairProtocol {
    fun offer(identity: String): String = "$PAIR_PREFIX offer $identity"

    fun offered(line: String): String? = argument(line, "offer")

    fun pair(claim: HolderPairClaim): String =
        "$PAIR_PREFIX pair ${claim.token} ${claim.identity} ${claim.generation}"

    fun claim(line: String): HolderPairClaim? {
        val words = line.takeIf { it.length <= MAX_PAIR_CHARS }?.split(' ') ?: return null
        val valid = words.size == PAIR_WORDS && words[0] == PAIR_PREFIX && words[1] == "pair"
        return words.takeIf { valid && it.drop(PAIR_TOKEN_INDEX).all(::atom) }?.let {
            HolderPairClaim(it[PAIR_TOKEN_INDEX], it[PAIR_IDENTITY_INDEX], it[PAIR_GENERATION_INDEX])
        }
    }

    fun paired(token: String): String = "$PAIR_PREFIX paired $token"

    fun pairedToken(line: String): String? = argument(line, "paired")

    fun barrier(round: String): String = "$PAIR_PREFIX barrier $round"

    fun barrierRound(line: String): String? = argument(line, "barrier")

    fun response(key: String, text: String): String = "$PAIR_PREFIX response $key $text"

    fun response(line: String): HolderReverseResponse? {
        val prefix = "$PAIR_PREFIX response "
        val rest = line.takeIf { it.startsWith(prefix) }?.removePrefix(prefix) ?: return null
        val end = rest.indexOf(' ')
        return rest.takeIf { end in 1..MAX_KEY_CHARS }?.let {
            val key = it.substring(0, end)
            key.takeIf(HolderWireKeys::reverse)?.let { valid -> HolderReverseResponse(valid, it.substring(end + 1)) }
        }
    }

    fun privateFrame(line: String): Boolean = line.startsWith(PAIR_PREFIX)

    private fun argument(line: String, verb: String): String? =
        line.takeIf { it.length <= MAX_PAIR_CHARS && it.startsWith("$PAIR_PREFIX $verb ") }
            ?.removePrefix("$PAIR_PREFIX $verb ")?.takeIf(::atom)

    private fun atom(value: String): Boolean = value.isNotEmpty() && value.all { it in '!'..'~' }
}

data class HolderPairClaim(val token: String, val identity: String, val generation: String)

data class HolderReverseResponse(val key: String, val text: String)

/** The same bounded decimal grammar is used on data and control, including legacy numeric keys. */
internal object HolderWireKeys {
    fun forward(value: String): Boolean = numbered(value, "f:")

    fun reverse(value: String): Boolean = numbered(value, "s:")

    fun accepts(value: String): Boolean = forward(value) || reverse(value)

    private fun numbered(value: String, prefix: String): Boolean =
        value.startsWith(prefix) && value.length in MIN_KEY_CHARS..MAX_KEY_CHARS &&
            value.drop(KEY_PREFIX_CHARS).all { it in '0'..'9' }
}

private const val PAIR_PREFIX = "@splice-holder/2"
private const val MAX_PAIR_CHARS = 2048
private const val PAIR_WORDS = 5
private const val PAIR_TOKEN_INDEX = 2
private const val PAIR_IDENTITY_INDEX = 3
private const val PAIR_GENERATION_INDEX = 4
private const val KEY_PREFIX_CHARS = 2
private const val MIN_KEY_CHARS = 3
private const val MAX_KEY_CHARS = 66
