// NEW: server mode on a laptop (docs/specs/server-mode.md, F2 and F8). The shell commands a session of a command on a
// team's server reads the key this laptop holds there through, so the key is never in the session's environment and a
// new sign-in reaches a running session: Claude Code's apiKeyHelper prints the key file, and its otelHeadersHelper
// prints the same key as a bearer, in the JSON object its telemetry export reads headers from. With no key either one
// says how to sign in, on stderr, and fails.
package splice.share

import java.nio.file.Path

/** The helpers of the commands on the server at [origin], reading the key from [keyFile]. */
public class KeyHelpers(private val origin: String, private val keyFile: Path) {
    /** The apiKeyHelper: it prints the key file and nothing else. */
    public fun apiKey(): String = "cat ${quoted(keyFile.toString())} 2>/dev/null || ${unsigned()}"

    /** The otelHeadersHelper: it prints `{"Authorization":"Bearer <key>"}`. */
    public fun otelHeaders(): String =
        "k=\$(cat ${quoted(keyFile.toString())} 2>/dev/null) && [ -n \"\$k\" ] || ${unsigned()}; " +
            "printf '{\"Authorization\":\"Bearer %s\"}\\n' \"\$k\""

    /** What a helper runs when it finds no key. */
    private fun unsigned(): String =
        "{ echo ${quoted("splice: not signed in to $origin; run: splice login $origin")} >&2; exit 1; }"

    private fun quoted(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
