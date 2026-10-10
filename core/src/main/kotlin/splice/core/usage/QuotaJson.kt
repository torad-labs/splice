// NEW: the on-disk shape of a QuotaSnapshot (<head>-quota.json). Flat and forgiving: a missing
// or malformed file is "no snapshot yet", never a failed head.
package splice.core.usage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.core.util.JsonScalars
import splice.core.util.JsonWire

// why: the one spelling of a window's used share on disk and on the wire, for whole windows and per-model ones alike.
private const val USED = "used_percent"

public class QuotaJson {
    private val json = Json { ignoreUnknownKeys = true }

    public fun encode(snapshot: QuotaSnapshot): String = JsonWire.string(
        buildJsonObject {
            snapshot.fiveHour?.let { w -> putJsonObject("five_hour") { window(this, w) } }
            snapshot.sevenDay?.let { w -> putJsonObject("seven_day") { window(this, w) } }
            snapshot.plan?.let { put("plan", it) }
            if (snapshot.models.isNotEmpty()) putModels(this, "models", snapshot.models)
            put("updated_at", snapshot.updatedAt)
        },
    )

    // Corrupt content is no snapshot until the next poll rewrites it (QuotaTracker.readFile).
    public fun decode(text: String): QuotaSnapshot? {
        val root = JsonScalars.objectOrNull(json, text) ?: return null
        return QuotaSnapshot(
            fiveHour = (root["five_hour"] as? JsonObject)?.let(::window),
            sevenDay = (root["seven_day"] as? JsonObject)?.let(::window),
            plan = (root["plan"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            updatedAt = (root["updated_at"] as? JsonPrimitive)?.longOrNull ?: 0L,
            models = (root["models"] as? JsonArray).orEmpty().mapNotNull(::model),
        ).takeIf { !it.isEmpty || it.answeredEmpty }
    }

    /** The per-model weekly windows as every surface writes them: `[{"model": "Opus", "used_percent": 41.0}]`. */
    public fun putModels(into: JsonObjectBuilder, key: String, models: List<ModelQuota>) {
        into.putJsonArray(key) {
            models.forEach { m ->
                addJsonObject {
                    put("model", m.model)
                    put(USED, m.usedPercent)
                }
            }
        }
    }

    private fun model(element: JsonElement): ModelQuota? {
        val obj = element as? JsonObject ?: return null
        val name = (obj["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return (obj[USED] as? JsonPrimitive)?.doubleOrNull?.let { ModelQuota(name, it) }
    }

    private fun window(into: JsonObjectBuilder, w: QuotaWindow) {
        into.put(USED, w.usedPercent)
        w.resetsAt?.let { into.put("resets_at", it) }
        w.windowSeconds?.let { into.put("window_seconds", it) }
    }

    private fun window(obj: JsonObject): QuotaWindow? {
        val used = (obj[USED] as? JsonPrimitive)?.doubleOrNull ?: return null
        return QuotaWindow(
            usedPercent = used,
            resetsAt = (obj["resets_at"] as? JsonPrimitive)?.longOrNull,
            windowSeconds = (obj["window_seconds"] as? JsonPrimitive)?.longOrNull,
        )
    }
}
