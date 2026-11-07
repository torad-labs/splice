package splice.core.perf.observation

/** Validate the telemetry wire alphabet without allocating a regex matcher for each observation. */
internal object PerfDimensionText {
    fun label(value: String): Boolean = startsWithAlphanumeric(value, LABEL_LENGTH) &&
        value.all { identifierCharacter(it) || it == '/' }

    fun identifier(value: String): Boolean = startsWithAlphanumeric(value, IDENTIFIER_LENGTH) &&
        value.all(::identifierCharacter)

    fun trace(value: String): Boolean = nonzeroHex(value, TRACE_LENGTH)

    fun span(value: String): Boolean = nonzeroHex(value, SPAN_LENGTH)

    fun flags(value: String): Boolean = value.length == FLAGS_LENGTH && value.all(::hexCharacter)

    private fun startsWithAlphanumeric(value: String, limit: Int): Boolean =
        value.length in 1..limit && alphanumeric(value[0])

    private fun nonzeroHex(value: String, length: Int): Boolean =
        value.length == length && value.all(::hexCharacter) && value.any { it != '0' }

    private fun alphanumeric(value: Char): Boolean = when (value) {
        in 'A'..'Z', in 'a'..'z', in '0'..'9' -> true
        else -> false
    }

    private fun identifierCharacter(value: Char): Boolean = when (value) {
        '.', '_', ':', '-' -> true
        else -> alphanumeric(value)
    }

    private fun hexCharacter(value: Char): Boolean = when (value) {
        in '0'..'9', in 'a'..'f' -> true
        else -> false
    }
}

// why: Preserve the existing bounded dimension/identifier grammar and W3C trace wire widths.
private const val LABEL_LENGTH = 128
private const val IDENTIFIER_LENGTH = 160
private const val TRACE_LENGTH = 32
private const val SPAN_LENGTH = 16
private const val FLAGS_LENGTH = 2
