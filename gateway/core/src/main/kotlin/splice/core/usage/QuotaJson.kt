// NEW: the on-disk shape of a QuotaSnapshot (<head>-quota.json). Flat and forgiving: a missing
// or malformed file is "no snapshot yet", never a failed head.
package splice.core.usage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.util.Cancellables

public class QuotaJson {
    private val json = Json { ignoreUnknownKeys = true }

    public fun encode(snapshot: QuotaSnapshot): String = buildJsonObject {
        snapshot.fiveHour?.let { w -> putJsonObject("five_hour") { window(this, w) } }
        snapshot.sevenDay?.let { w -> putJsonObject("seven_day") { window(this, w) } }
        snapshot.plan?.let { put("plan", it) }
        put("updated_at", snapshot.updatedAt)
    }.toString()

    public fun decode(text: String): QuotaSnapshot? = Cancellables.runCatchingCancellable {
        val root = json.parseToJsonElement(text).jsonObject
        QuotaSnapshot(
            fiveHour = (root["five_hour"] as? JsonObject)?.let(::window),
            sevenDay = (root["seven_day"] as? JsonObject)?.let(::window),
            plan = (root["plan"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            updatedAt = (root["updated_at"] as? JsonPrimitive)?.longOrNull ?: 0L,
        )
    }.getOrNull()?.takeIf { !it.isEmpty }

    private fun window(into: JsonObjectBuilder, w: QuotaWindow) {
        into.put("used_percent", w.usedPercent)
        w.resetsAt?.let { into.put("resets_at", it) }
        w.windowSeconds?.let { into.put("window_seconds", it) }
    }

    private fun window(obj: JsonObject): QuotaWindow? {
        val used = (obj["used_percent"] as? JsonPrimitive)?.doubleOrNull ?: return null
        return QuotaWindow(
            usedPercent = used,
            resetsAt = (obj["resets_at"] as? JsonPrimitive)?.longOrNull,
            windowSeconds = (obj["window_seconds"] as? JsonPrimitive)?.longOrNull,
        )
    }
}
