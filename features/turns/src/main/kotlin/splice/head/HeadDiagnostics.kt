// PORT-OF: splice/gateway/head/HeadServer.kt (healthSnapshot's HeadHealth assembly, healthJson,
// modelsJson, the Json instance) @ 1caedd6 — invariants unchanged: the two read-only reporting
// routes and the control-plane's passive health snapshot; EVERY catalog model still gets a
// discovery row. Split out (HD-24) because the reporting plane shares no mutable state with
// admission or lifecycle. Liveness arrives as the [running] parameter rather than a back-reference
// to HeadEngine, which owns the route table that calls back into here. The PORT arrives the same way
// (2026-09-23): it was a constructor Int, the configured number, so a head bound on port 0 reported
// 0 on /health; HeadEngine owns the bound port and passes it at call time.
package splice.head

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.GATEWAY_VERSION
import splice.core.head.GateCounts
import splice.core.head.GateHealth
import splice.core.head.GateWatchdog
import splice.core.head.HeadHealth
import splice.core.model.DiscoveryRow
import splice.core.turn.LiveWatchdogBudget
import splice.core.util.JsonWire
import splice.head.turn.TurnDriver
import splice.head.wire.WIRE_TAP_OFF
import splice.head.wire.WireTap
import splice.upstream.Provider
import splice.upstream.retry.InflightGate

internal class HeadDiagnostics(
    private val provider: Provider,
    /** The ONE thing this collaborator needs from the head (V4-105 item 3): it reads `deps.traffic.gate`
     *  and nothing else, so the whole 25-parameter bundle was carried to reach one snapshot. */
    private val gate: InflightGate,
    private val driver: TurnDriver,
    /** V4-173: null on every head whose operator did not turn the tap on. */
    private val wireTap: WireTap?,
    /** The live watchdog tiers; null where no knob is live, and the provider's own budget stands. */
    private val liveWatchdog: LiveWatchdogBudget? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** [port] is the one the head listens on (HeadEngine.port), not the configured number. */
    fun healthSnapshot(running: Boolean, port: Int): HeadHealth {
        val counts = driver.healthCounters()
        val gateSnap = gate.snapshot()
        return HeadHealth(
            ok = running,
            running = running,
            port = port,
            version = GATEWAY_VERSION,
            localOriginErrors = counts.localOrigin,
            providerErrors = counts.providerError,
            gate = GateHealth(
                inflight = gateSnap.inflight,
                queued = gateSnap.queued,
                limit = gateSnap.limit,
                counts = GateCounts(
                    acquired = gateSnap.traffic.acquired,
                    released = gateSnap.traffic.released,
                    waited = gateSnap.traffic.waited,
                    avgWaitMs = gateSnap.traffic.avgWaitMs,
                ),
                live = gateSnap.live,
                // The first-output and re-anchor tiers are live knobs, read as a turn would read them now; the
                // idle tier is restartRequired (Knob.STREAM_IDLE_MS), the one the head was built with. A tier with
                // no limit is INFINITE here and crosses as null rather than as the Long.MAX_VALUE it converts to,
                // because a reader handed that number draws a real ceiling.
                watchdog = (liveWatchdog?.invoke() ?: provider.watchdog).let { now ->
                    GateWatchdog(
                        streamIdleMs = now.streamIdle.inWholeMilliseconds,
                        firstByteTimeoutMs = now.firstByteTimeout.takeIf { it.isFinite() }?.inWholeMilliseconds,
                        stallReanchorMs = now.stallReanchor.takeIf { it.isFinite() }?.inWholeMilliseconds,
                    )
                },
            ),
        )
    }

    fun rateLimitSnapshot(): splice.core.head.RateLimitHealth = driver.rateLimitSnapshot()

    /** GET /health. [port] is the one the head listens on (HeadEngine.port), not the configured one. */
    fun healthJson(port: Int): String = JsonWire.string(
        buildJsonObject {
            put("ok", true)
            put("port", port)
            put("version", GATEWAY_VERSION)
            put("head", provider.key)
        },
    )

    /** V4-173: GET /wire — the last [last] upstream request bodies this head sent, or null when the
     *  tap is off so the route can say which knob turns it on rather than answer an empty list a
     *  reader would take for "nothing was sent". */
    fun wireJson(last: Int): String? = wireTap?.json(provider.key, last)

    fun wireOffJson(): String = WIRE_TAP_OFF.replace("KEY", provider.key)

    fun modelsJson(): String {
        // EVERY catalog model gets a discovery row, including the pinned one — Claude Code needs
        // each id present so its display_name supplies the /model picker + status label (the pinned
        // model is otherwise missing from the picker). Which rows actually show is curated by the
        // availableModels allowlist in settings.json, not here.
        val rows = provider.catalog.discoveryRows()
        return JsonWire.string(
            buildJsonObject {
                put("object", "list")
                put(
                    "data",
                    buildJsonArray { rows.forEach { add(json.encodeToJsonElement(DiscoveryRow.serializer(), it)) } },
                )
            },
        )
    }
}
