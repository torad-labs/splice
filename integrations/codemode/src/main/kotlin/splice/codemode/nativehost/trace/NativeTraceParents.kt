package splice.codemode.nativehost.trace

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context

private const val NATIVE_TRACE_ID_HEX_DIGITS = 32
private const val NATIVE_SPAN_ID_HEX_DIGITS = 16
private const val NATIVE_MAX_TRACE_VERSION = 254
private const val NATIVE_MAX_TRACE_BYTE = 255
private const val NATIVE_TRACE_SUPPORTED_OPTIONS = 2
private const val NATIVE_TRACE_PARTS = 4
private const val NATIVE_TRACE_OPTIONS_INDEX = 3
private const val NATIVE_TRACE_HEX_RADIX = 16
private const val NATIVE_TRACE_SAMPLED_MASK = 1
private const val NATIVE_TRACE_WHITESPACE =
    "\t\n\u000B\u000C\r \u0085\u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005" +
        "\u2006\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000"

/** Matches the pinned Rust 0.31 propagator, including its numeric widths and version-zero option rules. */
internal object NativeTraceParents {
    fun context(value: String): Context? = parts(value)?.let(::remote)

    private fun parts(value: String): List<String>? {
        val parts = value.trim { it in NATIVE_TRACE_WHITESPACE }.removeSuffix(
            "-",
        ).split('-', limit = NATIVE_TRACE_PARTS + 1)
        if (parts.size < NATIVE_TRACE_PARTS) return null
        return byte(parts[0])?.takeIf { it <= NATIVE_MAX_TRACE_VERSION }?.let { version ->
            val options = byte(parts[NATIVE_TRACE_OPTIONS_INDEX])
            parts.takeIf { options != null && validVersion(version, parts.size, options) }
        }
    }

    private fun validVersion(version: Int, size: Int, options: Int): Boolean {
        if (version != 0) return true
        return size == NATIVE_TRACE_PARTS && options <= NATIVE_TRACE_SUPPORTED_OPTIONS
    }

    private fun remote(parts: List<String>): Context? {
        val trace = identifier(parts[1], NATIVE_TRACE_ID_HEX_DIGITS) ?: return null
        val span = identifier(parts[2], NATIVE_SPAN_ID_HEX_DIGITS) ?: return null
        val options = checkNotNull(byte(parts[NATIVE_TRACE_OPTIONS_INDEX]))
        val flags = if (options and NATIVE_TRACE_SAMPLED_MASK == NATIVE_TRACE_SAMPLED_MASK) {
            TraceFlags.getSampled()
        } else {
            TraceFlags.getDefault()
        }
        val context = SpanContext.createFromRemoteParent(trace, span, flags, TraceState.getDefault())
        return context.takeIf { it.isValid }?.let { Context.root().with(Span.wrap(it)) }
    }

    private fun byte(value: String): Int? {
        val digits = value.removePrefix("+")
        val valid = digits.isNotEmpty() && digits.all(::byteHexadecimal)
        return digits.takeIf { valid }?.toIntOrNull(NATIVE_TRACE_HEX_RADIX)?.takeIf { it in 0..NATIVE_MAX_TRACE_BYTE }
    }

    private fun identifier(value: String, width: Int): String? {
        val digits = value.removePrefix("+")
        val valid = digits.isNotEmpty() && digits.all(::lowerHexadecimal)
        val significant = digits.trimStart('0')
        return significant.takeIf { valid && it.length <= width }?.padStart(width, '0')
    }

    private fun lowerHexadecimal(value: Char): Boolean = value in '0'..'9' || value in 'a'..'f'

    private fun byteHexadecimal(value: Char): Boolean = lowerHexadecimal(value) || value in 'A'..'F'
}
