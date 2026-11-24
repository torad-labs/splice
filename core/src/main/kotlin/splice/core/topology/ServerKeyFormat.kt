package splice.core.topology

public const val DEFAULT_SERVER_KEY_PREFIX: String = "spk_"

// why: the public key ID is 8 lowercase alphanumerics, which names 36^8 keys, far past any team's count.
public const val SERVER_KEY_ID_LENGTH: Int = 8

// why: decision 4 admits any secret of 32 or more alphanumerics (190+ bits), so a key imported from an older or
// external issuer verifies by the same rule that admitted its hash, whatever length it was minted at.
internal const val SERVER_KEY_SECRET_MIN_LENGTH: Int = 32

/** The public shape of a Splice key, including those minted with a previous server's mark. */
public object ServerKeyFormat {
    private const val MARK_PATTERN = "[A-Za-z][A-Za-z0-9_-]{0,22}_"
    private const val PREFIX_PATTERN = "$MARK_PATTERN[a-z0-9]{$SERVER_KEY_ID_LENGTH}"
    private const val SECRET_PATTERN = "_[A-Za-z0-9]{$SERVER_KEY_SECRET_MIN_LENGTH,}"
    private val mark = Regex(MARK_PATTERN)
    private val prefix = Regex(PREFIX_PATTERN)
    private val key = Regex("$PREFIX_PATTERN$SECRET_PATTERN")
    private val embedded = Regex("(?=($PREFIX_PATTERN)$SECRET_PATTERN)")

    public fun validMark(text: String): Boolean = mark.matches(text)

    public fun validPrefix(text: String): Boolean = prefix.matches(text)

    public fun validKey(text: String): Boolean = key.matches(text)

    /** The prefix of every run of [text] with a key's shape. Overlapping matches keep a key detectable even when
     *  preceded by token-shaped text, and the secret's length is not bounded, so a key is found by its prefix. */
    public fun prefixesIn(text: String): Sequence<String> = embedded.findAll(text).map { it.groupValues[1] }
}
