// NEW: the /health body, assembled from the head set's verdict, the per-head readings and the booted config's
// identity. Every launch shim ever shipped reads its flat version, wantShimVersion and topologyStale, which stay. Oct 12:
// bootedAtEpochMillis, setupState, setupCommand, runtimeNotAnswering and configPath go, as nothing read them here.
package splice.app.control.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.app.control.TopologyDigest
import splice.configuration.topology.TopologyStale
import splice.core.GATEWAY_VERSION
import splice.core.SHIM_VERSION
import splice.core.version.ClientVersionTracker
import splice.core.wire.ControlFields

internal class ControlHealthReport(
    private val readiness: HeadReadiness,
    private val signals: HeadSignals,
    private val topologyDigest: TopologyDigest,
    private val topologyStale: TopologyStale,
    private val clientVersions: ClientVersionTracker,
    private val mcpHub: McpHubVerdict? = null,
) {
    /** The /health body, read at the current instant. */
    fun json(): String = json(System.currentTimeMillis())

    /** The topology staleness probe the configuration route reads: the same probe /health reports as topologyStale. */
    fun staleness(): TopologyStale = topologyStale

    /** [json] at a given instant: the quota instants in the body are read against it, so a test can pin them. */
    internal fun json(nowEpochMillis: Long): String {
        val head = readiness.snapshot()
        return buildJsonObject {
            put("ok", head.ok)
            if (head.stalled.isNotEmpty()) {
                put("turnPathStalled", JsonArray(head.stalled.map { JsonPrimitive(it) }))
            }
            put("version", GATEWAY_VERSION)
            put("wantShimVersion", SHIM_VERSION)
            clientVersions.aggregateWarning()?.let { put("clientVersionWarning", it) }
            // Configured total, NOT heads.size (assembled only) — see the ControlServer ctor comment.
            put(ControlFields.HEADS, head.configured)
            // ready + failed == heads, NOT ready == heads: a start-failed head stays in `heads` forever with
            // running=false (review 2026-07-22 round 3). Doctor and the e2e rigs read the three.
            put("readyHeads", head.running)
            put("failedHeads", head.failed)
            putFailedHeadReasons(this, head.reasons)
            putQuotaResets(this, nowEpochMillis)
            putQuotaFull(this)
            putHeldTurns(this)
            // JW-04: the booted config identity — an edited splice.toml used to be silently inert
            // (topology loads once by design; nothing anywhere compared disk to boot). Stale is
            // recomputed per request and fails OPEN on an unreadable file. V4-162: the digest is the
            // version the daemon RUNS, which a window-only edit moves, so it is read per request too.
            put("topologyDigest", topologyDigest())
            put("topologyStale", topologyStale())
            mcpHub?.let { hub ->
                put("mcpHub", if (hub.offReason == null) "on" else "off")
                hub.offReason?.let { put("mcpHubOffReason", it) }
            }
        }.toString()
    }

    /** V4-394: which heads failed, and why, beside the count; absent on a healthy boot so the old
     *  shape is unchanged for every reader that never asks. */
    private fun putFailedHeadReasons(into: JsonObjectBuilder, reasons: Map<String, String>) {
        if (reasons.isEmpty()) return
        into.putJsonObject("failedHeadReasons") { reasons.forEach { (key, reason) -> put(key, reason) } }
    }

    /** V4-398: /health puts each refusing head's instant in `quotaResetAtEpochSeconds`; absent when none. */
    private fun putQuotaResets(into: JsonObjectBuilder, nowEpochMillis: Long) {
        val resets = signals.quotaResets(nowEpochMillis)
        if (resets.isEmpty()) return
        into.putJsonObject("quotaResetAtEpochSeconds") { resets.forEach { (key, reset) -> put(key, reset) } }
    }

    /** Failure-handling slice 5: each head's held turns, oldest first, as `heldTurns: {head: [{until, login, cause,
     *  heapBytes}]}`; absent when no turn is held, so a quiet daemon's shape is unchanged. */
    private fun putHeldTurns(into: JsonObjectBuilder) {
        val held = signals.heldTurns()
        if (held.isEmpty()) return
        into.putJsonObject("heldTurns") {
            held.forEach { (key, turns) ->
                putJsonArray(key) {
                    turns.forEach { turn ->
                        add(
                            buildJsonObject {
                                turn.untilEpochSeconds?.let { put("untilEpochSeconds", it) }
                                put("login", turn.login)
                                put("cause", turn.cause)
                                put("heapBytes", turn.heapBytes)
                            },
                        )
                    }
                }
            }
        }
    }

    /** V4-452: [HeadSignals.quotaFull] as /health's object, one `{window, resetsAtEpochSeconds}` per head;
     *  absent when no reading is full, so a healthy daemon's shape is unchanged. */
    private fun putQuotaFull(into: JsonObjectBuilder) {
        val full = signals.quotaFull()
        if (full.isEmpty()) return
        into.putJsonObject("quotaFull") { full.forEach { (key, reading) -> put(key, signals.quotaFullJson(reading)) } }
    }
}

/** Whether this daemon runs the MCP hub, as McpHubGate decided at boot: [offReason] is why not, in the words the hub
 *  routes' 503 gives, or null when it is on. Doctor reads it off /health (`mcpHub`, `mcpHubOffReason`). */
internal data class McpHubVerdict(val offReason: String?)
