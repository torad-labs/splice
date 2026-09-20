// NEW: V4-132 (FEATURES.md §4.5 "Claude windows", §6 "statusline rate_limits capture") — Claude
// Code's OWN `rate_limits` object on the statusline payload — `five_hour`, `seven_day`,
// `seven_day_opus`, `seven_day_sonnet` and a `model_scoped` list of per-model weekly windows,
// gated on `rate_limits_available` (false for API-key/Bedrock/Vertex sessions, which never gate a
// real window behind a stale default; FEATURES.md §4.5). WINDOW FIELDS ONLY: session cost and
// per-model usage on the same payload stay unrecorded (PRODUCT.md's per-turn-body rule).
//
// Split out of StatuslineRenderer.kt (concentration, 2026-09-20): the capture's own state machine
// and its three value types moved the renderer from moderate into band HIGH the same way
// StatuslineBars/StatuslineRow/StatuslineWindowLearner already left it for — same relocation, no
// behaviour change, [MODEL_FIELD] (StatuslineRenderer.kt) is the one call site this file still
// reaches across the split.
//
// IN-MEMORY ONLY, unlike [ClientWindows]: this store's only reader is a console surface, and a
// live session re-teaches it on its next tick, so a daemon restart losing it costs one tick, not a
// session's whole compaction point the way losing [ClientWindows] did. Bounded (LRU eviction) by
// both session and account for the same reason [ClientWindows] is — sessions and accounts come and
// go for the daemon's whole life.
package splice.control

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import splice.core.util.JsonScalars

internal class StatuslineRateLimits(private val capacity: Int = RATE_LIMIT_CAPACITY) {
    private val lock = Any()
    private val bySession = boundedRateLimitMap()
    private val byAccount = boundedRateLimitMap()

    // A member, never a top-level fun (the wall bans those): a fresh bounded, access-order
    // LinkedHashMap, the same eviction shape ClientWindows.kt uses for the same reason — sessions
    // and accounts come and go for the daemon's whole life.
    private fun boundedRateLimitMap(): LinkedHashMap<String, RateLimitCapture> {
        val initial = RATE_LIMIT_MAP_INITIAL_CAPACITY
        val load = RATE_LIMIT_MAP_LOAD_FACTOR
        return object : LinkedHashMap<String, RateLimitCapture>(initial, load, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RateLimitCapture>?) = size > capacity
        }
    }

    fun record(sessionId: String?, accountLabel: String?, root: JsonObject) {
        if (sessionId.isNullOrEmpty()) return
        val limits = root["rate_limits"] as? JsonObject ?: return
        if ((limits["rate_limits_available"] as? JsonPrimitive)?.booleanOrNull != true) return
        val capture = RateLimitCapture(
            accountLabel = accountLabel,
            fiveHour = window(limits, "five_hour"),
            sevenDay = window(limits, "seven_day"),
            sevenDayOpus = window(limits, "seven_day_opus"),
            sevenDaySonnet = window(limits, "seven_day_sonnet"),
            modelScoped = modelScoped(limits["model_scoped"]),
        )
        synchronized(lock) {
            bySession[sessionId] = capture
            accountLabel?.let { byAccount[it] = capture }
        }
    }

    fun forSession(sessionId: String): RateLimitCapture? = synchronized(lock) { bySession[sessionId] }

    fun forAccount(accountLabel: String): RateLimitCapture? = synchronized(lock) { byAccount[accountLabel] }

    private fun window(limits: JsonObject, key: String): RateLimitWindow? {
        val w = limits[key] as? JsonObject ?: return null
        val pct = (w["used_percentage"] as? JsonPrimitive)?.doubleOrNull ?: return null
        return RateLimitWindow(pct, (w["resets_at"] as? JsonPrimitive)?.longOrNull)
    }

    private fun modelScoped(element: JsonElement?): List<ModelScopedWindow> {
        val entries = element as? JsonArray ?: return emptyList()
        return entries.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val model = JsonScalars.str(obj, MODEL_FIELD) ?: return@mapNotNull null
            val pct = (obj["used_percentage"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            ModelScopedWindow(model, pct, (obj["resets_at"] as? JsonPrimitive)?.longOrNull)
        }
    }
}

internal data class RateLimitWindow(val usedPercent: Double, val resetsAtEpochSeconds: Long?)

internal data class ModelScopedWindow(val model: String, val usedPercent: Double, val resetsAtEpochSeconds: Long?)

internal data class RateLimitCapture(
    val accountLabel: String?,
    val fiveHour: RateLimitWindow?,
    val sevenDay: RateLimitWindow?,
    val sevenDayOpus: RateLimitWindow?,
    val sevenDaySonnet: RateLimitWindow?,
    val modelScoped: List<ModelScopedWindow>,
)

// why: sized like ClientWindows.DEFAULT_CAPACITY (Kotlin/core/model/ClientWindows.kt) — this store
// keeps the same shape of thing (one entry per live session, plus one per pooled account) for the
// same daemon lifetime, so it is bounded the same way rather than picking a fresh number.
private const val RATE_LIMIT_CAPACITY = 512
private const val RATE_LIMIT_MAP_INITIAL_CAPACITY = 16
private const val RATE_LIMIT_MAP_LOAD_FACTOR = 0.75f
