package splice.head.turn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

/** The session a request names in its body: `metadata.user_id` is itself a JSON string holding `device_id`,
 *  `account_uuid` and `session_id`. The header carries the same id and stands in where the body does not. */
internal object SessionFromBody {
    private val userId = Json { ignoreUnknownKeys = true }

    /** The session id inside `metadata.user_id`; null when the body carries none or the string is not that. */
    fun of(raw: JsonObject): String? {
        val text = JsonScalars.str((raw["metadata"] as? JsonObject)?.get("user_id")) ?: return null
        return JsonScalars.str(JsonScalars.objectOrNull(userId, text), "session_id")?.takeIf(String::isNotBlank)
    }
}
