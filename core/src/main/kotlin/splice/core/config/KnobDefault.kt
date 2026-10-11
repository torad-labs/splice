// NEW: a knob's default, typed by the kind of value the knob holds: a count, a text, a flag, or none.
package splice.core.config

import splice.core.topology.AuthKind

/** The default a [Knob] starts from. Each kind is one arm, so a reader names the kind it expects. */
internal sealed class KnobDefault {
    internal data class Count(val value: Long) : KnobDefault()

    internal data class Text(val value: String) : KnobDefault()

    /** The file a login writes: the text is the registry's own, referenced and never copied, so the knob and the
     *  login cannot drift apart (the DR-79 class). */
    internal data object ChatgptLoginFile : KnobDefault()

    internal data object GrokLoginFile : KnobDefault()

    internal data class Flag(val value: Boolean) : KnobDefault()

    internal data object None : KnobDefault()

    /** The untyped form the config layers carry: a Long, a String, a Boolean, or null. */
    internal fun raw(): Any? =
        when (this) {
            is Count -> value
            is Text -> value
            is Flag -> value
            ChatgptLoginFile -> AuthKind.ChatgptOAuth.authFile
            GrokLoginFile -> AuthKind.GrokOAuth.authFile
            None -> null
        }
}
