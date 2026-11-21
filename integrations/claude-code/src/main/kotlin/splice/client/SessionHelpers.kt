// NEW: the commands a launched session's settings name for Claude Code to run when it needs a secret, so the secret is
// never in the session's environment: `apiKeyHelper` prints the bearer a head checks (key rotation, Oct 26), and
// `otelHeadersHelper` prints the headers Claude Code's telemetry export sends, as a JSON object (server mode, F8).
// The materialized settings.json and a wrapped launch's --settings overlay both write them from this one value.
package splice.client

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

/** The helpers a session's settings carry; a null one is not written. */
public data class SessionHelpers(val apiKey: String? = null, val otelHeaders: String? = null) {
    /** Writes each helper that is set under its settings key. */
    internal fun writeInto(settings: JsonObjectBuilder) {
        apiKey?.let { settings.put(Keys.API_KEY_HELPER, it) }
        otelHeaders?.let { settings.put(Keys.OTEL_HEADERS_HELPER, it) }
    }
}
