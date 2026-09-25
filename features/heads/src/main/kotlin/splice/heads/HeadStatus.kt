// PORT-OF: control/api/HeadResolver.kt — the heads capability owns its byte-stable status projection.
package splice.heads

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.core.GATEWAY_VERSION
import splice.core.head.GatePhase
import splice.core.head.Head

/** Projects the current state of one runtime head using the established control API shape. */
public object HeadStatus {
    public fun json(head: Head, authKind: String): JsonObject = buildJsonObject {
        val h = head.healthSnapshot()
        put("key", head.key)
        put("label", head.label)
        put("name", head.key)
        put("port", head.port)
        put("authKind", authKind)
        put("wantVersion", GATEWAY_VERSION)
        put("running", h.running)
        put("healthy", h.ok)
        put("version", if (h.running) GATEWAY_VERSION else null as String?)
        put("versionMatch", if (h.running) true else null as Boolean?)
        put("mode", null as String?)
        putJsonObject("gate") {
            put("inflight", h.gateInflight)
            put("queued", h.gateQueued)
            if (h.gateLimit <= 0) put("max", "unlimited") else put("max", h.gateLimit)
            // V4-213: the gate's own measurements. These were literals (0 and []) while the
            // in-process gate kept no counts, so the console's in-flight list was always empty.
            put("acquired", h.gateAcquired)
            put("released", h.gateReleased)
            put("waited", h.gateWaited)
            put("avg_wait_ms", h.gateAvgWaitMs)
            putJsonArray("live") {
                h.gateLive.forEach { slot ->
                    addJsonObject {
                        put("label", slot.label)
                        put("compact", slot.compact)
                        put(
                            "phase",
                            when (slot.phase) {
                                GatePhase.CONNECT -> "connect"
                                GatePhase.STREAMING -> "streaming"
                            },
                        )
                        put("age_ms", slot.ageMs)
                        put("idle_ms", slot.idleMs)
                    }
                }
            }
            put("stream_idle_ms", h.streamIdleMs)
        }
        put("maxInflight", if (h.gateLimit <= 0) null else h.gateLimit)
        putJsonObject("health") {
            put("localOriginErrors", h.localOriginErrors)
            put("providerErrors", h.providerErrors)
        }
        putJsonArray("pids") {}
    }
}
