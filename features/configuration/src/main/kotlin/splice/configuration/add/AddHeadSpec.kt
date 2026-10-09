package splice.configuration.add

/** The head a profile adds: the [key] it is known by (provider AND head key) and the wrapper [command] that
 *  launches it. Only this module constructs one. */
@ConsistentCopyVisibility
public data class AddHeadSpec internal constructor(
    /** Default provider AND head key; empty when the operator must name it (`--name`). */
    public val key: String,
    /** Default wrapper command; empty means `claude-<key>`. */
    internal val command: String,
)
