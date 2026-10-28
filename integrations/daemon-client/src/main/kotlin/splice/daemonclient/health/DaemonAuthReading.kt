package splice.daemonclient.health

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import splice.core.auth.CredentialVerdict
import splice.core.auth.CredentialVerdictRead
import splice.core.util.JsonScalars
import splice.daemonclient.DaemonProbe.HeadAuthSeen

/** Missing verdicts predate the feature; malformed sent verdicts remain unverified. */
internal object DaemonAuthReading {
    fun read(head: JsonObject): HeadAuthSeen {
        val sent = head["verdict"]?.takeUnless { it is JsonNull }
        val verdict = sent as? JsonObject
        val read = CredentialVerdictRead().of(
            JsonScalars.str(verdict, "state"),
            JsonScalars.long(verdict, "at_epoch_ms"),
        )
        return HeadAuthSeen(
            present = head["present"]?.jsonPrimitive?.booleanOrNull == true,
            verdict = when (sent) {
                null -> CredentialVerdict.Held
                is JsonPrimitive, is JsonObject, is JsonArray -> read ?: CredentialVerdict.Unverified
            },
        )
    }
}
