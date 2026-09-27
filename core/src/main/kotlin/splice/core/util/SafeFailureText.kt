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
}

public object SafeFailureText {

    /** Filesystem and network failures keep their full text — their messages are paths, hosts
     *  and timeouts, the useful safe diagnostics. Every other exception renders as a FIXED
     *  literal: parser messages can quote the input they failed on, and the class name is only
     *  reachable through overridable toString() (reflection is walled), so a throwable that
     *  overrides toString() colon-free would ride any prefix-taking render into diagnostics
     *  verbatim (codex probe, 2026-08-31). No virtual call happens outside the allowlist. */
    public fun render(failure: Throwable): String = when (failure) {
        is TopologyTypeFailure ->
            "splice.toml: ${failure.key} at line ${failure.line} expects ${failure.expected.label}"
        // SAFE-RENDER-EXEMPT[2026-09-01]: these exact filesystem/network classes carry paths,
        // hosts or timeouts, never parsed file values; this sanctioned renderer cannot recurse.
        is java.nio.file.FileSystemException,
        is java.net.SocketException,
        is java.net.UnknownHostException,
        is java.io.InterruptedIOException,
        is java.io.EOFException,
        -> failure.toString()
        else -> "failure (message withheld: it may quote file bytes)"
    }
}
