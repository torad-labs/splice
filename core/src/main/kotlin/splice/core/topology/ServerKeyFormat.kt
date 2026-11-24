package splice.core.topology

public const val DEFAULT_SERVER_KEY_PREFIX: String = "spk_"

// why: the public key ID is 8 lowercase alphanumerics, which names 36^8 keys, far past any team's count.
public const val SERVER_KEY_ID_LENGTH: Int = 8

// why: decision 4 admits any secret of 32 or more alphanumerics (190+ bits), so a key imported from an older or
// external issuer verifies by the same rule that admitted its hash, whatever length it was minted at.
internal const val SERVER_KEY_SECRET_MIN_LENGTH: Int = 32

// why: 40 characters of 62 carry 238 bits, beyond guessing at any request rate, and above the 32 verification asks.
public const val SERVER_KEY_MINTED_LENGTH: Int = 40

// why: a mark is at most 24 characters ending with `_`, leaving at most 23 characters before that underscore.
private const val MARK_BODY_MAX_LENGTH: Int = 23

// why: scanning embedded secret prefixes up to 128 characters covers 32-to-128-character imported keys (up to 768
// bits) even before first admission, while keeping per-anchor work bounded on arbitrary header lengths.
private const val MAX_EMBEDDED_SECRET_LENGTH: Int = 128

/** The public shape of a Splice key, including those minted with a previous server's mark. */
public object ServerKeyFormat {
    private val markPattern = "[A-Za-z][A-Za-z0-9_-]{0,${MARK_BODY_MAX_LENGTH - 1}}_"
    private val prefixPattern = "$markPattern[a-z0-9]{$SERVER_KEY_ID_LENGTH}"
    private val bodyPattern = "_[A-Za-z0-9]{$SERVER_KEY_SECRET_MIN_LENGTH,}"
    private val mark = Regex(markPattern)
    private val prefix = Regex(prefixPattern)
    private val key = Regex("$prefixPattern$bodyPattern")

    public fun validMark(text: String): Boolean = mark.matches(text)

    public fun validPrefix(text: String): Boolean = prefix.matches(text)

    public fun validKey(text: String): Boolean = key.matches(text)

    /** Candidate keys embedded in [text], in scan order. Scanning from each `_<id>_` anchor handles any admitted
     *  secret length (32, 40, 64+) in linear time, finds keys preceded by token-shaped text, and checks both
     *  [knownSecretLengths] and (when [scanVariableLengths] is set) bounded embedded lengths when alphanumeric
     *  characters immediately follow a key. */
    public fun keysIn(
        text: String,
        knownSecretLengths: Set<Int> = emptySet(),
        scanVariableLengths: Boolean = true,
    ): Sequence<String> = sequence {
        var cursor = 0
        val minTail = SERVER_KEY_ID_LENGTH + 1 + SERVER_KEY_SECRET_MIN_LENGTH
        while (cursor + minTail < text.length) {
            val markEnd = text.indexOf('_', cursor)
            cursor = if (markEnd < 0 || markEnd + minTail >= text.length) {
                text.length
            } else {
                val tailEnd = validTailEnd(text, markEnd)
                if (tailEnd >= 0) {
                    val tailStart = markEnd + SERVER_KEY_ID_LENGTH + 2
                    val lengths = candidateLengths(tailEnd - tailStart, knownSecretLengths, scanVariableLengths)
                    yieldAll(anchorCandidates(text, markEnd, tailStart, tailEnd, lengths))
                    tailEnd
                } else {
                    markEnd + 1
                }
            }
        }
    }

    private fun validTailEnd(text: String, markEnd: Int): Int {
        val sep = markEnd + SERVER_KEY_ID_LENGTH + 1
        val tailStart = sep + 1
        val tailEnd = if (text[sep] == '_' && isLowerAlnumRange(text, markEnd + 1, sep)) {
            alnumRunEnd(text, tailStart)
        } else {
            -1
        }
        return if (tailEnd - tailStart >= SERVER_KEY_SECRET_MIN_LENGTH) tailEnd else -1
    }

    private fun candidateLengths(tailLen: Int, knownSecretLengths: Set<Int>, scanVariableLengths: Boolean): List<Int> {
        val lengths = LinkedHashSet<Int>()
        lengths += tailLen
        if (tailLen > SERVER_KEY_MINTED_LENGTH) lengths += SERVER_KEY_MINTED_LENGTH
        for (known in knownSecretLengths) {
            if (known in SERVER_KEY_SECRET_MIN_LENGTH until tailLen) lengths += known
        }
        if (scanVariableLengths) {
            val scanLimit = minOf(tailLen - 1, MAX_EMBEDDED_SECRET_LENGTH)
            for (len in SERVER_KEY_SECRET_MIN_LENGTH..scanLimit) lengths += len
        }
        return lengths.toList()
    }

    private fun anchorCandidates(
        text: String,
        markEnd: Int,
        tailStart: Int,
        tailEnd: Int,
        lengths: List<Int>,
    ): Sequence<String> = sequence {
        val starts = markStartWindow(text, markEnd).filter { isAsciiLetter(text[it]) }
        for (len in lengths) {
            val end = minOf(tailStart + len, tailEnd)
            for (start in starts) yield(text.substring(start, end))
        }
    }

    private fun markStartWindow(text: String, markEnd: Int): IntRange {
        val floor = maxOf(0, markEnd - MARK_BODY_MAX_LENGTH)
        var start = markEnd
        while (start > floor && isMarkBody(text[start - 1])) start -= 1
        return start until markEnd
    }

    private fun isLowerAlnumRange(text: String, from: Int, until: Int): Boolean {
        for (i in from until until) {
            if (!isLowerAlnum(text[i])) return false
        }
        return true
    }

    private fun alnumRunEnd(text: String, from: Int): Int {
        var end = from
        while (end < text.length && isAsciiAlnum(text[end])) end += 1
        return end
    }

    private fun isAsciiLetter(ch: Char): Boolean = ch in 'A'..'Z' || ch in 'a'..'z'

    private fun isLowerAlnum(ch: Char): Boolean = ch in 'a'..'z' || ch in '0'..'9'

    private fun isAsciiAlnum(ch: Char): Boolean = isAsciiLetter(ch) || ch in '0'..'9'

    private fun isMarkBody(ch: Char): Boolean = isAsciiAlnum(ch) || ch == '_' || ch == '-'
}
