package splice.core.model

/** Evidence about the divisor a client uses to decide when to compact. */
public sealed class ClientWindow {
    /** The request supplies its own divisor. */
    public data class Stated(val tokens: Long) : ClientWindow()

    /** A record can outlive its process. A missing hint leaves only what the catalog can infer. */
    public data class Hint(val tokens: Long? = null) : ClientWindow()
}

/** The context window and scale used for one response's input counts. */
public data class UsageSizing(
    val contextWindow: Long? = null,
    val factor: Double = 1.0,
    val declaredWindow: Long? = null,
    val source: WindowSource = WindowSource.NONE,
) {
    public fun scale(tokens: Long): Long = if (factor == 1.0) tokens else (tokens * factor).toLong()
}

public enum class WindowSource(public val label: String) {
    REQUEST("stated by the request"),
    HINT("a window hint"),
    NONE("no window stated"),
}
