package splice.core.topology

public const val DEFAULT_SERVER_KEY_PREFIX: String = "spk_"
public const val SERVER_KEY_ID_LENGTH: Int = 8
public const val SERVER_KEY_SECRET_LENGTH: Int = 40

/** The public shape of a Splice key, including those minted with a previous server's mark. */
public object ServerKeyFormat {
    private const val MARK = "[A-Za-z][A-Za-z0-9_-]{0,22}_"
    private const val PREFIX = "$MARK[a-z0-9]{$SERVER_KEY_ID_LENGTH}"
    private const val KEY = "${PREFIX}_[A-Za-z0-9]{$SERVER_KEY_SECRET_LENGTH}"
    private val mark = Regex(MARK)
    private val prefix = Regex(PREFIX)
    private val key = Regex(KEY)
    private val embedded = Regex("(?=($KEY))")

    public fun validMark(text: String): Boolean = mark.matches(text)

    public fun validPrefix(text: String): Boolean = prefix.matches(text)

    public fun validKey(text: String): Boolean = key.matches(text)

    /** Overlapping matches keep a key detectable even when preceded by token-shaped text. */
    public fun keysIn(text: String): Sequence<String> = embedded.findAll(text).map { it.groupValues[1] }
}
