package splice.daemonclient.health

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import splice.core.head.HeldTurnReading
import splice.core.usage.QuotaFull
import splice.core.usage.QuotaFullWindow
import splice.core.util.JsonScalars
import splice.daemonclient.DaemonProbe
import splice.daemonclient.DaemonProbe.HealthClient
import splice.daemonclient.DaemonProbe.HealthHeads
import splice.daemonclient.DaemonProbe.HealthMcpHub
import splice.daemonclient.DaemonProbe.HealthQuota
import splice.daemonclient.DaemonProbe.HealthTopology
import splice.daemonclient.DaemonProbe.HealthTurns
import splice.daemonclient.DaemonProbe.HealthView

/** Pure decoding of the daemon's health document, independent of an HTTP probe's lifetime. */
internal object DaemonHealthReading {
    fun parse(body: String): HealthView {
        val obj = DaemonProbe.json.parseToJsonElement(body).jsonObject
        return HealthView(
            version = JsonScalars.str(obj, "version"),
            ok = (obj["ok"] as? JsonPrimitive)?.booleanOrNull,
            heads = HealthHeads(
                total = JsonScalars.int(obj, "heads"),
                ready = JsonScalars.int(obj, "readyHeads"),
                failed = JsonScalars.int(obj, "failedHeads"),
                failedReasons = (obj["failedHeadReasons"] as? JsonObject)
                    ?.mapNotNull { (key, reason) -> JsonScalars.str(reason)?.let { key to it } }
                    ?.toMap()
                    .orEmpty(),
            ),
            topology = HealthTopology(
                digest = JsonScalars.str(obj, "topologyDigest"),
                stale = (obj["topologyStale"] as? JsonPrimitive)?.booleanOrNull,
            ),
            client = HealthClient(
                versionWarning = JsonScalars.str(obj, "clientVersionWarning"),
                mcpHub = when (JsonScalars.str(obj, "mcpHub")) {
                    "on" -> HealthMcpHub(on = true)
                    "off" -> HealthMcpHub(on = false, offReason = JsonScalars.str(obj, "mcpHubOffReason"))
                    else -> null
                },
            ),
            turns = turns(obj),
        )
    }

    private fun turns(obj: JsonObject): HealthTurns = HealthTurns(
        stalled = (obj["turnPathStalled"] as? JsonArray)
            ?.mapNotNull { JsonScalars.str(it) }
            .orEmpty(),
        quota = HealthQuota(
            refusedUntil = (obj["quotaResetAtEpochSeconds"] as? JsonObject)
                ?.mapNotNull { (key, reset) -> (reset as? JsonPrimitive)?.longOrNull?.let { key to it } }
                ?.toMap()
                .orEmpty(),
            full = (obj["quotaFull"] as? JsonObject)
                ?.mapNotNull { (key, reading) -> quotaFull(reading as? JsonObject)?.let { key to it } }
                ?.toMap()
                .orEmpty(),
        ),
        held = (obj["heldTurns"] as? JsonObject)
            ?.mapValues { (_, turns) -> (turns as? JsonArray).orEmpty().mapNotNull { heldTurn(it as? JsonObject) } }
            .orEmpty(),
    )

    private fun heldTurn(turn: JsonObject?): HeldTurnReading? {
        if (turn == null) return null
        return HeldTurnReading(
            untilEpochSeconds = JsonScalars.long(turn, "untilEpochSeconds"),
            login = JsonScalars.str(turn, "login") ?: return null,
            cause = JsonScalars.str(turn, "cause").orEmpty(),
            heapBytes = JsonScalars.long(turn, "heapBytes") ?: 0L,
        )
    }

    /** The reading, or null when it names no known window or no reset. */
    private fun quotaFull(reading: JsonObject?): QuotaFull? {
        val wire = reading?.let { JsonScalars.str(it, "window") }
        val window = QuotaFullWindow.entries.firstOrNull { it.wire == wire } ?: return null
        return JsonScalars.long(reading, "resetsAtEpochSeconds")?.let { QuotaFull(window, it) }
    }
}
