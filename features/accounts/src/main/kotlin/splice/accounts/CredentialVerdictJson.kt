// NEW: V4-220 item 6b — the one JSON shape of a credential's verdict, written into every auth object the
// control plane serves (/api/auth per head, and each pooled account's `auth`), so the console reads one
// shape wherever it meets a credential: {state, at_epoch_ms?}.
package splice.accounts

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.auth.CredentialVerdict

internal class CredentialVerdictJson {
    fun write(into: JsonObjectBuilder, verdict: CredentialVerdict) {
        into.putJsonObject("verdict") {
            put("state", verdict.wire)
            verdict.atEpochMs?.let { put("at_epoch_ms", it) }
        }
    }
}
