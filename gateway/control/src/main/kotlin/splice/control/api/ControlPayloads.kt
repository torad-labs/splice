// PORT-OF: ControlServer.kt (ControlPayloads) @ a77531a — invariants unchanged: the head-registry
// read model — /health, /api/status, /api/heads and their shared errorJson — split from the
// config/usage/perf/compact projections that each had their own subsystem import. Dropped its
// `config: ConfigService` ctor param: configJson (splice.core.config) and usageJson
// (splice.core.usage) moved to ConfigRoutes/UsagePayloads with them, so this class no longer reads
// config at all.
package splice.control.api

import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.control.FailedHeads
import splice.control.ManagedHead
import splice.control.TopologyStale
import splice.control.TurnPathStalled
import splice.core.GATEWAY_VERSION
import splice.core.SHIM_VERSION

private const val KEY = "key"
private const val LABEL = "label"
private const val HEADS = "heads"

// internal (was private) so ControlHealthTest can pin the ok/stall contract
internal class ControlPayloads(
    private val heads: Map<String, ManagedHead>,
    private val failedHeads: FailedHeads,
    private val configuredHeads: Int,
    private val topologyDigest: String = "",
    private val configPath: String = "",
    private val topologyStale: TopologyStale = TopologyStale { false },
    private val turnPathStalled: TurnPathStalled = TurnPathStalled { emptyList() },
) {

    fun controlHealthJson(): String = buildJsonObject {
        // ok means "this gateway can serve", not "heads are configured" — the 91h wedge served
        // ok:true for its entire duration under the old hardcoded value (2026-08-12). Precisely: no
        // head is unresponsive at its request path, none failed to start, and at least one is up.
        // It is NOT an end-to-end turn assertion — see TurnPathProbeLoop's header for the probe's
        // documented ceiling (it is answered at the 401 before the gate and driver).
        //
        // F4: only a head that is SUPPOSED to be running can drag ok false. A deliberate
        // `POST /api/heads/x/stop` makes the probe see connection-refused and mark the head
        // stalled, but that is an intentional state, not the wedge — intersecting with the
        // running set keeps an operator's maintenance stop from paging an external monitor.
        //
        // ...but "not running" is NOT self-certifying. The F4 intersection alone read a head that
        // CRASHED or never started as "not supposed to be running", so its stall entry was
        // discarded and a daemon whose every head died on EADDRINUSE served
        // {ok:true, readyHeads:0, failedHeads:4} — the same green-through-an-outage shape as the
        // 91h wedge, one layer over. failedHeads() is what separates a crash from a deliberate
        // stop, and a configured daemon with nothing running cannot complete a turn either way.
        val runningKeys = heads.filterValues { it.head.healthSnapshot().running }.keys
        val stalledHeads = turnPathStalled().filter { it in runningKeys }
        val running = runningKeys.size
        put("ok", stalledHeads.isEmpty() && failedHeads() == 0 && (configuredHeads == 0 || running > 0))
        if (stalledHeads.isNotEmpty()) {
            put(
                "turnPathStalled",
                kotlinx.serialization.json.JsonArray(stalledHeads.map { kotlinx.serialization.json.JsonPrimitive(it) }),
            )
        }
        put("version", GATEWAY_VERSION)
        put("wantShimVersion", SHIM_VERSION)
        // Configured total, NOT heads.size (assembled only) — see the ControlServer ctor comment.
        put(HEADS, configuredHeads)
        // Launch shims wait for readyHeads + failedHeads == heads before POSTing /launch (post
        // startDaemonHeads) — NOT readyHeads == heads: a start-failed head stays in `heads`
        // forever with running=false, so the old equality-wait spun forever on a degraded boot
        // (review 2026-07-22 round 3).
        put("readyHeads", running)
        put("failedHeads", failedHeads())
        // JW-04: the booted config identity — an edited splice.toml used to be silently inert
        // (topology loads once by design; nothing anywhere compared disk to boot). Stale is
        // recomputed per request and fails OPEN on an unreadable file.
        put("topologyDigest", topologyDigest)
        put("configPath", configPath)
        put("topologyStale", topologyStale())
    }.toString()

    fun statusJson(): String = buildJsonObject {
        put("server", "control")
        put("version", GATEWAY_VERSION)
        putJsonArray(HEADS) { heads.keys.forEach { add(it) } }
        putJsonArray("registry") {
            heads.values.forEach { m ->
                addJsonObject {
                    put(KEY, m.head.key)
                    put(LABEL, m.head.label)
                    put("authKind", m.authKind)
                }
            }
        }
    }.toString()

    fun headsJson(): String = buildJsonObject {
        putJsonArray(HEADS) { heads.values.forEach { add(headStatus(it)) } }
    }.toString()

    // PORT-OF server/launcher/heads.mjs status shape @ pre-public-port-baseline. In the single daemon the head is
    // in-process, so healthy/version are authoritative and versionMatch is always true when up.
    fun headStatus(m: ManagedHead) = buildJsonObject {
        val h = m.head.healthSnapshot()
        put(KEY, m.head.key)
        put(LABEL, m.head.label)
        put("name", m.head.key)
        put("port", m.head.port)
        put("authKind", m.authKind)
        put("wantVersion", GATEWAY_VERSION)
        put("running", h.running)
        put("healthy", h.ok)
        put("version", if (h.running) GATEWAY_VERSION else null as String?)
        put("versionMatch", if (h.running) true else null as Boolean?)
        put("mode", null as String?)
        // Live InflightGate snapshot for the dashboard (was permanently null after the Kotlin port).
        putJsonObject("gate") {
            put("inflight", h.gateInflight)
            put("queued", h.gateQueued)
            if (h.gateLimit <= 0) put("max", "unlimited") else put("max", h.gateLimit)
            // Counters the Node gate tracked; Kotlin gate has no acquired/released totals —
            // zero-fill so the GateSnapshot shape stays stable for the webui.
            put("acquired", 0)
            put("released", 0)
            put("waited", 0)
            put("avg_wait_ms", 0)
            putJsonArray("live") {}
            put("stream_idle_ms", 0)
        }
        put("maxInflight", if (h.gateLimit <= 0) null else h.gateLimit)
        // G20: passive per-head health counters, local-origin vs provider-error split — diagnosis
        // only, surfaced through this aggregation (never the per-head /health liveness route).
        putJsonObject("health") {
            put("localOriginErrors", h.localOriginErrors)
            put("providerErrors", h.providerErrors)
        }
        putJsonArray("pids") {}
    }

    fun errorJson(message: String): String = buildJsonObject { put("error", message) }.toString()

    /** The /api/daemon/shutdown ack body — `{"ok":true}`. */
    fun okJson(): String = buildJsonObject { put("ok", true) }.toString()
}
