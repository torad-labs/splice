// NEW: a knob's default, typed by the kind of value the knob holds: a count, a text, a flag, or none.
package splice.core.config

/** The default a [Knob] starts from. Each kind is one arm, so a reader names the kind it expects. */
internal sealed class KnobDefault {
    internal data class Count(val value: Long) : KnobDefault()

    internal data class Text(val value: String) : KnobDefault()

    internal data class Flag(val value: Boolean) : KnobDefault()

    internal data object None : KnobDefault()

    /** The untyped form the config layers carry: a Long, a String, a Boolean, or null. */
    internal fun raw(): Any? =
        when (this) {
            is Count -> value
            is Text -> value
            is Flag -> value
            None -> null
        }
}
