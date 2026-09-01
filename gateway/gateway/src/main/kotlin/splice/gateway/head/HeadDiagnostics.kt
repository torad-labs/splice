// PORT-OF: splice/gateway/head/HeadServer.kt (healthSnapshot's HeadHealth assembly, healthJson,
// modelsJson, the Json instance) @ 1caedd6 — invariants unchanged: the two read-only reporting
// routes and the control-plane's passive health snapshot; EVERY catalog model still gets a
// discovery row. Split out (HD-24) because the reporting plane shares no mutable state with
// admission or lifecycle. Liveness arrives as the [running] parameter rather than a back-reference
// to HeadEngine, which owns the route table that calls back into here.
package splice.gateway.head

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.GATEWAY_VERSION
import splice.core.head.HeadHealth
import splice.core.model.DiscoveryRow
import splice.spi.Provider

internal class HeadDiagnostics(
    private val provider: Provider,
    private val listenPort: Int,
    private val deps: HeadDeps,
    private val driver: TurnDriver,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun healthSnapshot(running: Boolean): HeadHealth {
        val counts = driver.healthCounters()
        val gateSnap = deps.gate.snapshot()
        return HeadHealth(
            ok = running,
            running = running,
            port = listenPort,
            version = GATEWAY_VERSION,
            localOriginErrors = counts.localOrigin,
            providerErrors = counts.providerError,
            gateInflight = gateSnap.inflight,
            gateQueued = gateSnap.queued,
            gateLimit = gateSnap.limit,
        )
    }

    fun healthJson(): String = buildJsonObject {
        put("ok", true)
        put("port", listenPort)
        put("version", GATEWAY_VERSION)
        put("head", provider.key)
    }.toString()

    fun modelsJson(): String {
        // EVERY catalog model gets a discovery row, including the pinned one — Claude Code needs
        // each id present so its display_name supplies the /model picker + status label (the pinned
        // model is otherwise missing from the picker). Which rows actually show is curated by the
        // availableModels allowlist in settings.json, not here.
        val rows = provider.catalog.discoveryRows()
        return buildJsonObject {
            put("object", "list")
            put(
                "data",
                buildJsonArray { rows.forEach { add(json.encodeToJsonElement(DiscoveryRow.serializer(), it)) } },
            )
        }.toString()
    }
}
