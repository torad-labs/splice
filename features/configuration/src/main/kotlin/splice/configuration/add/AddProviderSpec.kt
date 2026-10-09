package splice.configuration.add

/** The provider table a profile renders: the wire [dialect] it speaks, the [authKind] that signs it in, whether the
 *  operator must supply a key, and any [extra] provider lines. Only this module constructs one. */
@ConsistentCopyVisibility
public data class AddProviderSpec internal constructor(
    internal val dialect: String,
    public val authKind: String,
    /** Whether an API-key profile needs a key from the operator. Local runtimes use a non-secret
     *  placeholder at save instead; OAuth and client profiles have their own sign-in path. */
    internal val requiresKey: Boolean = authKind == API_KEY,
    /** Extra provider lines, already valid TOML (a default vendor header, for one). */
    internal val extra: List<String> = emptyList(),
)
