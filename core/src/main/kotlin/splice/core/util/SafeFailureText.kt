// NEW: DR-65 (codex security probe 2026-08-31) — one renderer for any failure whose exception
// text may quote the bytes of the file that produced it. kotlinx parse exceptions embed a
// "JSON input:" excerpt of the parsed input, so a bare `$failure` on a credential or state
// file's parse cause copied token/env bytes into daemon.log and /mgmt introspection.
package splice.core.util

// why: cap an operator-defined TOML key in the one diagnostic printed during a failed boot.
private const val MAX_DIAGNOSTIC_KEY_LENGTH = 512

/** A topology type diagnosis built only from a key, a source line and a fixed expected-type label.
 *  The parser's message and the operator's value never become fields or a cause. */
public class TopologyTypeFailure(
    public val key: String,
    public val line: Int,
    public val expected: Expected,
) : IllegalArgumentException("splice.toml: $key at line $line expects ${expected.label}") {
    public enum class Expected(public val label: String) {
        QUOTED_STRING("quoted string"),
        INTEGER("integer"),
        BOOLEAN("boolean"),
        NUMBER("number"),
        TABLE("table"),
        ARRAY("array"),
    }

    init {
        require(key.isNotBlank() && key.length <= MAX_DIAGNOSTIC_KEY_LENGTH)
        require(key.none { Character.isISOControl(it) })
        require(line > 0)
    }

    /** What to write for the key this names, by the type it expects (V4-366): the one fix that boot and
     *  `splice doctor` both print (V4-424). The example uses the key's own last segment and a
     *  placeholder, never the value that was there. */
    public fun fix(): String {
        val leaf = key.substringAfterLast('.')
        return when (expected) {
            Expected.QUOTED_STRING -> "put the value in double quotes, as in $leaf = \"...\""
            Expected.INTEGER -> "write a whole number, with no quotes"
            Expected.NUMBER -> "write a number, with no quotes"
            Expected.BOOLEAN -> "write true or false, with no quotes"
            Expected.TABLE -> "write it as a table, [$key], not as a single value"
            Expected.ARRAY -> "write it as an array, [ ... ]"
        }
    }
}

/** A fixed tier-declaration diagnosis. No source values or parser text enter its message. */
public class TopologySlotsFailure(public val problem: Problem) : IllegalArgumentException(problem.detail) {
    public enum class Problem(public val detail: String) {
        UNKNOWN("model_slots contains an unknown Claude tier; use opus, sonnet, haiku or fable"),
        DUPLICATE("model_slots contains duplicate tiers; keep one assignment per tier"),
        BLANK_ID("model_slots model ids must not be blank"),
        DUPLICATE_ID("model_slots assigns one model id to multiple tiers; keep one tier per model"),
        COMPETING(
            "models entries with slots and model_slots compete; keep slots in models or keep model_slots, not both",
        ),
    }

    public fun fix(): String = problem.detail
}

public object SafeFailureText {

    /** Filesystem and network failures keep their full text — their messages are paths, hosts
     *  and timeouts, the useful safe diagnostics. Every other exception renders as a FIXED
     *  literal: parser messages can quote the input they failed on, and the class name is only
     *  reachable through overridable toString() (reflection is walled), so a throwable that
     *  overrides toString() colon-free would ride any prefix-taking render into diagnostics
     *  verbatim (codex probe, 2026-08-31). No virtual call happens outside the allowlist. */
    public fun render(failure: Throwable): String = when (failure) {
        is TopologySlotsFailure -> failure.problem.detail
        is TopologyTypeFailure ->
            "splice.toml: ${failure.key} at line ${failure.line} expects ${failure.expected.label}"
        // These exact filesystem/network classes carry paths, hosts or timeouts, never parsed file values.
        // This function is the wall's sink, named by file and function in SafeFailureRenderLawTest.
        is java.nio.file.FileSystemException,
        is java.net.SocketException,
        is java.net.UnknownHostException,
        is java.io.InterruptedIOException,
        is java.io.EOFException,
        -> failure.toString()
        else -> "failure (message withheld: it may quote file bytes)"
    }

    /** The first line of the text a decoder gave for an input its CALLER masked before decoding, every secret in
     *  it replaced by the mask. Such a text can quote only the operator's own request and the mask, never a stored
     *  secret or file bytes, so it is the one throwable text this object hands out whole. A caller that has not
     *  masked its input uses [render]. */
    public fun maskedInputRefusal(failure: Throwable): String {
        return failure.message.orEmpty().lineSequence().first()
    }

    /** Where [failure] was thrown, as ` at File.kt:LINE` for the first frame in splice's own code. A
     *  code location never quotes content, so an invariant that fires on a live turn is locatable from
     *  daemon.log while its message stays withheld. Only a splice-package frame with a plain Kotlin
     *  source name counts; anything else, or a stackless throwable, renders as nothing. */
    public fun site(failure: Throwable): String {
        val frame = failure.stackTrace.firstOrNull { it.className.startsWith(SPLICE_PACKAGE) } ?: return ""
        val file = frame.fileName?.takeIf(KOTLIN_SOURCE::matches) ?: return ""
        return " at $file:${frame.lineNumber}"
    }
}

private const val SPLICE_PACKAGE = "splice."
private val KOTLIN_SOURCE = Regex("[A-Za-z0-9_]+\\.kt")
